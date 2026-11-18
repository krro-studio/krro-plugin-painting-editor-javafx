(ns top.kzre.krro.plugin.painting.editor.javafx.canvas.gl-canvas-kernel
  (:require
   [taoensso.timbre :as log]
   [top.kzre.krro.core.util.channel :refer [<| >| chan close!]]
   [top.kzre.krro.core.util.promise :as promise :refer [plet>]]
   [top.kzre.krro.plugin.painting.core.changes.viewport :as viewport]
   [top.kzre.krro.plugin.painting.core.project.canvas :as pc]
   [top.kzre.krro.plugin.painting.core.schedule.executor :as executor]
   [top.kzre.krro.plugin.painting.core.schedule.scheduler-impl :as scheduler-impl]
   [top.kzre.krro.plugin.painting.editor.javafx.canvas.gl :as gl]
   [top.kzre.krro.plugin.painting.editor.javafx.canvas.protocol :refer [ICanvasKernel]]
   [top.kzre.krro.plugin.painting.editor.javafx.input.pointer :as pointer]
   [top.kzre.krro.plugin.painting.editor.javafx.input.protocol :as input]
   [top.kzre.krro.plugin.painting.core.viewport :as vp])
  (:import
    (com.huskerdev.grapl.gl GLContext GLProfile)
    (com.huskerdev.openglfx.canvas GLCanvas GLCanvas$Builder GLCanvas$Builder$ContextDescription$New)
    (com.huskerdev.openglfx.canvas.events GLRenderEvent)
    (com.huskerdev.openglfx.lwjgl LWJGLExecutor)
    (java.lang AutoCloseable)
    (java.util.concurrent Callable)
    (java.util.function Consumer UnaryOperator)
    (javafx.application Platform)
    (javafx.scene.canvas Canvas)
    (javafx.scene.layout StackPane)
    (top.kzre.krro.canvas.gl.tile TiledTextureNormalBlitter)
    (top.kzre.krro.core.util SerialExecutor)
    (top.kzre.krro.plugin.painting.editor.javafx.glfx
      CheckerDraw CheckerShader ClearDraw GLRuntime GLSyncTiledTextureBlitter)
    (top.kzre.krro.util.math KMath)))


;; ═══════════════════════════════════════════════
;; 状态
;; ═══════════════════════════════════════════════

(def ^:private gray-bg
  "图像范围外的底色，与 CPU 上传路径 Upload.GRAY_ARGB 同色。"
  (float-array [0.533 0.533 0.533 1.0]))

(def ^:private checker-colors
  "棋盘格两色，与 Upload.CHECKER_COLOR1/2 同色。"
  [(float-array [0.8 0.8 0.8 1.0])
   (float-array [0.533 0.533 0.533 1.0])])

;; 格子边长取瓦片尺寸：放大到格子小于几个像素时不再有意义，缩小时代数够用。
(def ^:private checker-grid pc/global-tile-size)

(defn- make-state []
  (atom {:layers           nil
         :current-layer-id nil
         :image-width      0
         :image-height     0
         :viewport         nil
         :closed           false}))

;; ═══════════════════════════════════════════════
;; 渲染请求
;; ═══════════════════════════════════════════════

(defn- request-render!
  [{:keys [sync-blitter
           checker
           repaint-chan
           gl-runtime
           gl-canvas
           scheduler
           render-task-id
           state-atom]
    :as _kernel} changes]
  (let [{:keys [layers current-layer-id
                image-width image-height
                viewport]}
        @state-atom
        ^SerialExecutor gl-exec (.getExecutor ^GLRuntime gl-runtime)
        viewport-w (.getWidth  gl-canvas)
        viewport-h (.getHeight gl-canvas)
        ]
    (plet>
      [canvas
       (executor/submit!
         render-task-id
         scheduler
         layers
         :changes          changes
         :image-width      (if (pos? image-width)
                             image-width
                             viewport-w)
         :image-height     (if (pos? image-height)
                             image-height
                             viewport-h)
         :current-layer-id current-layer-id
         :viewport         viewport
         :viewport-w       viewport-w
         :viewport-h       viewport-h)

       _
       (promise/from-completable-future
         (.submit
           gl-exec
           ^Callable
           (fn []
             ;; 帧提交 + fence 全部封装在 send 内部；canvas 为空或已关闭时跳过
             (when (and canvas (not (.isClosed canvas)))
               (.send ^GLSyncTiledTextureBlitter sync-blitter
                      canvas
                      (double viewport-w)
                      (double viewport-h)
                      (CheckerDraw. (ClearDraw. 0.3 0.3 0.3 1)
                        checker (int checker-grid)
                        0 0 (int image-width) (int image-height)
                        (int viewport-w) (int viewport-h)
                        (KMath/mat2dInv
                          (vp/viewport->mat2d
                            (or viewport (vp/make-viewport))))))))))]
      (>| repaint-chan :dirty)
      (Platform/runLater
        (reify Runnable
          (run [_]
            (when (<| repaint-chan)
              (.repaint gl-canvas))))))))

;; ═══════════════════════════════════════════════
;; 协议实现
;; ═══════════════════════════════════════════════

(defrecord GLCanvasKernalImpl
  [
   ;; 显示相关
   ^GLCanvas gl-canvas
   ^Canvas overlay-canvas
   ^StackPane fx-container
   ^GLSyncTiledTextureBlitter sync-blitter
   ;; 合成相关
   ^GLRuntime gl-runtime
   ^CheckerShader checker
   scheduler
   render-task-id
   repaint-chan
   ;; 业务相关
   on-event
   mouse-input
   ;; 状态
   state-atom]

  ICanvasKernel

  (set-layers! [_ layers]
    (swap! state-atom assoc :layers layers))

  (set-current-layer-id! [_ layer-id]
    (swap! state-atom assoc :current-layer-id layer-id))

  (set-image-size! [_ width height]
    (swap! state-atom assoc
           :image-width  width
           :image-height height))

  (set-viewport! [_ viewport]
    (swap! state-atom assoc :viewport viewport))

  (overlay-gc [_]
    (.getGraphicsContext2D overlay-canvas))

  (repaint [this changes]
    (request-render! this changes))

  (close [_]
    (let [[old _] (swap-vals! state-atom assoc :closed true)]
      (when-not (:closed old)
        (doseq [step [
                      ;; 立刻关生产者，不再产出新数据
                      (when mouse-input #(input/stop! mouse-input))
                      (when scheduler #(.close ^AutoCloseable scheduler))
                      ;; 消费者按消费流逆序关闭 repaint->blitter
                      #(close! repaint-chan)
                      (when sync-blitter
                        #(try
                           (.close ^GLSyncTiledTextureBlitter sync-blitter)
                           (catch Throwable t
                             (log/warn t "sync-blitter close failed"))))
                      ;; kernel 自持的 GLCanvas 会话资源
                      (when checker
                        #(try
                           (.close ^CheckerShader checker)
                           (catch Throwable t
                             (log/warn t "checker close failed"))))
                      ;; 最后关上下文
                      (when gl-canvas #(.dispose ^GLCanvas gl-canvas))]]
          (when step
            (try
              (step)
              (catch Throwable t
                (log/error t "kernel close step failed")))))))))

;; ═══════════════════════════════════════════════
;; GLCanvas 创建
;; ═══════════════════════════════════════════════

(defn- make-gl-canvas
  "创建与离屏 runtime 共享的 GLCanvas。fps=0——手动 repaint。"
  ^GLCanvas [^GLRuntime gl-runtime]
  (let [^GLContext ctx (.getContext gl-runtime)]
    (-> (GLCanvas$Builder.)
        (.setExecutor LWJGLExecutor/LWJGL_MODULE)
        (.setFps (double 0))
        (.setContextDescription
          (-> (GLCanvas$Builder$ContextDescription$New.)
              (.setShareWith ctx)
              (.setProfile GLProfile/CORE)))
        (.build))))

(defn- build-layout
  [^GLRuntime gl-runtime]
  (let [fx-stack     (StackPane.)
        ^GLCanvas gl-canvas (make-gl-canvas gl-runtime)
        overlay      (Canvas.)]
    (.bind (.widthProperty  overlay) (.widthProperty  fx-stack))
    (.bind (.heightProperty overlay) (.heightProperty fx-stack))

    (doto fx-stack
      (.setMinWidth 0)
      (.setMinHeight 0))

    (doto (.getChildren fx-stack)
      (.add gl-canvas)
      (.add overlay))

    {:container      fx-stack
     :gl-canvas      gl-canvas
     :overlay-canvas overlay}))

;; ═══════════════════════════════════════════════
;; 构造
;; ═══════════════════════════════════════════════

(defn build-kernel
  [^GLRuntime gl-runtime
   {:keys [on-event]}]
  (let [{:keys [container gl-canvas overlay-canvas]}
        (build-layout gl-runtime)

        render-task-id (str "gl-canvas-kernel-" (random-uuid))
        ;; blitter 与棋盘格资源都跟 GLCanvas 的会话同寿，由 kernel 持有；
        ;; 通道只投递帧，不再代管它们
        gl-blitter     (TiledTextureNormalBlitter. (int pc/global-tile-size))
        checker        (CheckerShader. gray-bg (first checker-colors) (second checker-colors))
        ;; 空画布时清成完全透明——棋盘格靠 dst-over 垫在已有像素之下，
        ;; 这里若清成不透明的灰，它就无处可落了
        sync-blitter   (GLSyncTiledTextureBlitter/create gl-blitter)
        mouse-input    (pointer/make-pointer-input container on-event)

        scheduler      (scheduler-impl/make-scheduler
                         {:backend :gl
                          :gl-composite-context-promise
                          (gl/glfx-composite-context-promise)})

        repaint-chan   (chan 1)

        kernel
        (map->GLCanvasKernalImpl
          {:gl-canvas      gl-canvas
           :overlay-canvas overlay-canvas
           :fx-container   container
           :sync-blitter   sync-blitter
           :gl-runtime     gl-runtime
           :checker        checker
           :scheduler      scheduler
           :render-task-id render-task-id
           :repaint-chan   repaint-chan
           :on-event       on-event
           :mouse-input    mouse-input
           :state-atom     (make-state)})]

    ;; ── onRenderEvent：GLCanvas 的 GL 线程，消费最新一帧 ──
    (.addOnRenderEvent
      gl-canvas
      (reify Consumer
        (accept [_ event]
          (.poll ^GLSyncTiledTextureBlitter sync-blitter
                 ^GLRenderEvent event))))

    ;; ── onReshapeEvent：surface 尺寸变化，触发一次重渲染 ──
    (.addOnReshapeEvent
      gl-canvas
      (reify Consumer
        (accept [_ _]
          (request-render! kernel [(viewport/refreshed)]))))

    (input/start! mouse-input)

    {:container container
     :kernel    kernel}))