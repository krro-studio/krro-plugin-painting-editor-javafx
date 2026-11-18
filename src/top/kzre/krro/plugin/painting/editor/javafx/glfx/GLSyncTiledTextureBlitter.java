package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import org.lwjgl.opengl.GL32;
import top.kzre.krro.canvas.gl.tile.GLTextureLayerCanvas;
import top.kzre.krro.canvas.gl.tile.ITiledTextureBlitter;
import top.kzre.krro.canvas.gl.tile.TiledTextureBlitter;
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
 * <p>画什么由调用方决定：{@link #send} 把内容绘制（{@link FrameDraw} 或空画布时的
 * {@code emptyDraw}）交给 {@code decorate} 装饰后再投递——背景、棋盘格这类
 * {@link IFrameDraw} 装饰器都挂在这里，本类不认识它们。
 */
public class GLSyncTiledTextureBlitter implements AutoCloseable {
    private final ITiledTextureBlitter blitter;
    private final BufferedChannel<IFrameDraw> frameChan;

    private GLSyncTiledTextureBlitter(ITiledTextureBlitter blitter) {
        this.blitter = blitter;
        this.frameChan = new BufferedChannel<>(1, IFrameDraw::discard);
    }

    public static GLSyncTiledTextureBlitter create(ITiledTextureBlitter blitter) {
        return new GLSyncTiledTextureBlitter(blitter);
    }


    /**
     * 在 GLCanvas 的 GL 线程消费最新一帧；没有新帧时什么都不做。
     */
    public void poll(GLRenderEvent event) {
        IFrameDraw draw = frameChan.poll();
        if (draw != null) {
            draw.consume(blitter, event);
        }
    }

    public void send(TiledCanvas canvas, double width, double height){
        send(canvas, width, height, new UnitDraw());
    }


    /**
     * 投递一帧。canvas 无瓦片时退化成装饰后的背景本身，此时不需要等 GPU——
     * 没有纹理要被采样，fence 也就无从产生。
     *
     * @param decorate 背景装饰：把 {@code emptyDraw}（清屏）叠成这一帧的背景，
     *                 内容画在它之上。不能为 null；不需要背景时传恒等函数
     */
    public void send(TiledCanvas canvas, double width, double height,
                     IFrameDraw background) {
        if (background == null) throw new NullPointerException("decorate can't be null");
        if (canvas == null || canvas.getTiles().isEmpty()) {
            frameChan.send(background);
            return;
        }

        glFlush();
        long fence = GL32.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        IFrameDraw frameDraw = makeFrameDraw(background, canvas, width, height, fence);
        if (frameDraw == null) return;

        frameChan.send(frameDraw);
    }

    protected IFrameDraw makeFrameDraw(IFrameDraw background, TiledCanvas canvas,
                                       double width, double height, long fence) {
        if (canvas.getTiles().isEmpty()) {
            throw new IllegalArgumentException("canvas can't be empty");
        }
        GLTextureLayerCanvas texCanvas = GLTextureLayerCanvas.fromCanvas(canvas);
        return new FrameDraw(background, texCanvas, width, height, fence);
    }

    /**
     * 关闭通道。blitter 与棋盘格资源由持有它们的 kernel 负责释放。
     */
    @Override
    public void close() throws Exception {
        blitter.close();
        frameChan.close();

    }
}
