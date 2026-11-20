package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import top.kzre.krro.canvas.gl.tile.GLTextureLayerCanvas;
import top.kzre.krro.canvas.gl.tile.TiledTextureCropBlitter;

/**
 * 尺寸适配帧绘制——用 {@link TiledTextureCropBlitter} 按像素 1:1
 * （考虑 DPI）裁剪画到目标，内容不随窗口缩放。
 */
public final class CropFrameDraw extends AbstractFrameDraw {

    private final double dpi;

    public CropFrameDraw(IFrameDraw backgroundDraw,
                         GLTextureLayerCanvas texCanvas,
                         double viewWidth, double viewHeight,
                         long fence,
                         double dpi) {
        super(backgroundDraw, texCanvas, viewWidth, viewHeight, fence);
        this.dpi = dpi;
    }

    public CropFrameDraw(GLTextureLayerCanvas texCanvas,
                         double viewWidth, double viewHeight,
                         long fence,
                         double dpi) {
        super(null, texCanvas, viewWidth, viewHeight, fence);
        this.dpi = dpi;
    }

    @Override
    protected void draw(FrameBlitter blitter, GLRenderEvent event) {
        int fboW = event.width;
        int fboH = event.height;
        if (fboW < 1 || fboH < 1) {
            return;
        }
        // 屏幕扩大时候，视口可能略微大于纹理
        double w = Math.min(viewWidth, viewTex.getWidth());
        double h = Math.min(viewHeight, viewTex.getHeight());

        TiledTextureCropBlitter cropBlitter = blitter.getCropBlitter();
        cropBlitter.blit(
                viewTex, (int) layer,
                (int) w, (int) h,
                event.fbo,
                fboW, fboH,
                dpi);
    }
}