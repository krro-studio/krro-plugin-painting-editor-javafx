package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.GLTextureLayerCanvas;
import top.kzre.krro.canvas.gl.tile.ITiledTextureBlitter;
import top.kzre.krro.canvas.gl.util.ResourceUtils;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opengl.GL32.GL_TIMEOUT_IGNORED;
import static org.lwjgl.opengl.GL32.glDeleteSync;
import static org.lwjgl.opengl.GL32.glFinish;
import static org.lwjgl.opengl.GL32.glWaitSync;

/**
 * 单帧绘制请求——合成纹理的输出层。持有一帧渲染产物的完整信息
 * （纹理、层、canvas、fence、渲染时的视口尺寸），并提供两种终态操作：
 *
 * <ul>
 *   <li>{@link #discard()} —— 帧从未被绘制。GPU 从未采样过 texture，
 *       直接删除 fence + 关闭 canvas，不做 GPU 同步。</li>
 *   <li>{@link #consume(ITiledTextureBlitter, GLRenderEvent)} —— 先让背景
 *       画完，再等离屏 GPU 写入完成、把纹理画上去、glFinish 确保采样结束，
 *       最后释放资源。</li>
 * </ul>
 *
 * <p><b>装饰方向</b>与其他 {@link IFrameDraw} 一致：内层
 * {@code backgroundDraw} 在下，本层的纹理在上。背景为 null 时纹理直接
 * 画在当前 framebuffer 内容上——此时是覆盖还是混合，取决于用哪个
 * blitter：{@link top.kzre.krro.canvas.gl.tile.TiledTextureBlitter}
 * 整块拷贝，
 * {@link top.kzre.krro.canvas.gl.tile.TiledTextureNormalBlitter}
 * 按 alpha 混合。
 *
 * <p><b>两种操作互斥</b>——一个帧最终要么被 discard（channel 满、
 * dispose 清理），要么被 consume（正常渲染路径）。内部的
 * {@link AtomicBoolean} 保证幂等——重复调用是 no-op。
 *
 * <p><b>线程契约</b>：
 * <ul>
 *   <li>{@code consume} 必须在 GLCanvas 的 GL 线程调用——内部的
 *       glWaitSync / draw / glFinish 都要求正确的上下文。</li>
 *   <li>{@code discard} 可在任意线程——它不做 GPU 同步，只删
 *       fence 和关闭 canvas。</li>
 *   <li>{@code close} 走 discard 路径——作为 AutoCloseable 的兜底。</li>
 * </ul>
 */
public class FrameDraw implements IFrameDraw {

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final long fence;
    private final GLTextureLayerCanvas texCanvas;
    private final GLTexture viewTex;
    private final long layer;
    private final TiledCanvas canvas;
    private final double viewWidth;
    private final double viewHeight;

    /**
     * 画在纹理之下的背景，可为 null
     */
    private final IFrameDraw backgroundDraw;

    public FrameDraw(GLTextureLayerCanvas texCanvas,
                     double viewWidth, double viewHeight,
                     long fence) {
        this(null, texCanvas, viewWidth, viewHeight, fence);
    }

    /**
     * @param backgroundDraw 画在纹理之下的背景，可为 null
     * @param viewWidth      渲染时的视口宽（逻辑像素），用于绘制源区域
     * @param viewHeight     渲染时的视口高（逻辑像素）
     * @param fence          离屏侧 GPU 写入完成的同步点；0 表示无 fence
     * @throws IllegalArgumentException texCanvas 为 null
     */
    public FrameDraw(IFrameDraw backgroundDraw,
                     GLTextureLayerCanvas texCanvas,
                     double viewWidth, double viewHeight,
                     long fence) {
        if (texCanvas == null) throw new IllegalArgumentException("texCanvas can't be null.");

        this.backgroundDraw = backgroundDraw;
        this.fence = fence;
        this.texCanvas = texCanvas;
        this.viewTex = texCanvas.getTexture();
        layer = texCanvas.getLayer();
        canvas = texCanvas.getCanvas();
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
    }

    /**
     * 丢弃该帧——GPU 从未采样过 texture。直接删除 fence 并关闭 canvas，
     * 不做 GPU 同步。
     *
     * <p>用于 channel 满、dispose 清理等场景——该帧没有被绘制。
     */
    @Override
    public final void discard() {
        if (!closed.compareAndSet(false, true)) return;
        if (backgroundDraw != null) {
            backgroundDraw.discard();
        }
        if (fence != 0) {
            glDeleteSync(fence);
        }
        if (!canvas.isClosed()) {
            RuntimeException error = ResourceUtils.closeDelayError(canvas);
            if (error != null) {
                throw error;
            }
        }
    }

    /**
     * 消费该帧——画背景、等离屏侧 GPU 写入完成、把纹理画进 event 的
     * FBO、等 GPU 采样结束，然后释放资源。
     *
     * <p><b>线程契约</b>：必须在 GLCanvas 的 GL 线程调用。
     *
     * @param blitter 绘制执行器，与 GLCanvas 会话同寿，非 null
     * @param event   当前 GLCanvas 渲染事件，非 null
     * @throws IllegalArgumentException blitter 或 event 为 null
     */
    @Override
    public final void consume(ITiledTextureBlitter blitter, GLRenderEvent event) {
        if (blitter == null) throw new IllegalArgumentException("blitter can't be null.");
        if (event == null) throw new IllegalArgumentException("event can't be null.");
        if (!closed.compareAndSet(false, true)) return;

        // 1. 背景在下——与离屏侧的 fence 无关，先画掉
        if (backgroundDraw != null) {
            backgroundDraw.consume(blitter, event);
        }

        // 2. 等离屏侧 GPU 写入完成
        if (fence != 0) {
            glWaitSync(fence, 0, GL_TIMEOUT_IGNORED);
        }

        draw(blitter, event);

        // 4. 等 GPU 完成对 viewTex 的采样，再释放
        glFinish();

        if (fence != 0) {
            glDeleteSync(fence);
        }
        RuntimeException error = ResourceUtils.closeDelayError(canvas);
        if (error != null) {
            throw error;
        }
    }

    /**
     * 绘制，默认行为就是把纹理层画到目标 framebuffer。
     *
     * @param blitter 绘制执行器
     * @param event   当前 GLCanvas 渲染事件
     */
    protected void draw(ITiledTextureBlitter blitter, GLRenderEvent event) {
        // 3. 画纹理——源用渲染时的视口尺寸，目标用事件尺寸
        blitter.blit(
                viewTex, (int) layer,
                (int) viewWidth, (int) viewHeight,
                event.fbo,
                event.width, event.height);
    }

    /**
     * 释放资源。幂等。走 discard 路径——不引入额外 GPU 同步。
     */
    @Override
    public final void close() {
        discard();
    }

    @Override
    public final boolean isClosed() {
        return closed.get();
    }
}
