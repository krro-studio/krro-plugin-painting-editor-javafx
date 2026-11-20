package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.GLCanvas;
import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import org.lwjgl.opengl.GL32;
import top.kzre.krro.canvas.gl.tile.*;
import top.kzre.krro.canvas.gl.util.ResourceUtils;
import top.kzre.krro.core.util.BufferedChannel;
import top.kzre.krro.util.tile.TiledCanvas;

import static org.lwjgl.opengl.GL11.glFlush;
import static org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE;

/**
 * 跨线程的帧投递通道：生产侧（离屏 GL 线程）投递 {@link IFrameDraw}，
 * 消费侧（GLCanvas 的 GL 线程）在渲染事件里取最新一帧执行。
 *
 * <p>本类<em>不持有</em> {@link TiledTextureBlitter}——blitter 与 GLCanvas 的
 * 会话同寿，由 kernel 创建并逐事件传进 {@link #poll}；{@link IFrameDraw#consume}
 * 本来就把它当参数收，通道没有必要替它管生命周期。相应地 {@link #close()}
 * 只关通道，一帧帧的绘制对象由 {@link BufferedChannel} 的丢弃回调收尾。
 *
 * <p>画什么由调用方决定：{@link #send} 把内容绘制（{@link IFrameDraw} 或空画布时的
 * {@code emptyDraw}）交给 {@code decorate} 装饰后再投递——背景、棋盘格这类
 * {@link IFrameDraw} 装饰器都挂在这里，本类不认识它们。
 */
public class FrameBlitter implements AutoCloseable {
    private final TiledTextureNormalBlitter normalBlitter;
    private final TiledTextureCropBlitter cropBlitter;
    private final BufferedChannel<IFrameDraw> frameChan;


    private FrameBlitter(TiledTextureNormalBlitter normalBlitter, TiledTextureCropBlitter cropBlitter) {
        this.normalBlitter = normalBlitter;
        this.cropBlitter = cropBlitter;
        this.frameChan = new BufferedChannel<>(1, IFrameDraw::discard);
    }

    public static FrameBlitter create(int tileSize) {
        TiledTextureNormalBlitter normalBlitter = new TiledTextureNormalBlitter(tileSize);
        TiledTextureCropBlitter cropBlitter = new TiledTextureCropBlitter(tileSize);
        return new FrameBlitter(normalBlitter, cropBlitter);
    }


    /**
     * 在 GLCanvas 的 GL 线程消费最新一帧；没有新帧时什么都不做。
     */
    public boolean poll(GLRenderEvent event) {
        IFrameDraw draw = frameChan.poll();
        if (draw != null) {
            // 内部自己选择怎么用本上下文
            draw.consume(this, event);
            return true;
        }
        return false;
    }

    /**
     * 投递一帧。canvas 无瓦片时退化成装饰后的背景本身，此时不需要等 GPU——
     * 没有纹理要被采样，fence 也就无从产生。
     *
     */
    public void send(TiledCanvas canvas,
                     double viewWidth, double viewHeight,
                     IFrameDraw background,
                     FrameDrawFactory factory) {
        if (background == null) throw new NullPointerException("decorate can't be null");
        if (canvas == null || canvas.getTiles().isEmpty()) {
            if (canvas!=null){
                canvas.close();
            }
            frameChan.send(background);
            return;
        }
        glFlush();
        long fence = GL32.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        IFrameDraw frameDraw = factory.create(background, canvas, viewWidth, viewHeight, fence);
        if (frameDraw == null) return;

        frameChan.send(frameDraw);
    }

    public interface FrameDrawFactory{
        IFrameDraw create(IFrameDraw background, TiledCanvas canvas,
                          double viewWidth, double viewHeight, long fence);
    }

    public static FrameDrawFactory scaled(GLCanvas glCanvas){
        return (background, canvas, viewWidth, viewHeight, fence) -> {
            if (canvas.getTiles().isEmpty()) {
                throw new IllegalArgumentException("canvas can't be empty");
            }
            double dpi = glCanvas.getDpi();
            GLTextureLayerCanvas texCanvas = GLTextureLayerCanvas.fromCanvas(canvas);
            return new CropFrameDraw(background, texCanvas, viewWidth, viewHeight, fence, dpi);
        };
    }

    public static FrameDrawFactory stretched(){
        return (background, canvas, viewWidth, viewHeight, fence) -> {
            if (canvas.getTiles().isEmpty()) {
                throw new IllegalArgumentException("canvas can't be empty");
            }
            GLTextureLayerCanvas texCanvas = GLTextureLayerCanvas.fromCanvas(canvas);
            return new NormalFrameDraw(background, texCanvas, viewWidth, viewHeight, fence);
        };
    }



    /**
     * 关闭通道。blitter 与棋盘格资源由持有它们的 kernel 负责释放。
     */
    @Override
    public void close() throws Exception {
        RuntimeException ex = ResourceUtils.closeDelayError(normalBlitter, cropBlitter, frameChan);
        if (ex != null){
            throw ex;
        }
    }

    public TiledTextureCropBlitter getCropBlitter() {
        return cropBlitter;
    }

    public ITiledTextureBlitter getNormalBlitter(){
        return normalBlitter;
    }

}
