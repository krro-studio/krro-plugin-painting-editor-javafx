package top.kzre.krro.plugin.painting.editor.javafx.glfx;

import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLQuad;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

/**
 * 棋盘格着色器——GL 上下文绑定的共享资源（{@link GLProgram} + {@link GLQuad}）。
 *
 * <p>与 GLCanvas 的会话同寿，由 kernel 持有，逐帧注入 {@link CheckerDraw}。
 * 棋盘格是纯程序化图形，不采样任何纹理，所以只需要一次全屏 quad。
 *
 * <p><b>绘制顺序</b>：本类是背景——画在 {@link ClearDraw} 之上、
 * {@link FrameDraw} 的合成纹理之下。它不透明地铺满视口（图像范围内
 * 是格子，范围外是灰），之上的纹理由 normal 混合按自身 alpha 盖上来，
 * 透明处自然露出格子。
 *
 * <h2>坐标空间</h2>
 *
 * <p>两个尺寸空间必须分开：
 * <ul>
 *   <li><b>光栅化</b>用 framebuffer 的<em>物理</em>尺寸（{@code event.width/height}）。
 *       quad 铺满整个 framebuffer，所以 DIP 与物理像素的比例在归一化的
 *       {@code vUv} 里自动抵消，不需要预缩放矩阵。</li>
 *   <li><b>着色</b>用<em>逻辑</em>视口尺寸（{@code uViewport}）：
 *       {@code screen = vUv * uViewport} 落在与 {@code uScreenToImage} 同一个
 *       坐标空间里，也就是 kernel 记录视口 offset/zoom 的那个空间。</li>
 * </ul>
 *
 * <p>方向与合成管线（{@code tile_comp.vert}）一致：图像顶行对应高 y，
 * {@code aPos.y}（{@link GLQuad.Orientation#Y_DOWN}）向下增大，
 * {@code screen.y} 也向下增大——两侧都不需要额外翻转。
 *
 * <p>矩阵按列向量约定相乘：{@code M * vec3(screen, 1.0)}，与
 * {@link top.kzre.krro.canvas.gl.resource.GLProgram#setMat2D} 把
 * {@code [a b c d e f]} 补齐成列主序 mat3 的方式一致。写成行向量
 * {@code vec3 * M} 会得到转置——平移分量直接丢失，棋盘格不再跟着视口平移走。
 *
 * <p><b>格子相位固定在 UI/屏幕坐标</b>（{@code screen / uGrid}），不经过
 * {@code uScreenToImage}——否则格子会跟着图像平移/缩放一起漂，看起来一直在动、
 * 很闪。图像边界判断仍然走图像坐标（{@code logic}），所以只有「格子」部分
 * 被钉在屏幕上，图像的可见范围依旧正确。
 *
 * <p><b>线程契约</b>：{@code draw} / {@code close} 必须在 GLCanvas 的 GL 线程。
 * program 与 quad 在首次 {@code draw} 时惰性创建——构造不要求 GL 上下文。
 */
public final class CheckerShader implements AutoCloseable {

    private static final String VERT =
            "#version 330 core\n"
                    + "layout(location = 0) in vec2 aPos;\n"
                    + "out vec2 vUv;\n"
                    + "void main() {\n"
                    + "    vUv = aPos;\n"
                    + "    gl_Position = vec4(aPos.x * 2.0 - 1.0, 1.0 - aPos.y * 2.0, 0.0, 1.0);\n"
                    + "}\n";

    private static final String FRAG =
            "#version 330 core\n"
                    + "in vec2 vUv;\n"
                    + "uniform vec2 uViewport;\n"
                    + "uniform mat3 uScreenToImage;\n"
                    + "uniform vec4 uImage;\n"
                    + "uniform float uGrid;\n"
                    + "uniform vec4 uGray;\n"
                    + "uniform vec4 uCellA;\n"
                    + "uniform vec4 uCellB;\n"
                    + "out vec4 frag;\n"
                    + "void main() {\n"
                    + "    vec2 screen = vUv * uViewport;\n"
                    + "    vec2 logic = (uScreenToImage * vec3(screen, 1.0)).xy;\n"
                    + "    vec2 rel = logic - uImage.xy;\n"
                    + "    if (rel.x < 0.0 || rel.y < 0.0 || rel.x >= uImage.z || rel.y >= uImage.w) {\n"
                    + "        frag = uGray;\n"
                    + "        return;\n"
                    + "    }\n"
                    + "    // 用 UI 坐标计算格子：位置相对屏幕固定，不随视口平移/缩放漂移\n"
                    + "    ivec2 cell = ivec2(floor(screen / max(uGrid, 1.0)));\n"
                    + "    frag = mod(float(cell.x + cell.y), 2.0) < 0.5 ? uCellA : uCellB;\n"
                    + "}\n";

    private final float[] gray;
    private final float[] cellA;
    private final float[] cellB;

    private GLProgram program;
    private GLQuad quad;
    private boolean closed = false;

    /**
     * @param gray  图像范围外的底色，4 元素 {@code [r g b a]}
     * @param cellA 棋盘格 A 色，4 元素 {@code [r g b a]}
     * @param cellB 棋盘格 B 色，4 元素 {@code [r g b a]}
     * @throws IllegalArgumentException 任一颜色为 null 或长度不足 4
     */
    public CheckerShader(float[] gray, float[] cellA, float[] cellB) {
        this.gray = requireColor(gray, "gray");
        this.cellA = requireColor(cellA, "cellA");
        this.cellB = requireColor(cellB, "cellB");
    }

    /**
     * 画棋盘格背景：图像范围内是格子，范围外是底色。
     *
     * @param fbo           目标 framebuffer
     * @param fboWidth      framebuffer 物理宽，用于光栅化范围
     * @param fboHeight     framebuffer 物理高，用于光栅化范围
     * @param viewportW     逻辑视口宽（与 {@code screenToImage} 同一坐标空间）
     * @param viewportH     逻辑视口高
     * @param x             图像范围左上角 x（图像坐标）
     * @param y             图像范围左上角 y（图像坐标）
     * @param imageWidth    图像宽
     * @param imageHeight   图像高
     * @param grid          棋盘格边长（<b>UI 坐标</b>，通常 8~32 逻辑像素）
     * @param screenToImage 逻辑屏幕 -> 图像坐标的 6 元素仿射逆矩阵
     */
    public void draw(int fbo, int fboWidth, int fboHeight,
                     int viewportW, int viewportH,
                     int x, int y, int imageWidth, int imageHeight, int grid,
                     float[] screenToImage) {
        if (closed) {
            throw new IllegalStateException("CheckerShader is closed");
        }
        if (fbo == 0 || fboWidth <= 0 || fboHeight <= 0
                || viewportW <= 0 || viewportH <= 0) {
            return;
        }
        if (screenToImage == null || screenToImage.length < 6) {
            throw new IllegalArgumentException("screenToImage must have 6 elements");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, fboWidth, fboHeight);
        try {
            GLProgram p = program();
            p.use();
            p.setVec2("uViewport", viewportW, viewportH);
            p.setMat2D("uScreenToImage", screenToImage);
            p.setVec4("uImage", x, y, imageWidth, imageHeight);
            p.setFloat("uGrid", grid);
            setVec4(p, "uGray", gray);
            setVec4(p, "uCellA", cellA);
            setVec4(p, "uCellB", cellB);

            // 背景自己不透明，也不该继承上一帧留下的混合状态
            glDisable(GL_BLEND);

            GLQuad q = quad();
            q.bind();
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            GLQuad.unbind();
        } finally {
            GLProgram.unuse();
        }
    }

    /**
     * 释放 program 与 quad。必须在 GLCanvas 的 GL 线程调用。幂等。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (program != null) {
            program.release();
        }
        if (quad != null) {
            quad.close();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private GLProgram program() {
        if (program == null) {
            program = GLProgram.create(VERT, FRAG);
        }
        return program;
    }

    private GLQuad quad() {
        if (quad == null) {
            quad = GLQuad.create(GLQuad.Orientation.Y_DOWN);
            quad.bindPositionAttribute(0);
        }
        return quad;
    }

    private static void setVec4(GLProgram p, String name, float[] c) {
        p.setVec4(name, c[0], c[1], c[2], c[3]);
    }

    private static float[] requireColor(float[] c, String what) {
        if (c == null || c.length < 4) {
            throw new IllegalArgumentException(what + " must be [r g b a]: " + Arrays.toString(c));
        }
        return c;
    }
}