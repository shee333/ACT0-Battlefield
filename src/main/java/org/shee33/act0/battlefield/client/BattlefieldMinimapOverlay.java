package org.shee33.act0.battlefield.client;

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
import org.shee33.act0.battlefield.BattlefieldConfig;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.BreakthroughHudDto;
import org.shee33.act0.battlefield.network.BreakthroughPointDto;
import org.shee33.act0.battlefield.network.ControlPointHudDto;
import org.shee33.act0.battlefield.network.DownedMateDto;
import org.shee33.act0.battlefield.network.SquadMateHudDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BF 风格小地图（大修版）—— 《小地图大修规格文档》。
 *
 * <p>默认<b>旋转模式</b>：整图随视角旋转、前方永远朝上；北朝上保留为配置项
 * （{@code minimapNorthUp}）。两模式共享全部子系统，方位统一按
 * {@link MinimapMath#screenBearing} 换算，零特判。
 *
 * <p>MC 与规格 demo 的一处实现差异（结果等价、代价更低）：demo 用"位置组随世界旋转 +
 * 字形组反向旋转"两层 SVG 分组来保证标签正立；这里改为先用 {@link MinimapMath#project}
 * 算出<b>已含旋转</b>的屏幕坐标，再以不旋转的姿态绘制字形——字形天然正立，省掉每个标记
 * 一次 push/rotate/pop。只有世界网格这种连续场才真的需要旋转姿态。
 *
 * <p>{@code enableScissor} 的裁剪矩形始终取屏幕固定的面板区域，旋转只发生在其内部的
 * PoseStack 上，因此裁剪永远正确（这正是此前不敢做旋转模式的顾虑之一）。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldMinimapOverlay {

    private static final int SIZE = CombatHudOverlay.minimapSize();
    private static final int MARGIN = CombatHudOverlay.margin();
    /** 世界网格间距（格）。 */
    private static final int GRID_BLOCKS = 12;
    /** 边缘方向指示与罗盘贴内圈的距离（px）。 */
    private static final int RIM_INSET = 13;
    private static final int COMPASS_INSET = 9;

    /** 归一化后的据点标记，屏蔽征服/突破两套 DTO 的差异。 */
    private record Marker(int pointId, String name, double x, double z, int color,
                          boolean contested, int progress) {}

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
        List<DownedMateDto> downed;
        BattleHudDto conquest = ClientBattleHud.hud();
        BreakthroughHudDto breakthrough = ClientBreakthroughHud.hud();
        if (ClientBattleHud.isShown() && conquest != null) {
            markers = conquestMarkers(conquest);
            squad = conquest.squad();
            downed = conquest.downedMates();
        } else if (ClientBreakthroughHud.isShown() && breakthrough != null && breakthrough.show()) {
            markers = breakthroughMarkers(breakthrough);
            squad = breakthrough.squad();
            downed = List.of();
        } else {
            return;
        }

        long now = Tween.now();
        GuiGraphics gg = event.getGuiGraphics();
        int mapX = MARGIN;
        int mapY = gg.guiHeight() - SIZE - MARGIN;
        renderMinimap(gg, mc.font, mapX, mapY, player, markers, squad, downed, now);
    }

    private static List<Marker> conquestMarkers(BattleHudDto hud) {
        List<Marker> markers = new ArrayList<>();
        for (ControlPointHudDto p : hud.points()) {
            markers.add(new Marker(p.pointId(), p.name(), p.x(), p.z(),
                    MinimapMath.conquestPointColor(p.owner(), p.pressure(), hud.myFaction()),
                    p.pressure() != 0, p.progress()));
        }
        return markers;
    }

    /** 突破模式只画当前扇区的目标点（架构决策保留）。 */
    private static List<Marker> breakthroughMarkers(BreakthroughHudDto hud) {
        List<Marker> markers = new ArrayList<>();
        for (BreakthroughPointDto p : hud.points()) {
            if (p.sectorIndex() != hud.currentSectorId()) {
                continue;
            }
            markers.add(new Marker(p.pointId(), p.name(), p.x(), p.z(),
                    MinimapMath.breakthroughPointColor(p.owner(), p.pressure()),
                    p.pressure() != 0, p.progress()));
        }
        return markers;
    }

    private static void renderMinimap(GuiGraphics gg, Font font, int mapX, int mapY, Player player,
                                      List<Marker> markers, List<SquadMateHudDto> squad,
                                      List<DownedMateDto> downed, long now) {
        float intro = MinimapAnimator.introPanel(now);
        if (intro <= 0.01f) {
            return;
        }
        float cx = mapX + SIZE / 2f;
        float cy = mapY + SIZE / 2f;
        boolean northUp = BattlefieldConfig.MINIMAP_NORTH_UP.get();
        float mapRotDeg = MinimapMath.mapRotationFor(
                MinimapAnimator.smoothYaw(player.getYRot(), now), northUp);
        double mapRotRad = Math.toRadians(mapRotDeg);
        double s = MinimapMath.pixelsPerBlock(SIZE);
        double px = player.getX();
        double pz = player.getZ();

        // 开场：面板以左下角为锚 scale 0.9→1 + 淡入。
        gg.pose().pushPose();
        if (intro < 1f) {
            float scale = 0.9f + 0.1f * intro;
            gg.pose().translate(mapX, mapY + SIZE, 0);
            gg.pose().scale(scale, scale, 1f);
            gg.pose().translate(-mapX, -(mapY + SIZE), 0);
        }

        gg.fill(mapX, mapY, mapX + SIZE, mapY + SIZE, withAlpha(MinimapMath.BG, intro));

        // 裁剪矩形是屏幕固定的面板区域；旋转只发生在它内部。
        gg.enableScissor(mapX, mapY, mapX + SIZE, mapY + SIZE);
        renderWorldGrid(gg, cx, cy, px, pz, mapRotDeg, s, intro);
        renderPoints(gg, font, markers, cx, cy, px, pz, mapRotRad, s, mapX, mapY, intro, now);
        renderMates(gg, squad, downed, cx, cy, px, pz, mapRotRad, s, intro, now);
        renderPings(gg, cx, cy, px, pz, mapRotRad, s, intro, now);
        renderDamageArcs(gg, cx, cy, mapRotRad, intro, now);
        renderEdgeIndicators(gg, font, markers, squad, downed, cx, cy, px, pz, mapRotRad, intro, now);
        renderViewCone(gg, cx, cy, mapRotDeg, player.getYRot(), northUp, intro);
        renderIntroSweep(gg, cx, cy, intro, now);
        gg.disableScissor();

        renderBorder(gg, mapX, mapY, intro);
        renderCompass(gg, font, cx, cy, mapRotDeg, intro);
        gg.pose().popPose();
    }

    /**
     * 世界网格：12 格间距细线，随世界平移+旋转，玩家移动时脚下流动，提供运动感。
     *
     * <p>覆盖范围取 {@code VIEW_R × 1.5}（>√2 倍半径），旋转到任意角度时面板四角都不会露底。
     */
    private static void renderWorldGrid(GuiGraphics gg, float cx, float cy,
                                        double px, double pz, float mapRotDeg, double s, float intro) {
        int color = withAlpha(0xFFFFFFFF, 0.06f * intro);
        double reach = MinimapMath.VIEW_R * 1.5;
        gg.pose().pushPose();
        gg.pose().translate(cx, cy, 0);
        gg.pose().mulPose(Axis.ZP.rotationDegrees(mapRotDeg));
        gg.pose().translate(-px * s, -pz * s, 0);

        int kMinX = (int) Math.floor((px - reach) / GRID_BLOCKS);
        int kMaxX = (int) Math.ceil((px + reach) / GRID_BLOCKS);
        int kMinZ = (int) Math.floor((pz - reach) / GRID_BLOCKS);
        int kMaxZ = (int) Math.ceil((pz + reach) / GRID_BLOCKS);
        float z0 = (float) ((kMinZ * GRID_BLOCKS) * s);
        float z1 = (float) ((kMaxZ * GRID_BLOCKS) * s);
        float x0 = (float) ((kMinX * GRID_BLOCKS) * s);
        float x1 = (float) ((kMaxX * GRID_BLOCKS) * s);
        for (int k = kMinX; k <= kMaxX; k++) {
            float gx = (float) (k * GRID_BLOCKS * s);
            gg.fill(Math.round(gx), Math.round(z0), Math.round(gx) + 1, Math.round(z1), color);
        }
        for (int k = kMinZ; k <= kMaxZ; k++) {
            float gz = (float) (k * GRID_BLOCKS * s);
            gg.fill(Math.round(x0), Math.round(gz), Math.round(x1), Math.round(gz) + 1, color);
        }
        gg.pose().popPose();
    }

    private static void renderPoints(GuiGraphics gg, Font font, List<Marker> markers,
                                     float cx, float cy, double px, double pz, double mapRotRad,
                                     double s, int mapX, int mapY, float intro, long now) {
        for (Marker marker : markers) {
            double dist = Math.hypot(marker.x() - px, marker.z() - pz);
            float fade = MinimapMath.edgeFade(dist, MinimapMath.VIEW_R, MinimapMath.FADE_R) * intro;
            if (fade <= 0.01f) {
                continue;
            }
            double[] p = MinimapMath.project(marker.x(), marker.z(), px, pz, mapRotRad, s, cx, cy);
            int sx = (int) Math.round(p[0]);
            int sy = (int) Math.round(p[1]);

            renderEventRings(gg, sx, sy, marker, fade, now);
            drawDiamond(gg, sx, sy, withAlpha(marker.color(), fade));

            if (marker.contested()) {
                double shown = MinimapAnimator.pointProgress(marker.pointId(), marker.progress());
                float breathe = 0.55f + 0.3f * (float) Math.sin(now / 300.0);
                HudShapes.ringArc(gg, sx, sy, 10f, 1.5f, (float) (shown / 100.0),
                        MinimapMath.YELLOW, fade * breathe);
            }

            String label = marker.name().length() > 1 ? marker.name().substring(0, 1) : marker.name();
            int labelW = font.width(label);
            int labelX = sx + 4;
            int labelY = sy - font.lineHeight / 2;
            gg.fill(labelX - 1, labelY - 1, labelX + labelW + 1, labelY + font.lineHeight + 1,
                    withAlpha(MinimapMath.LABEL_BG, fade));
            gg.drawString(font, label, labelX, labelY, withAlpha(0xFFFFFFFF, fade), false);
        }
    }

    /**
     * 据点事件双波扩散环：r 5→18、opacity 0.9→0，600ms outCubic，第二波延迟 250ms。
     * 只放一次，不循环。
     */
    private static void renderEventRings(GuiGraphics gg, int sx, int sy, Marker marker,
                                         float fade, long now) {
        long age = ClientCapturePointEvent.minimapEventAgeMs(marker.pointId());
        if (age < 0L) {
            return;
        }
        for (int wave = 0; wave < 2; wave++) {
            long waveAge = age - wave * 250L;
            if (waveAge < 0L || waveAge >= 600L) {
                continue;
            }
            float v = Tween.Ease.OUT_CUBIC.apply(waveAge / 600f);
            HudShapes.ringSegment(gg, sx, sy, 5f + 13f * v, 2f, 0f, 360f,
                    marker.color(), fade * 0.9f * (1f - v));
        }
    }

    private static void renderMates(GuiGraphics gg, List<SquadMateHudDto> squad,
                                    List<DownedMateDto> downed, float cx, float cy,
                                    double px, double pz, double mapRotRad, double s,
                                    float intro, long now) {
        Map<String, Integer> bleedByName = new HashMap<>();
        for (DownedMateDto d : downed) {
            bleedByName.put(d.name(), d.remainingSeconds());
        }
        Set<String> present = new HashSet<>();
        for (SquadMateHudDto mate : squad) {
            if (mate.self() || !mate.alive()) {
                continue;
            }
            present.add(mate.name());
            double[] render = MinimapAnimator.mateRenderPos(mate.name(), mate.x(), mate.z());
            double dist = Math.hypot(render[0] - px, render[1] - pz);
            float fade = MinimapMath.edgeFade(dist, MinimapMath.VIEW_R, MinimapMath.FADE_R) * intro;
            if (fade <= 0.01f) {
                continue;
            }
            double[] p = MinimapMath.project(render[0], render[1], px, pz, mapRotRad, s, cx, cy);
            int sx = (int) Math.round(p[0]);
            int sy = (int) Math.round(p[1]);

            if (mate.downed()) {
                float breathe = 0.55f + 0.45f * (float) Math.sin(now / 260.0);
                Integer remaining = bleedByName.get(mate.name());
                if (remaining != null) {
                    float left = Math.max(0f, Math.min(1f,
                            remaining / (CombatHudMath.BLEED_MS / 1000f)));
                    HudShapes.ringArc(gg, sx, sy, 7f, 1.5f, left, MinimapMath.SQUAD_DOWNED, fade);
                }
                drawSquadPip(gg, sx, sy, withAlpha(MinimapMath.SQUAD_DOWNED, fade * breathe));
            } else {
                drawSquadPip(gg, sx, sy, withAlpha(MinimapMath.SQUAD_ALIVE, fade));
            }
        }
        MinimapAnimator.retainMates(present);
    }

    private static void renderPings(GuiGraphics gg, float cx, float cy, double px, double pz,
                                    double mapRotRad, double s, float intro, long now) {
        for (MinimapAnimator.Ping ping : MinimapAnimator.pings(now)) {
            double dist = Math.hypot(ping.x() - px, ping.z() - pz);
            float fade = MinimapMath.edgeFade(dist, MinimapMath.VIEW_R, MinimapMath.FADE_R)
                    * intro * MinimapAnimator.pingAlpha(ping, now);
            if (fade <= 0.01f) {
                continue;
            }
            double[] p = MinimapMath.project(ping.x(), ping.z(), px, pz, mapRotRad, s, cx, cy);
            int sx = (int) Math.round(p[0]);
            int sy = (int) Math.round(p[1]);
            float ring = MinimapAnimator.pingRingProgress(ping, now);
            if (ring < 1f) {
                float v = Tween.Ease.OUT_CUBIC.apply(ring);
                HudShapes.ringSegment(gg, sx, sy, 5f + 16f * v, 1.5f, 0f, 360f,
                        CombatHudMath.GOLD, fade * 0.9f * (1f - v));
            }
            drawDiamondSmall(gg, sx, sy, withAlpha(CombatHudMath.GOLD, fade));
        }
    }

    /**
     * 受击方向弧。屏幕方位<b>每帧</b>按当前 mapRot 重算，因此旋转模式下玩家转身时威胁方向
     * 在屏幕上始终正确。数据只有方位角、没有坐标——"不下发敌人位置"这条原则不破。
     */
    private static void renderDamageArcs(GuiGraphics gg, float cx, float cy, double mapRotRad,
                                         float intro, long now) {
        float r = SIZE / 2f - 4f;
        for (float[] arc : MinimapAnimator.activeDamageArcs(now)) {
            double bearing = MinimapMath.screenBearing(arc[0], mapRotRad);
            float centerDeg = (float) Math.toDegrees(bearing);
            HudShapes.ringSegment(gg, cx, cy, r, 3.5f, centerDeg - 28.6f, 57.2f,
                    0xFFFF4D40, arc[1] * intro);
        }
    }

    /**
     * 边缘方向指示：只给<b>目标据点</b>（常显 0.75）与<b>倒地队友</b>（呼吸闪烁）。
     * 普通存活队友超界仍不显示——"不 clamp 贴边"的决策只为这两类高价值目标破例。
     */
    private static void renderEdgeIndicators(GuiGraphics gg, Font font, List<Marker> markers,
                                             List<SquadMateHudDto> squad, List<DownedMateDto> downed,
                                             float cx, float cy, double px, double pz,
                                             double mapRotRad, float intro, long now) {
        float rr = SIZE / 2f - RIM_INSET;
        for (Marker marker : markers) {
            double dx = marker.x() - px;
            double dz = marker.z() - pz;
            if (Math.hypot(dx, dz) <= MinimapMath.VIEW_R) {
                continue;
            }
            double bearing = MinimapMath.screenBearing(MinimapMath.worldBearing(dx, dz), mapRotRad);
            double[] at = MinimapMath.polar(bearing, rr, cx, cy);
            float deg = (float) Math.toDegrees(bearing);
            HudShapes.triangle(gg, (float) at[0], (float) at[1], 5f, deg,
                    marker.color(), 0.75f * intro);
            String label = marker.name().isEmpty() ? "?" : marker.name().substring(0, 1);
            gg.drawString(font, label, (int) Math.round(at[0] - font.width(label) / 2f),
                    (int) Math.round(at[1] + 6), withAlpha(marker.color(), 0.75f * intro), false);
        }

        Set<String> downedNames = new HashSet<>();
        for (DownedMateDto d : downed) {
            downedNames.add(d.name());
        }
        float breathe = 0.55f + 0.45f * (float) Math.sin(now / 260.0);
        for (SquadMateHudDto mate : squad) {
            if (mate.self() || !mate.downed() || !downedNames.contains(mate.name())) {
                continue;
            }
            double[] render = MinimapAnimator.mateRenderPos(mate.name(), mate.x(), mate.z());
            double dx = render[0] - px;
            double dz = render[1] - pz;
            if (Math.hypot(dx, dz) <= MinimapMath.VIEW_R) {
                continue;
            }
            double bearing = MinimapMath.screenBearing(MinimapMath.worldBearing(dx, dz), mapRotRad);
            double[] at = MinimapMath.polar(bearing, rr, cx, cy);
            HudShapes.triangle(gg, (float) at[0], (float) at[1], 5f,
                    (float) Math.toDegrees(bearing), MinimapMath.SQUAD_DOWNED, breathe * intro);
        }
    }

    /** 60° 视野锥 + 玩家箭头。旋转模式固定朝上；北朝上模式随 yaw。 */
    private static void renderViewCone(GuiGraphics gg, float cx, float cy, float mapRotDeg,
                                       float rawYaw, boolean northUp, float intro) {
        float facing = northUp ? MinimapMath.screenAngleFor(rawYaw) : 0f;
        HudShapes.sector(gg, cx, cy, SIZE / 3f, facing, 60f, 0xFFFFFFFF, 0.10f * intro);

        gg.pose().pushPose();
        gg.pose().translate(cx, cy, 0);
        gg.pose().mulPose(Axis.ZP.rotationDegrees(facing));
        int white = withAlpha(MinimapMath.PLAYER, intro);
        gg.fill(-1, -3, 2, 3, white);
        gg.fill(-2, -5, 3, -3, white);
        gg.fill(-1, -4, 2, -3, white);
        gg.pose().popPose();
    }

    /** 开场雷达扫描：一道扇形扫描光转一周即止，不循环。 */
    private static void renderIntroSweep(GuiGraphics gg, float cx, float cy, float intro, long now) {
        float sweep = MinimapAnimator.introSweep(now);
        if (sweep < 0f) {
            return;
        }
        HudShapes.sector(gg, cx, cy, SIZE / 2f * 1.45f, 360f * sweep, 70f,
                0xFF4FA8FF, 0.35f * intro);
    }

    private static void renderBorder(GuiGraphics gg, int mapX, int mapY, float intro) {
        int border = withAlpha(MinimapMath.BORDER, intro);
        gg.fill(mapX, mapY, mapX + SIZE, mapY + 1, border);
        gg.fill(mapX, mapY + SIZE - 1, mapX + SIZE, mapY + SIZE, border);
        gg.fill(mapX, mapY, mapX + 1, mapY + SIZE, border);
        gg.fill(mapX + SIZE - 1, mapY, mapX + SIZE, mapY + SIZE, border);
    }

    /** 动态罗盘环：四向字母沿内缘随 mapRot 游走，N 金色高亮；字形正立。 */
    private static void renderCompass(GuiGraphics gg, Font font, float cx, float cy,
                                      float mapRotDeg, float intro) {
        float rr = SIZE / 2f - COMPASS_INSET;
        String[] letters = {"N", "E", "S", "W"};
        for (int i = 0; i < letters.length; i++) {
            double bearing = Math.toRadians(i * 90f + mapRotDeg);
            double[] at = MinimapMath.polar(bearing, rr, cx, cy);
            int color = i == 0 ? CombatHudMath.GOLD : 0xFFE8EDF2;
            float alpha = (i == 0 ? 1f : 0.45f) * intro;
            gg.drawString(font, letters[i],
                    (int) Math.round(at[0] - font.width(letters[i]) / 2f),
                    (int) Math.round(at[1] - font.lineHeight / 2f),
                    withAlpha(color, alpha), false);
        }
    }

    /** 4-pixel 菱形（据点）。 */
    private static void drawDiamond(GuiGraphics gg, int cx, int cy, int color) {
        gg.fill(cx - 2, cy, cx + 3, cy + 1, color);
        gg.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
        gg.fill(cx, cy - 2, cx + 1, cy + 3, color);
    }

    /** 3-pixel 菱形（Ping）。 */
    private static void drawDiamondSmall(GuiGraphics gg, int cx, int cy, int color) {
        gg.fill(cx - 1, cy, cx + 2, cy + 1, color);
        gg.fill(cx, cy - 1, cx + 1, cy + 2, color);
    }

    /** 3x3 十字（队友），比据点菱形小一圈，避免在密集区喧宾夺主。 */
    private static void drawSquadPip(GuiGraphics gg, int cx, int cy, int color) {
        gg.fill(cx - 1, cy, cx + 2, cy + 1, color);
        gg.fill(cx, cy - 1, cx + 1, cy + 2, color);
    }

    private static int withAlpha(int color, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        int out = Math.round(((color >>> 24) & 0xFF) * a);
        return (color & 0x00FFFFFF) | (out << 24);
    }
}
