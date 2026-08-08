package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.BreakthroughHudDto;
import org.shee33.act0.battlefield.network.BreakthroughPointDto;
import org.shee33.act0.battlefield.network.ControlPointHudDto;
import org.shee33.act0.battlefield.network.SquadMateHudDto;

import java.util.ArrayList;
import java.util.List;

/**
 * BF 风格小地图：右下角 100x100 面板，显示据点菱形、小队队友点位与玩家自身朝向箭头。
 *
 * <p>使用 {@link RenderGuiEvent.Post} 每帧绘制。世界坐标以玩家为中心投影到小地图坐标
 * （1 像素 = 2 格，半径 50 格）。地图北朝上固定不转，玩家箭头随视角旋转——取舍理由见
 * {@link MinimapMath} 的类注释。
 *
 * <p>征服与突破两种模式的 HUD 走的是两套互不相干的 DTO（{@link BattleHudDto} /
 * {@link BreakthroughHudDto}），这里先把各自的据点列表归一化成 {@link Marker} 再渲染，
 * 因此两个模式共用同一套绘制代码。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldMinimapOverlay {

    private static final int SIZE = 100;
    private static final int MARGIN_RIGHT = 8;
    private static final int MARGIN_BOTTOM = 8;
    private static final double SCALE = 0.5; // 1 px = 2 blocks → 50-block radius
    private static final int INSET = 3;

    /** 归一化后的据点标记，屏蔽征服/突破两套 DTO 的差异。 */
    private record Marker(int pointId, String name, double x, double z, int color) {}

    private BattlefieldMinimapOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }

        List<Marker> markers;
        List<SquadMateHudDto> squad;
        BattleHudDto conquest = ClientBattleHud.hud();
        BreakthroughHudDto breakthrough = ClientBreakthroughHud.hud();
        if (ClientBattleHud.isShown() && conquest != null) {
            markers = conquestMarkers(conquest);
            squad = conquest.squad();
        } else if (ClientBreakthroughHud.isShown() && breakthrough != null && breakthrough.show()) {
            markers = breakthroughMarkers(breakthrough);
            squad = breakthrough.squad();
        } else {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        int mapX = gg.guiWidth() - SIZE - MARGIN_RIGHT;
        int mapY = gg.guiHeight() - SIZE - MARGIN_BOTTOM;
        renderMinimap(gg, mc.font, mapX, mapY, player, markers, squad);
    }

    private static List<Marker> conquestMarkers(BattleHudDto hud) {
        List<Marker> markers = new ArrayList<>();
        for (ControlPointHudDto p : hud.points()) {
            markers.add(new Marker(p.pointId(), p.name(), p.x(), p.z(),
                    MinimapMath.conquestPointColor(p.owner(), p.pressure(), hud.myFaction())));
        }
        return markers;
    }

    /**
     * 突破模式只画<b>当前扇区</b>的目标点。已推过的扇区目标对当前战术决策没有价值，全画出来
     * 只会让 100x100 的面板挤满无关菱形，违背"玩家能在 0.3 秒内理解这个信息吗"的 HUD 准则。
     */
    private static List<Marker> breakthroughMarkers(BreakthroughHudDto hud) {
        List<Marker> markers = new ArrayList<>();
        for (BreakthroughPointDto p : hud.points()) {
            if (p.sectorIndex() != hud.currentSectorId()) {
                continue;
            }
            markers.add(new Marker(p.pointId(), p.name(), p.x(), p.z(),
                    MinimapMath.breakthroughPointColor(p.owner(), p.pressure())));
        }
        return markers;
    }

    private static void renderMinimap(GuiGraphics gg, Font font, int mapX, int mapY, Player player,
                                      List<Marker> markers, List<SquadMateHudDto> squad) {
        gg.fill(mapX, mapY, mapX + SIZE, mapY + SIZE, MinimapMath.BG);

        gg.fill(mapX, mapY, mapX + SIZE, mapY + 1, MinimapMath.BORDER);
        gg.fill(mapX, mapY + SIZE - 1, mapX + SIZE, mapY + SIZE, MinimapMath.BORDER);
        gg.fill(mapX, mapY, mapX + 1, mapY + SIZE, MinimapMath.BORDER);
        gg.fill(mapX + SIZE - 1, mapY, mapX + SIZE, mapY + SIZE, MinimapMath.BORDER);

        int cx = mapX + SIZE / 2;
        int cz = mapY + SIZE / 2;
        double px = player.getX();
        double pz = player.getZ();

        for (Marker marker : markers) {
            int sx = cx + MinimapMath.offsetX(marker.x() - px, SCALE);
            int sy = cz + MinimapMath.offsetY(marker.z() - pz, SCALE);
            if (!MinimapMath.withinBounds(sx, sy, mapX, mapY, SIZE, INSET)) {
                continue;
            }

            float pulse = ClientCapturePointEvent.minimapPulse(marker.pointId());
            if (pulse > 0f) {
                drawDiamondGlow(gg, sx, sy, MinimapMath.withAlpha(0xFFFFFFFF, pulse * 0.55f));
            }
            drawDiamond(gg, sx, sy, marker.color());

            String label = marker.name().length() > 1 ? marker.name().substring(0, 1) : marker.name();
            int labelW = font.width(label);
            int labelH = font.lineHeight;
            int labelX = sx + 4;
            int labelY = sy - labelH / 2;
            gg.fill(labelX - 1, labelY - 1, labelX + labelW + 1, labelY + labelH + 1, MinimapMath.LABEL_BG);
            gg.drawString(font, label, labelX, labelY, 0xFFFFFFFF, false);
        }

        // 队友画在据点之上：队友位置是高频变化的战术信息，被静态据点标记盖住就失去意义。
        for (SquadMateHudDto mate : squad) {
            if (mate.self() || !mate.alive()) {
                continue;
            }
            int sx = cx + MinimapMath.offsetX(mate.x() - px, SCALE);
            int sy = cz + MinimapMath.offsetY(mate.z() - pz, SCALE);
            if (!MinimapMath.withinBounds(sx, sy, mapX, mapY, SIZE, INSET)) {
                continue;
            }
            drawSquadPip(gg, sx, sy, MinimapMath.squadMateColor(mate.downed()));
        }

        drawPlayerArrow(gg, cx, cz, player.getYRot());
    }

    /** 3x3 十字点位，比据点菱形小一圈，避免在密集区喧宾夺主。 */
    private static void drawSquadPip(GuiGraphics gg, int cx, int cy, int color) {
        gg.fill(cx - 1, cy, cx + 2, cy + 1, color);
        gg.fill(cx, cy - 1, cx + 1, cy + 2, color);
    }

    /** 4-pixel diamond at (cx, cy). */
    private static void drawDiamond(GuiGraphics gg, int cx, int cy, int color) {
        gg.fill(cx - 2, cy, cx + 3, cy + 1, color);
        gg.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
        gg.fill(cx, cy - 2, cx + 1, cy + 3, color);
    }

    /**
     * 6-pixel 光晕菱形（据点事件一次性提亮反馈）：绘制在基础 4px 菱形之下，比其大一圈作为
     * 描边光晕，强度随 {@link ClientCapturePointEvent#minimapPulse(int)} 在 600ms 内线性衰减，
     * 不循环闪烁。
     */
    private static void drawDiamondGlow(GuiGraphics gg, int cx, int cy, int color) {
        gg.fill(cx - 3, cy, cx + 4, cy + 1, color);
        gg.fill(cx - 2, cy - 1, cx + 3, cy + 2, color);
        gg.fill(cx - 1, cy - 2, cx + 2, cy + 3, color);
        gg.fill(cx, cy - 3, cx + 1, cy + 4, color);
    }

    /**
     * 玩家箭头，绕面板中心旋转到玩家实际朝向。绕中心旋转需要"平移到原点→旋转→平移回去"，
     * 直接 {@code mulPose} 会让图形绕 GUI 原点(左上角)公转出屏幕。
     */
    private static void drawPlayerArrow(GuiGraphics gg, int cx, int cz, float yaw) {
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(cx + 0.5f, cz + 0.5f, 0f);
        pose.mulPose(Axis.ZP.rotationDegrees(MinimapMath.screenAngleFor(yaw)));
        pose.translate(-(cx + 0.5f), -(cz + 0.5f), 0f);

        gg.fill(cx - 1, cz - 3, cx + 2, cz + 3, MinimapMath.PLAYER);
        gg.fill(cx - 2, cz - 5, cx + 3, cz - 3, MinimapMath.PLAYER);
        gg.fill(cx - 1, cz - 4, cx + 2, cz - 3, MinimapMath.PLAYER);

        pose.popPose();
    }
}
