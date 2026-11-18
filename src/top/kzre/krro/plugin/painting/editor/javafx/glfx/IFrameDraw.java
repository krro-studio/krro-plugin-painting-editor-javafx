package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;
import top.kzre.krro.canvas.gl.tile.ITiledTextureBlitter;

public interface IFrameDraw extends AutoCloseable{
    void discard();

    void consume(ITiledTextureBlitter blitter, GLRenderEvent event);

    boolean isClosed();
}
