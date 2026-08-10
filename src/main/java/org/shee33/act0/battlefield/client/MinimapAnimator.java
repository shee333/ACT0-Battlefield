package org.shee33.act0.battlefield.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小地图的动效与插值状态 —— 《小地图大修规格文档》§3 丝滑系统 + §4 事件类元素。
 *
 * <p><b>数据通路零改动</b>：队友坐标、据点进度仍按原同步频率下发，这里只在渲染层做逐帧
 * 追赶插值。所谓"丝滑"全部是客户端补出来的，服务端一个字节都没多发。
 */
final class MinimapAnimator {

    /** 队友坐标追赶系数（每帧）。 */
    private static final double MATE_CHASE = 0.10;
    /** 整图朝向平滑系数（每帧）。 */
    private static final float ROT_CHASE = 0.06f;
    /** 据点进度追赶系数。 */
    private static final double PROGRESS_CHASE = 0.12;

    private static final long SWEEP_FADE_IN_MS = 300L;
    private static final long SWEEP_SPIN_MS = 800L;
    private static final long SWEEP_DELAY_MS = 150L;
    private static final long DAMAGE_MS = 900L;
    private static final long PING_POP_MS = 320L;
    private static final long PING_RING_MS = 700L;
    private static final long PING_LIFE_MS = 4000L;
    private static final long PING_FADE_MS = 400L;

    /** 队友的渲染坐标（追赶中的显示值），按玩家名索引。 */
    private static final Map<String, double[]> MATE_RENDER = new LinkedHashMap<>();
    /** 据点争夺进度的渲染值，按据点 id 索引。 */
    private static final Map<Integer, Double> POINT_PROGRESS = new LinkedHashMap<>();

    /** 受击方向：只存方位角，绝不存坐标——不下发敌人位置这条原则不破。 */
    private record DamageArc(float bearingRad, long startMs) {}

    /** 世界锚定的标记。 */
    record Ping(double x, double z, long startMs) {}

    private static final List<DamageArc> DAMAGE = new ArrayList<>();
    private static final List<Ping> PINGS = new ArrayList<>();

    private static float smoothedYaw;
    private static boolean yawInitialised;
    private static long sweepStartMs = -1L;

    private MinimapAnimator() {
    }

    static void clear() {
        MATE_RENDER.clear();
        POINT_PROGRESS.clear();
        DAMAGE.clear();
        PINGS.clear();
        yawInitialised = false;
        sweepStartMs = -1L;
    }

    /** 部署落地时播放开场雷达扫描。 */
    static void playIntro(long now) {
        sweepStartMs = now;
    }

    // ---- 朝向平滑 ----

    /**
     * 平滑后的朝向（度）。走最短弧，玩家甩视角时整图不会在 ±180° 边界倒着转一圈。
     *
     * <p>首帧直接落位，否则进场瞬间会从 0 开始转到当前朝向。
     */
    static float smoothYaw(float rawYaw, long now) {
        if (!yawInitialised) {
            smoothedYaw = rawYaw;
            yawInitialised = true;
            return smoothedYaw;
        }
        smoothedYaw += MinimapMath.shortestArcStep(smoothedYaw, rawYaw, ROT_CHASE);
        return smoothedYaw;
    }

    // ---- 队友坐标插值 ----

    /** 喂入服务端同步的队友坐标，返回追赶后的渲染坐标 {@code [x, z]}。 */
    static double[] mateRenderPos(String name, double syncX, double syncZ) {
        double[] cur = MATE_RENDER.get(name);
        if (cur == null) {
            cur = new double[]{syncX, syncZ};
            MATE_RENDER.put(name, cur);
            return cur;
        }
        cur[0] = MinimapMath.chase(cur[0], syncX, MATE_CHASE);
        cur[1] = MinimapMath.chase(cur[1], syncZ, MATE_CHASE);
        return cur;
    }

    /** 移除本帧不在名单里的队友，避免状态表无限增长。 */
    static void retainMates(Set<String> present) {
        MATE_RENDER.keySet().removeIf(k -> !present.contains(k));
    }

    // ---- 据点进度插值 ----

    /** 喂入服务端同步的争夺进度（0~100），返回追赶后的渲染值。 */
    static double pointProgress(int pointId, double syncProgress) {
        double cur = POINT_PROGRESS.getOrDefault(pointId, syncProgress);
        double next = MinimapMath.chase(cur, syncProgress, PROGRESS_CHASE);
        POINT_PROGRESS.put(pointId, next);
        return next;
    }

    // ---- 受击方向 ----

    /** 记录一次受击来源方位（世界方位角，弧度）。 */
    static void onDamageFrom(float worldBearingRad, long now) {
        DAMAGE.removeIf(d -> now - d.startMs() >= DAMAGE_MS);
        DAMAGE.add(new DamageArc(worldBearingRad, now));
    }

    /**
     * 存活中的受击弧。返回 {@code [世界方位角, 剩余透明度]} 列表——屏幕方位由渲染侧按<b>当前</b>
     * mapRot 每帧重算，这样旋转模式下玩家转身时威胁方向在屏幕上依然正确。
     */
    static List<float[]> activeDamageArcs(long now) {
        List<float[]> out = new ArrayList<>();
        Iterator<DamageArc> it = DAMAGE.iterator();
        while (it.hasNext()) {
            DamageArc d = it.next();
            float t = (now - d.startMs()) / (float) DAMAGE_MS;
            if (t >= 1f) {
                it.remove();
                continue;
            }
            out.add(new float[]{d.bearingRad(), 1f - Tween.Ease.OUT_CUBIC.apply(t)});
        }
        return out;
    }

    // ---- 标记 Ping ----

    static void addPing(double x, double z, long now) {
        PINGS.removeIf(p -> now - p.startMs() >= PING_LIFE_MS + PING_FADE_MS);
        PINGS.add(new Ping(x, z, now));
    }

    static List<Ping> pings(long now) {
        PINGS.removeIf(p -> now - p.startMs() >= PING_LIFE_MS + PING_FADE_MS);
        return PINGS;
    }

    /** Ping 整体透明度：弹出即满，存活 4s 后 400ms 淡出。 */
    static float pingAlpha(Ping ping, long now) {
        long age = now - ping.startMs();
        if (age < PING_POP_MS) {
            return Math.min(1f, Tween.Ease.OUT_BACK.apply(age / (float) PING_POP_MS));
        }
        if (age < PING_LIFE_MS) {
            return 1f;
        }
        return 1f - Tween.Ease.IN_CUBIC.apply(Math.min(1f, (age - PING_LIFE_MS) / (float) PING_FADE_MS));
    }

    /** Ping 弹出时的一次性扩散环进度；≥1 表示已结束。 */
    static float pingRingProgress(Ping ping, long now) {
        return Math.min(1f, (now - ping.startMs()) / (float) PING_RING_MS);
    }

    // ---- 开场雷达扫描 ----

    /** 面板整体入场：scale 0.9→1 + 淡入。返回 0..1 进度。 */
    static float introPanel(long now) {
        if (sweepStartMs < 0L) {
            return 1f;
        }
        return Tween.Ease.OUT_CUBIC.apply(Math.min(1f, (now - sweepStartMs) / (float) SWEEP_FADE_IN_MS));
    }

    /**
     * 扫描光的旋转进度 0..1；返回负数表示当前不该画扫描光。
     *
     * <p>只转一周就结束，不循环——循环的雷达扫描会持续吸引余光，违反对局 HUD"不抢注意力"的纪律。
     */
    static float introSweep(long now) {
        if (sweepStartMs < 0L) {
            return -1f;
        }
        long age = now - sweepStartMs - SWEEP_DELAY_MS;
        if (age < 0L) {
            return 0f;
        }
        if (age >= SWEEP_SPIN_MS) {
            return -1f;
        }
        return Tween.Ease.OUT_CUBIC.apply(age / (float) SWEEP_SPIN_MS);
    }
}
