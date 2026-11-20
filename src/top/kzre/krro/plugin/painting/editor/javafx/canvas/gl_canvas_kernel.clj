(ns top.kzre.krro.plugin.painting.editor.javafx.canvas.gl-canvas-kernel
  (:require
    [taoensso.timbre :as log]
    [top.kzre.krro.core.util.promise :as promise :refer [plet plet>]]
    [top.kzre.krro.plugin.painting.core.changes.viewport :as viewport]
    [top.kzre.krro.plugin.painting.core.project.canvas :as pc]
    [top.kzre.krro.plugin.painting.core.schedule.executor :as executor]
    [top.kzre.krro.plugin.painting.core.schedule.scheduler-impl :as scheduler-impl]
    [top.kzre.krro.plugin.painting.core.viewport :as vp]
    [top.kzre.krro.plugin.painting.editor.javafx.canvas.gl :as gl]
    [top.kzre.krro.plugin.painting.editor.javafx.canvas.protocol :as proto :refer [ICanvasKernel]]
    [top.kzre.krro.plugin.painting.editor.javafx.input.pointer :as pointer]
    [top.kzre.krro.plugin.painting.editor.javafx.input.protocol :as input])
  (:import
    (com.huskerdev.grapl.gl GLContext GLProfile)
    (com.huskerdev.openglfx.canvas GLCanvas GLCanvas$Builder GLCanvas$Builder$ContextDescription$New)
    (com.huskerdev.openglfx.canvas.events GLRenderEvent)
    (com.huskerdev.openglfx.lwjgl LWJGLExecutor)
    (java.lang AutoCloseable)
    (java.util.concurrent Callable)
    (java.util.function Consumer)
    (javafx.scene.canvas Canvas)
    (javafx.scene.layout StackPane)
    (top.kzre.krro.canvas.gl.tile TiledTextureNormalBlitter)
    (top.kzre.krro.core.util SerialExecutor)
    (top.kzre.krro.plugin.painting.editor.javafx.glfx
      CheckerDraw
      CheckerShader
      ClearDraw
      GLRuntime
      FrameBlitter)
    (top.kzre.krro.util.tile TiledCanvas)))


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

(defn- make-state-atom []
  (atom {:layers           nil
         :current-layer-id nil
         :image-width      0
         :image-height     0
         :viewport         nil
         :closed?           false
         :gl-canvas-ready? false
         ;; 最近一帧的拷贝
         :last-canvas nil
         }))

;; ═══════════════════════════════════════════════
;; 渲染请求
;; ═══════════════════════════════════════════════
(defn- render-cached!
  "用缓存的画布去绘制"
  [{:keys [sync-blitter
           checker-shader
           gl-canvas
           state-atom]
    :as _kernel}]
  (let [{:keys [gl-canvas-ready? closed?
               ^TiledCanvas last-canvas
                image-width image-height
                viewport]} @state-atom
        viewport-w (.getWidth gl-canvas)
        viewport-h (.getHeight gl-canvas)
        render-ready?
        (and
          (not closed?)
          gl-canvas-ready?
          (some? last-canvas)
          (not (.isClosed last-canvas))
          (pos? viewport-w)
          (pos? viewport-h)
          (pos? image-width)
          (pos? image-height))
        view-mat2d-inv
        (vp/viewport->mat2d-invert
          (or viewport (vp/make-viewport)))]
    (when render-ready?
      (let [canvas (.copy last-canvas)]
        (.send ^FrameBlitter sync-blitter
               canvas
               (double viewport-w)
               (double viewport-h)
               (CheckerDraw. (ClearDraw/fromRgba gray-bg)
                             checker-shader (int checker-grid)
                             0 0 image-width image-height
                             viewport-w viewport-h
                             view-mat2d-inv)
               (FrameBlitter/scaled gl-canvas))))
    ))

(defn- request-render!
  [{:keys [sync-blitter
           checker-shader
           gl-runtime
           gl-canvas
           scheduler
           render-task-id
           state-atom]
    :as _kernel} changes]
  (let [{:keys [gl-canvas-ready? closed?
                layers current-layer-id
                image-width image-height
                viewport]} @state-atom
        viewport-w (.getWidth gl-canvas)
        viewport-h (.getHeight gl-canvas)
        render-ready?
        (and
          (not closed?)
          gl-canvas-ready?
          (pos? viewport-w)
          (pos? viewport-h)
          (pos? image-width)
          (pos? image-height))]
    ;; 无效的绘制状态下直接放弃
    (if render-ready?
      (let [^SerialExecutor gl-exec (.getExecutor ^GLRuntime gl-runtime)
            view-mat2d-inv
            (vp/viewport->mat2d-invert
              (or viewport (vp/make-viewport)))]
        (plet
          [canvas
           (executor/submit!
             render-task-id
             scheduler
             layers
             :changes          changes
             :image-width      image-width
             :image-height     image-height
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
                 ;; 帧提交 + fence 全部封装在 send 内部。没有合成结果时传 nil——
                 ;; send 会退化成只投递背景，棋盘格因此首帧就能出现，
                 ;; 不必等到第一次有内容的重绘
                 (let [^TiledCanvas canvas canvas
                       copy (.copy canvas)
                       [old _] (swap-vals! state-atom assoc :last-canvas copy)]
                   ;; 保留旧帧
                   (when-let [cached-canvas (:last-canvas old)]
                     (when-not (.isClosed cached-canvas)
                       (.close cached-canvas)))
                   (.send ^FrameBlitter sync-blitter
                          canvas
                          (double viewport-w)
                          (double viewport-h)
                          (CheckerDraw. (ClearDraw/fromRgba gray-bg)
                                        checker-shader (int checker-grid)
                                        0 0 image-width image-height
                                        viewport-w viewport-h
                                        view-mat2d-inv)
                          (FrameBlitter/stretched))))))]))
      ;; 已经放弃的
      (promise/rejected
        (ex-info "invalid render state" {})))))

;; ═══════════════════════════════════════════════
;; 协议实现
;; ═══════════════════════════════════════════════

(defrecord GLCanvasKernalImpl
  [
   ;; 显示相关
   ^GLCanvas gl-canvas
   ^Canvas overlay-canvas
   ^StackPane fx-container
   ^FrameBlitter sync-blitter
   ;; 合成相关
   ^GLRuntime gl-runtime
   ^CheckerShader checker-shader
   scheduler
   render-task-id

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
    ;; 异步渲染，请求重绘
    (plet> [_ (request-render! this changes)]
           (.repaint gl-canvas)))

  (close [_]
    (let [[old _] (swap-vals! state-atom assoc :closed? true)]
      (when-not (:closed? old)
        (doseq [step [
                      ;; 立刻关生产者，不再产出新数据
                      (when mouse-input #(input/stop! mouse-input))
                      (when scheduler #(.close ^AutoCloseable scheduler))
                      ;; 消费者按消费流逆序关闭 repaint->blitter
                      (when sync-blitter
                        #(try
                           (.close ^FrameBlitter sync-blitter)
                           (catch Throwable t
                             (log/warn t "sync-blitter close failed"))))
                      (when-let [^TiledCanvas cached (:last-canvas old)]
                        #(try
                           (.close cached)
                           (catch Throwable t
                             (log/warn t "last-canvas close failed"))))
                      ;; kernel 自持的 GLCanvas 会话资源
                      (when checker-shader
                        #(try
                           (.close ^CheckerShader checker-shader)
                           (catch Throwable t
                             (log/warn t "checker-shader close failed"))))
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

        checker-shader        (CheckerShader. gray-bg (first checker-colors) (second checker-colors))
        sync-blitter   (FrameBlitter/create (int pc/global-tile-size))
        mouse-input    (pointer/make-pointer-input container on-event)

        scheduler      (scheduler-impl/make-scheduler
                         {:backend :gl
                          :gl-composite-context-promise
                          (gl/glfx-composite-context-promise)})

        state-atom (make-state-atom)
        kernel
        (map->GLCanvasKernalImpl
          {:gl-canvas      gl-canvas
           :overlay-canvas overlay-canvas
           :fx-container   container
           :sync-blitter   sync-blitter
           :gl-runtime     gl-runtime
           :checker-shader        checker-shader
           :scheduler      scheduler
           :render-task-id render-task-id
           :on-event       on-event
           :mouse-input    mouse-input
           :state-atom     state-atom})]

    (.addOnInitEvent
      gl-canvas
      (reify Consumer
        (accept [_ _event]
          (swap! state-atom assoc :gl-canvas-ready? true)
          (proto/repaint kernel [(viewport/refreshed)])
          )))

    ;; ── onRenderEvent：GLCanvas 的 GL 线程，消费最新一帧 ──
    (.addOnRenderEvent
      gl-canvas
      (reify Consumer
        (^void accept [_ event]
          (.poll ^FrameBlitter sync-blitter
                 ^GLRenderEvent event))))

    ;; ── onReshapeEvent：surface 尺寸变化，触发一次重渲染 ──
    (.addOnReshapeEvent
      gl-canvas
      (reify Consumer
        (accept [_ _]
          ;; 先用缓存绘制,在请求重新渲染
          (render-cached! kernel)
          (proto/repaint kernel [(viewport/refreshed)]))))

    (input/start! mouse-input)

    {:container container
     :kernel    kernel}))