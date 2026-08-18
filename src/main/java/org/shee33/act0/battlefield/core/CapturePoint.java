package org.shee33.act0.battlefield.core;

/**
 * 单个据点（控制点）的争夺状态。MC-free 纯逻辑，可单测。
 *
 * <p>用一个有符号的 {@link #level} 表示争夺进度：{@code +1} 表示完全被 {@link Faction#ALPHA} 控制，
 * {@code -1} 表示完全被 {@link Faction#BRAVO} 控制，{@code 0} 为中立。归属 {@link #owner} 在越过两端
 * 阈值时确认；敌方夺取已被控制的据点须<b>先中和到 0</b>再继续推到自己一端（经典征服的"先中立再占领"）。
 *
 * <p>每次 {@link #tick(int, int, ConquestRules, double)} 传入双方在区域内的人数：
 * <ul>
 *   <li>双方都有人 → 争夺冻结（CONTESTED）。</li>
 *   <li>无人 → 进度保持（IDLE）。</li>
 *   <li>仅一方有人 → 朝该方推进；防守方把进度推回己方一端。</li>
 * </ul>
 */
public final class CapturePoint {

    private static final double EPS = 1e-6;

    private final int id;
    private final String displayName;

    private Faction owner;   // null = 中立
    private double level;    // [-1,1]，+ 朝 ALPHA，- 朝 BRAVO

    public CapturePoint(int id, String displayName) {
        this.id = id;
        this.displayName = displayName != null ? displayName : ("据点 " + id);
        this.owner = null;
        this.level = 0.0;
    }

    /** 据点编号。 */
    public int id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 当前确认归属；{@code null} 表示中立。 */
    public Faction owner() {
        return owner;
    }

    /** 有符号争夺进度 [-1,1]。 */
    public double level() {
        return level;
    }

    /**
     * 某阵营在 HUD 上的占领比例 [0,1]：己方控制中或正被己方推进时为正。
     */
    public double progressFor(Faction faction) {
        double signed = faction == Faction.ALPHA ? level : -level;
        return Math.max(0.0, Math.min(1.0, signed));
    }

    /**
     * 推进一次争夺并返回本次状态。
     *
    * @param alphaInZone ALPHA 在区域内人数
    * @param bravoInZone BRAVO 在区域内人数
     * @param rules       规则（占点速度）
     * @param deltaSeconds 距上次推进的秒数
     */
    public CaptureStatus tick(int alphaInZone, int bravoInZone, ConquestRules rules, double deltaSeconds) {
        boolean a = alphaInZone > 0;
        boolean b = bravoInZone > 0;
        if (a && b) {
            return CaptureStatus.CONTESTED;
        }
        if (!a && !b) {
            return CaptureStatus.IDLE;
        }
        Faction present = a ? Faction.ALPHA : Faction.BRAVO;
        int count = a ? alphaInZone : bravoInZone;
        double step = rules.captureStep(count, deltaSeconds);
        double signedStep = present == Faction.ALPHA ? step : -step;

        Faction before = owner;
        double prev = level;
        level = clamp(level + signedStep);

        updateOwner();

        // 判定本次事件
        if (owner != before) {
            if (owner == present) {
                return CaptureStatus.CAPTURED;
            }
            if (owner == null) {
                return CaptureStatus.NEUTRALIZED;
            }
        }
        if (owner == present) {
            // 已属本方：如果之前被部分中和，这是在加固
            return level != prev ? CaptureStatus.DEFENDING : CaptureStatus.SECURE;
        }
        return CaptureStatus.CAPTURING;
    }

    private void updateOwner() {
        if (level >= 1.0 - EPS) {
            level = 1.0;
            owner = Faction.ALPHA;
        } else if (level <= -1.0 + EPS) {
            level = -1.0;
            owner = Faction.BRAVO;
        } else if (owner == Faction.ALPHA && level <= EPS) {
            owner = null;
        } else if (owner == Faction.BRAVO && level >= -EPS) {
            owner = null;
        }
    }

    private static double clamp(double v) {
        return v < -1.0 ? -1.0 : (v > 1.0 ? 1.0 : v);
    }

    /** 一次争夺推进的结果状态。 */
    public enum CaptureStatus {
        /** 无人在区域内，进度不变。 */
        IDLE,
        /** 双方都有人，争夺冻结。 */
        CONTESTED,
        /** 一方正在推进占领（尚未完成）。 */
        CAPTURING,
        /** 据点刚被中和为中立。 */
        NEUTRALIZED,
        /** 据点刚被一方占满。 */
        CAPTURED,
        /** 控制方在加固（把被部分中和的进度推回）。 */
        DEFENDING,
        /** 控制方在场且进度已满，无变化。 */
        SECURE
    }
}
