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

    /** 环背板(整圈,通常用低透明度白色画在进度弧下方)。 */
    static void ringTrack(GuiGraphics gg, float cx, float cy, float radius, float thickness, int argb, float alphaMul) {
        ringArc(gg, cx, cy, radius, thickness, 1f, argb, alphaMul);
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
