package org.shee33.act0.battlefield.match;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3f;
import org.shee33.act0.battlefield.core.Faction;

import javax.annotation.Nullable;

/**
 * 轻量粒子反馈：为「据点占领事件」「部署落地」「倒地」三个时刻播放克制的 FlatTheme 风格粒子。
 *
 * <p>全部基于原版 {@link DustParticleOptions}（红石粉尘粒子，纯色扁平、支持自定义 RGB 与大小，
 * 完全不需要注册任何新的 {@code ParticleType}/贴图），颜色复用 {@code BattlefieldHudOverlay}
 * 中已经定义好的阵营配色常量（ALPHA 蓝 / BRAVO 红 / 中立灰 / 失守橙），只做正确的 ARGB→0-1
 * RGB 换算，不自创新颜色。
 *
 * <p>这是"锦上添花"的事件反馈，不是持续特效：每次调用只在触发边沿播放一次，粒子数量
 * （8-15 个）与速度都刻意压低，配合原版粒子的自然物理消散在 1-2 秒内结束，不做常驻喷泉。
 */
public final class BattlefieldFx {

    // ARGB → 0-1 RGB，数值与 BattlefieldHudOverlay 的 BLUE/RED/GREY/DANGER 常量一一对应。
    private static final Vector3f ALPHA_COLOR = rgbOf(0xFF4A90D9);   // BLUE   —— ALPHA 阵营
    private static final Vector3f BRAVO_COLOR = rgbOf(0xFFD94A4A);   // RED    —— BRAVO 阵营
    private static final Vector3f NEUTRAL_COLOR = rgbOf(0xFF8C9196); // GREY   —— 中立/争夺开始
    private static final Vector3f DANGER_COLOR = rgbOf(0xFFFF8C00);  // DANGER —— 失守橙

    /**
     * 倒地提示专用的暗红色：在 BRAVO 红的基础上整体压暗（而非另起一套数值），
     * 用于和"BRAVO 阵营红"区分开——倒地是与阵营无关的危险状态提示，不代表 BRAVO。
     */
    private static final Vector3f DOWNED_COLOR = new Vector3f(
            BRAVO_COLOR.x() * 0.55f, BRAVO_COLOR.y() * 0.35f, BRAVO_COLOR.z() * 0.35f);

    private BattlefieldFx() {
    }

    /** 据点被占领（新占领或从中立/敌手中夺回）：己方蓝/敌方红一次性小簇。 */
    public static void captureBurst(ServerLevel level, double x, double y, double z, @Nullable Faction owner) {
        spawnBurst(level, x, y + 0.3, z, factionColor(owner), 1.1f, 12, 0.5, 0.3, 0.5, 0.02);
    }

    /** 据点争夺/推进开始（STARTED 边沿）：中立灰一次性小簇，标记"这里开始有事发生"。 */
    public static void contestStart(ServerLevel level, double x, double y, double z) {
        spawnBurst(level, x, y + 0.3, z, NEUTRAL_COLOR, 0.9f, 8, 0.5, 0.3, 0.5, 0.015);
    }

    /** 据点失守/被中立化（LOST 边沿）：失守橙一次性小簇。 */
    public static void lost(ServerLevel level, double x, double y, double z) {
        spawnBurst(level, x, y + 0.3, z, DANGER_COLOR, 1.1f, 12, 0.5, 0.3, 0.5, 0.02);
    }

    /** 部署传送落地：脚下一小段向上飘散的己方阵营色粒子，营造克制的"全息投影/传送"质感。 */
    public static void deployLanding(ServerLevel level, double x, double y, double z, @Nullable Faction faction) {
        spawnBurst(level, x, y + 0.05, z, factionColor(faction), 0.85f, 10, 0.3, 0.05, 0.3, 0.06);
    }

    /** 玩家倒地：暗红色一小簇，给附近队友一个"这里有人倒地"的视觉线索，不抢 actionbar 提示的戏。 */
    public static void downed(ServerLevel level, double x, double y, double z) {
        spawnBurst(level, x, y + 0.2, z, DOWNED_COLOR, 1.0f, 10, 0.4, 0.25, 0.4, 0.01);
    }

    private static Vector3f factionColor(@Nullable Faction faction) {
        if (faction == Faction.ALPHA) {
            return ALPHA_COLOR;
        }
        if (faction == Faction.BRAVO) {
            return BRAVO_COLOR;
        }
        return NEUTRAL_COLOR;
    }

    private static void spawnBurst(ServerLevel level, double x, double y, double z, Vector3f color, float scale,
                                    int count, double dx, double dy, double dz, double speed) {
        DustParticleOptions options = new DustParticleOptions(color, scale);
        level.sendParticles(options, x, y, z, count, dx, dy, dz, speed);
    }

    private static Vector3f rgbOf(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        return new Vector3f(r, g, b);
    }
}
