package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.GLTextureLayerCanvas;
import top.kzre.krro.canvas.gl.util.ResourceUtils;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.opengl.GL32.*;

/**
 * 帧绘制请求的抽象基类。
 *
 * <p>持有帧渲染产物的完整信息（纹理、层、canvas、fence、视口尺寸），
 * 提供两种终态操作的公共逻辑：
 *
 * <ul>
 *   <li>{@link #discard()} —— 帧从未被绘制，直接释放资源。</li>
 *   <li>{@link #consume(FrameBlitter, GLRenderEvent)} —— 画背景、
 *       等 GPU 写入、调用子类 {@link #draw(FrameBlitter, GLRenderEvent)}、
 *       glFinish、释放资源。</li>
 * </ul>
 *
 * <p>子类只需实现 {@link #draw(FrameBlitter, GLRenderEvent)}，
 * 决定用哪种 blitter、什么参数画纹理。
 */
public abstract class AbstractFrameDraw implements IFrameDraw {

    protected final AtomicBoolean closed = new AtomicBoolean(false);

    protected final long fence;
    protected final GLTextureLayerCanvas texCanvas;
    protected final GLTexture viewTex;
    protected final long layer;
    protected final TiledCanvas canvas;
    protected final double viewWidth;
    protected final double viewHeight;
    protected final IFrameDraw backgroundDraw;

    protected AbstractFrameDraw(IFrameDraw backgroundDraw,
                                GLTextureLayerCanvas texCanvas,
                                double viewWidth, double viewHeight,
                                long fence) {
        if (texCanvas == null) {
            throw new IllegalArgumentException("texCanvas can't be null.");
        }
        this.backgroundDraw = backgroundDraw;
        this.fence = fence;
        this.texCanvas = texCanvas;
        this.viewTex = texCanvas.getTexture();
        this.layer = texCanvas.getLayer();
        this.canvas = texCanvas.getCanvas();
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
    }

    @Override
    public final void discard() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
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

    @Override
    public final void consume(FrameBlitter blitter, GLRenderEvent event) {
        if (blitter == null) {
            throw new IllegalArgumentException("blitter can't be null.");
        }
        if (event == null) {
            throw new IllegalArgumentException("event can't be null.");
        }
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // 1. 背景在下
        if (backgroundDraw != null) {
            backgroundDraw.consume(blitter, event);
        }

        // 2. 等离屏侧 GPU 写入完成
        if (fence != 0) {
            glWaitSync(fence, 0, GL_TIMEOUT_IGNORED);
        }

        // 3. 子类决定怎么画
        draw(blitter, event);

        // 4. 等 GPU 完成采样
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
     * 子类实现：把纹理画进目标 framebuffer。
     */
    protected abstract void draw(FrameBlitter blitter, GLRenderEvent event);

    @Override
    public final void close() {
        discard();
    }

    @Override
    public final boolean isClosed() {
        return closed.get();
    }
}