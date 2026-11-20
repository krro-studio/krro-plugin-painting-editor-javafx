package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import top.kzre.krro.canvas.gl.tile.ITiledTextureBlitter;

import static org.lwjgl.opengl.GL30.*;

/**
 * 清空背景的FrameDraw。不持有任何离屏纹理资源，
 * 直接在 GLCanvas 的 FBO 上执行 glClear 清屏。
 *
 * <p>线程契约：{@code consume} 必须在 GLCanvas 的 GL 线程调用。
 */
public final class ClearDraw implements IFrameDraw {

    private static final int CLEAR_MASK = GL_COLOR_BUFFER_BIT;

    private final float red;
    private final float green;
    private final float blue;
    private final float alpha;

    public ClearDraw(float red, float green, float blue, float alpha) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    public static ClearDraw fromRgba(float[] rgba){

        return new ClearDraw(rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    @Override
    public void discard() {
        // no-op：不持有任何 GPU 资源
    }

    /**
     * 在 GLCanvas 的 FBO 上执行 glClear 清屏。
     *
     * <p>线程契约：必须在 GLCanvas 的 GL 线程调用。
     *
     * @param blitter blit 执行器（本类不使用）
     * @param event   当前 GLCanvas 渲染事件，提供目标 FBO
     */
    @Override
    public void consume(FrameBlitter blitter, GLRenderEvent event) {
        if (event == null) throw new IllegalArgumentException("event can't be null.");

        // 绑定目标 FBO
        glBindFramebuffer(GL_FRAMEBUFFER, event.fbo);
        // 设置清屏颜色
        glClearColor(red, green, blue, alpha);
        // 执行清除
        glClear(CLEAR_MASK);
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public void close() throws Exception {
        // no-op：不持有任何 GPU 资源
    }
}