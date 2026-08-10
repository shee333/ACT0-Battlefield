package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * 手绘 2D 几何图元(六边形填充/描边、径向进度弧),供占点占领特写 HUD 使用 —— {@link GuiGraphics#fill}
 * 只能画矩形,画不了文档要求的六边形轮廓与圆环进度。
 *
 * <p>渲染路径参照仓库里 {@code BattlefieldDeployWorldOverlay#drawLine} 用 {@code RenderType}/
 * {@code VertexConsumer} 手绘几何的技术路线,但目标是 GUI 正交空间而非世界空间:直接读取
 * {@code gg.pose().last().pose()},与 {@link GuiGraphics#fill} 内部实现同源,因此能正确叠加调用方
 * 已经 push 到 GUI pose 栈上的任何 translate/scale(参照 {@code BattlefieldHudOverlay#drawScaledText}
 * 的用法)。
 */
final class HudShapes {

    private static final int HEX_SIDES = 6;

    private HudShapes() {
    }

    private static void beginDraw() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void endDraw(BufferBuilder bb) {
        BufferUploader.drawWithShader(bb.end());
        RenderSystem.disableBlend();
    }

    private static void vertex(BufferBuilder bb, Matrix4f m, float x, float y, int argb, float alphaMul) {
        float a = ((argb >>> 24) & 0xFF) / 255f * alphaMul;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        bb.vertex(m, x, y, 0f).color(r, g, b, a).endVertex();
    }

    /** 六边形顶点(尖顶朝上/下,平边朝左/右),索引 0 为正上方,顺时针排列。 */
    private static float hexX(float cx, float r, int i) {
        return cx + r * (float) Math.cos(Math.toRadians(-90 + 60 * i));
    }

    private static float hexY(float cy, float r, int i) {
        return cy + r * (float) Math.sin(Math.toRadians(-90 + 60 * i));
    }

    /** 填充六边形(以圆心为扇心的三角扇)。 */
    static void fillHex(GuiGraphics gg, float cx, float cy, float r, int argb, float alphaMul) {
        if (alphaMul <= 0f || r <= 0f) {
            return;
        }
        beginDraw();
        Matrix4f m = gg.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        vertex(bb, m, cx, cy, argb, alphaMul);
        for (int i = 0; i <= HEX_SIDES; i++) {
            int idx = i % HEX_SIDES;
            vertex(bb, m, hexX(cx, r, idx), hexY(cy, r, idx), argb, alphaMul);
        }
        endDraw(bb);
    }

    /** 六边形描边(外圈半径 r,内圈 r-thickness 的三角带,构成一条闭合的细边框)。 */
    static void strokeHex(GuiGraphics gg, float cx, float cy, float r, float thickness, int argb, float alphaMul) {
        if (alphaMul <= 0f || r <= 0f) {
            return;
        }
        beginDraw();
        Matrix4f m = gg.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        float inner = Math.max(0f, r - thickness);
        for (int i = 0; i <= HEX_SIDES; i++) {
            int idx = i % HEX_SIDES;
            vertex(bb, m, hexX(cx, r, idx), hexY(cy, r, idx), argb, alphaMul);
            vertex(bb, m, hexX(cx, inner, idx), hexY(cy, inner, idx), argb, alphaMul);
        }
        endDraw(bb);
    }

    /**
     * 径向进度弧:从 12 点方向起,顺时针填充 {@code fraction}(0..1)圈的一段圆环。
     * 对应文档 SVG 的 {@code stroke-dasharray/strokeDashoffset} 效果 —— GuiGraphics 没有描边路径
     * 图元,所以用三角带手动光栅化。{@code fraction>=1} 时画满整圈,即环背板(track)。
     */
    static void ringArc(GuiGraphics gg, float cx, float cy, float radius, float thickness,
                        float fraction, int argb, float alphaMul) {
        float f = Math.max(0f, Math.min(1f, fraction));
        if (f <= 0f || alphaMul <= 0f || radius <= 0f) {
            return;
        }
        beginDraw();
        Matrix4f m = gg.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        float sweepDeg = 360f * f;
        int segs = Math.max(1, (int) Math.ceil(sweepDeg / 6f));
        float inner = Math.max(0f, radius - thickness);
        for (int i = 0; i <= segs; i++) {
            float deg = -90f + sweepDeg * (i / (float) segs);
            float rad = (float) Math.toRadians(deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            vertex(bb, m, cx + radius * cos, cy + radius * sin, argb, alphaMul);
            vertex(bb, m, cx + inner * cos, cy + inner * sin, argb, alphaMul);
        }
        endDraw(bb);
    }

    /**
     * 任意起始角与跨度的圆环段——小地图的受击方向弧、边缘指示所需。
     *
     * <p>与 {@link #ringArc} 的区别：后者恒从 12 点起画"前 N%"，用于进度读数；这里可以指定
     * 起始方位，用于"朝某个方向的一段弧"。角度以正北为 0、顺时针为正（与
     * {@code MinimapMath.screenBearing} 同一约定）。
     */
    static void ringSegment(GuiGraphics gg, float cx, float cy, float radius, float thickness,
                            float startDeg, float sweepDeg, int argb, float alphaMul) {
        if (alphaMul <= 0f || radius <= 0f || sweepDeg == 0f) {
            return;
        }
        beginDraw();
        Matrix4f m = gg.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        int segs = Math.max(1, (int) Math.ceil(Math.abs(sweepDeg) / 6f));
        float inner = Math.max(0f, radius - thickness);
        for (int i = 0; i <= segs; i++) {
            float deg = -90f + startDeg + sweepDeg * (i / (float) segs);
            float rad = (float) Math.toRadians(deg);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            vertex(bb, m, cx + radius * cos, cy + radius * sin, argb, alphaMul);
            vertex(bb, m, cx + inner * cos, cy + inner * sin, argb, alphaMul);
        }
        endDraw(bb);
    }

    /**
     * 扇形视野锥（三角扇）。{@code facingDeg} 为锥中轴方位（正北 0、顺时针），
     * {@code spreadDeg} 为总张角。
     */
    static void sector(GuiGraphics gg, float cx, float cy, float radius,
                       float facingDeg, float spreadDeg, int argb, float alphaMul) {
        if (alphaMul <= 0f || radius <= 0f) {
            return;
        }
        beginDraw();
        Matrix4f m = gg.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        vertex(bb, m, cx, cy, argb, alphaMul);
        int segs = Math.max(2, (int) Math.ceil(spreadDeg / 6f));
        for (int i = 0; i <= segs; i++) {
            float deg = -90f + facingDeg - spreadDeg / 2f + spreadDeg * (i / (float) segs);
            float rad = (float) Math.toRadians(deg);
            vertex(bb, m, cx + radius * (float) Math.cos(rad), cy + radius * (float) Math.sin(rad),
                    argb, alphaMul);
        }
        endDraw(bb);
    }

    /** 实心三角形（边缘方向指示的箭头）。顶点朝 {@code facingDeg} 方位。 */
    static void triangle(GuiGraphics gg, float cx, float cy, float size,
                         float facingDeg, int argb, float alphaMul) {
        if (alphaMul <= 0f || size <= 0f) {
            return;
        }
        beginDraw();
        Matrix4f m = gg.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (float offset : new float[]{0f, 130f, -130f}) {
            float rad = (float) Math.toRadians(-90f + facingDeg + offset);
            float r = offset == 0f ? size : size * 0.75f;
            vertex(bb, m, cx + r * (float) Math.cos(rad), cy + r * (float) Math.sin(rad), argb, alphaMul);
        }
        endDraw(bb);
    }

    /** 环背板(整圈,通常用低透明度白色画在进度弧下方)。 */
    static void ringTrack(GuiGraphics gg, float cx, float cy, float radius, float thickness, int argb, float alphaMul) {
        ringArc(gg, cx, cy, radius, thickness, 1f, argb, alphaMul);
    }

    /**
     * 斜切四边形(平行四边形)——作战 HUD 规格 §4.1 的 −8° 武器槽卡片与金色下划线滑块。
     *
     * <p>对应 CSS 的 {@code transform: skewX(deg)}：以矩形<b>垂直中心</b>为不动轴，上边右移、
     * 下边左移各 {@code (h/2)·tan(-deg)}。选中槽是"以底边为锚向上生长"的，若改用底边做不动轴，
     * 生长时整张卡片会横向漂移，因此这里固定用中心轴，与 CSS skew 的行为一致。
     */
    static void fillSkewedRect(GuiGraphics gg, float x, float y, float w, float h,
                               float skewDeg, int argb, float alphaMul) {
        if (alphaMul <= 0f || w <= 0f || h <= 0f) {
            return;
        }
        float shift = (h / 2f) * (float) Math.tan(Math.toRadians(-skewDeg));
        beginDraw();
        Matrix4f m = gg.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        vertex(bb, m, x + shift, y, argb, alphaMul);
        vertex(bb, m, x - shift, y + h, argb, alphaMul);
        vertex(bb, m, x + w - shift, y + h, argb, alphaMul);
        vertex(bb, m, x + w + shift, y, argb, alphaMul);
        endDraw(bb);
    }

    /**
     * 全屏边缘红晕(规格 §2「受击红晕」)：中心透明、四周渐红的径向渐变。
     *
     * <p>{@code GuiGraphics.fill} 只能画纯色矩形，画不了径向渐变，因此用一圈梯形把屏幕边框
     * 铺满：外圈顶点取目标 alpha、内圈顶点取 0，硬件插值即得到从中心向外渐强的效果。
     * {@code inset} 是透明区半径占比(规格为 55%)。
     */
    static void edgeVignette(GuiGraphics gg, int screenW, int screenH, int argb, float alphaMul, float inset) {
        if (alphaMul <= 0f) {
            return;
        }
        float ix = screenW * inset / 2f;
        float iy = screenH * inset / 2f;
        float cx = screenW / 2f;
        float cy = screenH / 2f;
        float l = cx - ix;
        float r = cx + ix;
        float t = cy - iy;
        float b = cy + iy;

        beginDraw();
        Matrix4f m = gg.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        // 上/下/左/右四条渐变带：外缘不透明、内缘全透。
        quadFade(bb, m, 0, 0, screenW, 0, r, t, l, t, argb, alphaMul);
        quadFade(bb, m, 0, screenH, screenW, screenH, r, b, l, b, argb, alphaMul);
        quadFade(bb, m, 0, 0, 0, screenH, l, b, l, t, argb, alphaMul);
        quadFade(bb, m, screenW, 0, screenW, screenH, r, b, r, t, argb, alphaMul);
        endDraw(bb);
    }

    /** 前两点为外缘(取 alpha)、后两点为内缘(取 0)的渐隐四边形。 */
    private static void quadFade(BufferBuilder bb, Matrix4f m,
                                 float ox1, float oy1, float ox2, float oy2,
                                 float ix2, float iy2, float ix1, float iy1,
                                 int argb, float alphaMul) {
        vertex(bb, m, ox1, oy1, argb, alphaMul);
        vertex(bb, m, ox2, oy2, argb, alphaMul);
        vertex(bb, m, ix2, iy2, argb, 0f);
        vertex(bb, m, ix1, iy1, argb, 0f);
    }

    /** 简易水平条(用于据点行内的压力/进度指示,矩形所以仍走 {@code GuiGraphics.fill} 即可,这里只是集中放置)。 */
    static void flatBar(GuiGraphics gg, int x, int y, int w, int h, int fillW, int bgColor, int fillColor, float alphaMul) {
        gg.fill(x, y, x + w, y + h, withAlpha(bgColor, alphaMul));
        int fw = Math.max(0, Math.min(w, fillW));
        if (fw > 0) {
            gg.fill(x, y, x + fw, y + h, withAlpha(fillColor, alphaMul));
        }
    }

    private static int withAlpha(int color, float alphaMul) {
        int a = Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alphaMul)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
