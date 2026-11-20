package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import top.kzre.krro.canvas.gl.tile.GLTextureLayerCanvas;
import top.kzre.krro.canvas.gl.tile.ITiledTextureBlitter;

/**
 * 正常帧绘制——用 {@link ITiledTextureBlitter} 把纹理整层画到目标，
 * 内容随窗口尺寸缩放。
 */
public final class NormalFrameDraw extends AbstractFrameDraw {

    public NormalFrameDraw(IFrameDraw backgroundDraw,
                           GLTextureLayerCanvas texCanvas,
                           double viewWidth, double viewHeight,
                           long fence) {
        super(backgroundDraw, texCanvas, viewWidth, viewHeight, fence);
    }

    public NormalFrameDraw(GLTextureLayerCanvas texCanvas,
                           double viewWidth, double viewHeight,
                           long fence) {
        super(null, texCanvas, viewWidth, viewHeight, fence);
    }

    @Override
    protected void draw(FrameBlitter blitter, GLRenderEvent event) {
        int fboW = event.width;
        int fboH = event.height;
        if (fboW < 1 || fboH < 1) {
            return;
        }

        ITiledTextureBlitter normalBlitter = blitter.getNormalBlitter();
        normalBlitter.blit(
                viewTex, (int) layer,
                (int) viewWidth, (int) viewHeight,
                event.fbo,
                fboW, fboH);
    }
}