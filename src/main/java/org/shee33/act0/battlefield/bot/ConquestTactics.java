package org.shee33.act0.battlefield.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 征服模式的据点取舍：在多个据点之间选出本 AI 士兵此刻最该去的一个。MC-free 纯函数，可单测。
 *
 * <p><b>为什么不是"去最近的敌方点"。</b>那会让整支 AI 部队像铁屑贴磁铁一样全部拥到同一个点，
 * 另外两个点无人问津——票数在征服里由控点数之差决定，散在三个点上各守一个远比四个人叠在一个点上
 * 有价值。因此打分里同时包含"点的战略价值"与"这个点已经有多少自己人"。
 *
 * <p><b>真人队长的指令拥有压倒性权重。</b>{@code SquadManager.SquadOrder} 是人类小队长下达的
 * 进攻/防守目标；AI 服从它比 AI 自己算得更"对"要重要得多——玩家下了指令却看到 bot 各干各的，
 * 那是比走错点严重得多的体验问题。
 */
public final class ConquestTactics {

    /** 据点相对本方的归属。 */
    public enum PointStance {
        /** 本方已完全控制。 */
        MINE,
        /** 敌方控制。 */
        ENEMY,
        /** 中立（无人控制或正在易手）。 */
        NEUTRAL
    }

    /**
     * 距离衰减尺度（格）。
     *
     * <p>48 格：大战场地图尺度远大于街机竞技场，取街机那套 24 会让 AI 几乎只看最近的点。
     */
    public static final double DISTANCE_SCALE = 48.0D;

    /**
     * 每个已在点内的友军对该点价值的折减。
     *
     * <p>0.18 使第 5 个人到场时价值只剩约三成——恰好压过"这个点最近"带来的加成，从而把后来者
     * 推向别的点。这是征服模式下的"散开"，与班组级的
     * {@link SquadTactics#separationStrength} 解决的是不同尺度的同一问题。
     */
    public static final double CROWD_PENALTY_PER_ALLY = 0.18D;

    /** 本方据点上有敌人时的紧迫度倍数——正在被翻的点必须优先回防。 */
    public static final double THREATENED_OWN_POINT_MULTIPLIER = 1.6D;

    /** 己方牢固控制且无敌人的点，价值压到很低，避免 AI 蹲在空点上。 */
    public static final double SECURE_OWN_POINT_BASE = 0.25D;

    private ConquestTactics() {
    }

    /**
     * 一个据点的态势快照。
     *
     * @param captureLevelForMe  以本方为正的争夺度，{@code +1} 为本方满控、{@code -1} 为敌方满控
     * @param distance           AI 到该点中心的水平距离（格）
     * @param squadOrdered       小队长是否把该点设为了指令目标
     */
    public record PointAssessment(int pointId,
                                  PointStance stance,
                                  double captureLevelForMe,
                                  double distance,
                                  int alliesInZone,
                                  int enemiesInZone,
                                  boolean squadOrdered) {

        public boolean contested() {
            return alliesInZone > 0 && enemiesInZone > 0;
        }
    }

    /**
     * 全局战况。
     *
     * @param myPoints    本方控点数
     * @param enemyPoints 敌方控点数
     * @param ticketRatio 本方剩余票数占初始票数的比例，{@code 0~1}
     */
    public record Situation(int myPoints, int enemyPoints, double ticketRatio) {

        /** 控点数落后即在流失票数——征服的票数流失只惩罚控点较少的一方。 */
        public boolean bleeding() {
            return myPoints < enemyPoints;
        }
    }

    /**
     * 该据点此刻对本 AI 的价值，越高越该去；{@code 0} 表示不值得去。
     *
     * <p>构成：归属基值 × 距离衰减 × 拥挤折减 × 争夺紧迫度 × 指令倍数。乘法而非加法，
     * 是为了让任一维度的"完全不合适"（比如已经挤了六个人）能真正把该点排除，
     * 而加权求和总会留下一个不小的底分。
     */
    public static double value(PointAssessment point, Situation situation) {
        double base = switch (point.stance()) {
            case NEUTRAL -> 1.0D;
            // 敌方点略低于中立：中立点只需推进，敌方点要先中立化再占领，耗时约两倍。
            case ENEMY -> 0.85D;
            case MINE -> point.enemiesInZone() > 0 ? 1.0D : SECURE_OWN_POINT_BASE;
        };
        if (point.stance() == PointStance.MINE && point.enemiesInZone() > 0) {
            base *= THREATENED_OWN_POINT_MULTIPLIER;
        }
        // 控点落后时，翻点的收益高于守点——这是止血的唯一手段。
        if (situation.bleeding() && point.stance() != PointStance.MINE) {
            base *= 1.0D + (1.0D - clamp01(situation.ticketRatio())) * 0.5D;
        }
        double proximity = 1.0D / (1.0D + Math.max(0.0D, point.distance()) / DISTANCE_SCALE);
        double crowding = Math.max(0.0D, 1.0D - CROWD_PENALTY_PER_ALLY * Math.max(0, point.alliesInZone()));
        return base * proximity * crowding;
    }

    /**
     * 该据点上的小队指令是否已经完成——本方控制且无人争夺。
     *
     * <p>完成后指令不再压制其它选择，否则整队会在一个已经拿下且无人来抢的点上站到对局结束。
     */
    public static boolean orderFulfilled(PointAssessment point) {
        return point.stance() == PointStance.MINE && point.enemiesInZone() == 0;
    }

    /**
     * 选出此刻最该去的据点；无可去（或列表为空）时返回空。
     *
     * <p><b>指令是分层而非加权的。</b>只要存在尚未完成的小队指令目标，就<b>只在指令目标之间</b>
     * 比较，几何价值完全不参与——否则地图另一头的指令目标永远会被脚下的顺路点压过去，玩家下了
     * 指令却看到 AI 各干各的。用倍数加权做不到这一点：大战场的点间距可达数百格，任何固定倍数都
     * 会被足够大的距离差击穿。
     *
     * <p>指令一旦完成（{@link #orderFulfilled}）即退出这一层，AI 恢复自主判断。
     */
    public static Optional<PointAssessment> pick(List<PointAssessment> points, Situation situation) {
        List<PointAssessment> ordered = new ArrayList<>();
        for (PointAssessment point : points) {
            if (point.squadOrdered() && !orderFulfilled(point)) {
                ordered.add(point);
            }
        }
        return bestByValue(ordered.isEmpty() ? points : ordered, situation);
    }

    private static Optional<PointAssessment> bestByValue(List<PointAssessment> points, Situation situation) {
        PointAssessment best = null;
        double bestValue = 0.0D;
        for (PointAssessment point : points) {
            double v = value(point, situation);
            if (v > bestValue) {
                bestValue = v;
                best = point;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 该据点是否已经"不需要更多人"——用于让 AI 在点内站够人之后转去下一个点。
     *
     * <p>判据是本方满控、无敌人、且在场友军已达小队规模。占领速度在
     * {@code ConquestRules.captureStep} 里对人数有上限收益，堆更多人是纯浪费。
     */
    public static boolean saturated(PointAssessment point, int squadSize) {
        return point.stance() == PointStance.MINE
                && point.enemiesInZone() == 0
                && point.alliesInZone() >= Math.max(1, squadSize);
    }

    private static double clamp01(double v) {
        return Math.max(0.0D, Math.min(1.0D, v));
    }
}
