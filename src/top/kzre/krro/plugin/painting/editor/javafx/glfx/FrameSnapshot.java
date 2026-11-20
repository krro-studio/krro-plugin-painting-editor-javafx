package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import top.kzre.krro.util.tile.TiledCanvas;

public final class FrameSnapshot implements AutoCloseable {

    private final TiledCanvas canvas;
    private final double viewportW;
    private final double viewportH;
    private final float[] viewMat2DInv;

    public FrameSnapshot(TiledCanvas canvas,
                         double viewportW,
                         double viewportH,
                         float[] viewMat2DInv) {
        if (canvas == null){
            throw new IllegalArgumentException("canvas");
        }
        // 快照必须是有效帧
        if (viewportW <= 0 || viewportH <= 0){
            throw new IllegalArgumentException("invalid frame size");
        }
        this.canvas = canvas;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.viewMat2DInv = viewMat2DInv.clone(); // 防御性拷贝，避免外部修改
    }

    public TiledCanvas getCanvas() {
        return canvas;
    }

    public double getViewportW() {
        return viewportW;
    }

    public double getViewportH() {
        return viewportH;
    }

    public float[] getViewMat2DInv() {
        return viewMat2DInv.clone(); // 每次返回拷贝，调用方不会意外改内部状态
    }

    @Override
    public void close() throws Exception {
        canvas.close();
    }
}