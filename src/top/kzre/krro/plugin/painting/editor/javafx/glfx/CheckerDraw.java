package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;

/**
 * 棋盘格绘制——装饰器。
 *
 * <p>统一方向：内层 {@code backgroundDraw} 先画，本层画在它<em>之上</em>。
 * 于是链条 {@code FrameDraw(CheckerDraw(ClearDraw))} 的阅读顺序就是
 * 从下到上的叠放顺序——清屏、铺棋盘格、最后合成纹理按 alpha 盖上来。
 *
 * <p>discard / close / isClosed 全部转发给 {@code backgroundDraw}。棋盘格自身的
 * GL 资源（program + quad）不在本类，由 kernel 持有 {@link CheckerShader}
 * 并注入——帧对象短命，GL 资源跟上下文同寿。
 *
 * <p><b>线程契约</b>：{@code consume} 必须在 GLCanvas 的 GL 线程调用。
 */
public final class CheckerDraw implements IFrameDraw {

    /**
     * 画在棋盘格之下的背景
     */
    private final IFrameDraw backgroundDraw;
    /**
     * 棋盘格着色器，kernel 持有
     */
    private final CheckerShader shader;
    /**
     * 棋盘格大小（图像坐标）
     */
    private final int gridSize;

    /// 图像范围（图像坐标）
    private final int x;
    private final int y;
    private final int imageW;
    private final int imageH;
    /// 视口变换的逆：逻辑屏幕 -> 图像坐标
    private final float[] viewTransformInv;
    private final double dpi;
    public CheckerDraw(IFrameDraw backgroundDraw, CheckerShader shader, int gridSize,
                       int x, int y, int imageW, int imageH,
                       float[] viewTransformInv, double dpi) {
        this.dpi = dpi;
        if (backgroundDraw == null) throw new IllegalArgumentException("backgroundDraw can't be null");
        if (shader == null) throw new IllegalArgumentException("shader can't be null");
        if (gridSize <= 0) throw new IllegalArgumentException("gridSize must be > 0: " + gridSize);
        this.backgroundDraw = backgroundDraw;
        this.shader = shader;
        this.gridSize = gridSize;
        this.x = x;
        this.y = y;
        this.imageW = imageW;
        this.imageH = imageH;
        /// 逻辑视口尺寸（DIP）——mat2d 所在的坐标空间，不是 framebuffer 物理尺寸
        this.viewTransformInv = viewTransformInv;
    }

    @Override
    public void discard() {
        backgroundDraw.discard();
    }

    @Override
    public void consume(FrameBlitter blitter, GLRenderEvent event) {
        if (event == null) throw new IllegalArgumentException("event can't be null.");
        backgroundDraw.consume(blitter, event);

        // 光栅化范围用 framebuffer 物理尺寸，格子计算用逻辑视口——两者只在缩放屏下不一致
        shader.draw(event.fbo, event.width, event.height,
                x, y, imageW, imageH, gridSize, viewTransformInv, dpi);
    }

    @Override
    public boolean isClosed() {
        return backgroundDraw.isClosed();
    }

    @Override
    public void close() throws Exception {
        backgroundDraw.close();
    }
}
