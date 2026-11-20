package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import com.huskerdev.openglfx.canvas.events.GLRenderEvent;

public interface IFrameDraw extends AutoCloseable{
    void discard();

    void consume(FrameBlitter blitter, GLRenderEvent event);

    boolean isClosed();
}
