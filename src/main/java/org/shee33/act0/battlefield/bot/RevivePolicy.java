package org.shee33.act0.battlefield.bot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 倒地救援的取舍：值不值得为一个倒地队友脱离战斗、以及该救谁。MC-free 纯函数，可单测。
 *
 * <p><b>核心判据是"赶不赶得到"。</b>倒地只有 15 秒，而大战场的据点间距动辄上百格。朝一个必然
 * 流血至死的队友跑过去，等于用自己的一条命换零收益——这是最容易让 AI 看起来很蠢的行为之一。
 * 因此本类先算"以当前移动速度能否在流血结束前完成救援"，赶不到就直接放弃。
 *
 * <p><b>残血不救。</b>倒地的人往往就躺在杀死他的那把枪的射界里。残血 AI 冲过去扶人是送双杀，
 * 玩家会看到两具尸体而不是一次英勇救援。血量门槛与
 * {@link RetreatPolicy#DEFAULT_BREAK_OFF_HEALTH} 刻意取同一量级：正在脱离交火的人不该被
 * 救援决策拉回火线。
 */
public final class RevivePolicy {

    /** 救援生效距离（格）。与 {@code ConquestMatch} 的 {@code distanceToSqr > 16.0} 判定一致。 */
    public static final double REVIVE_RANGE_BLOCKS = 4.0D;

    /** 徒手救援耗时（秒），对应默认 {@code REVIVE_DURATION_TICKS = 60}。 */
    public static final double REVIVE_SECONDS = 3.0D;

    /** 医疗针救援耗时（秒）：{@code SupplyRules.SYRINGE_SPEED_MULTIPLIER = 3}，故为三分之一。 */
    public static final double SYRINGE_REVIVE_SECONDS = 1.0D;

    /**
     * 尝试救援所需的最低血量比例。
     *
     * <p>取 0.35 略高于 {@link RetreatPolicy#DEFAULT_BREAK_OFF_HEALTH}（0.30）：让"该撤退"的判定
     * 先于"去救人"生效，否则 AI 会在刚决定脱离的下一 tick 又被救援目标拽回原地。
     */
    public static final double MIN_HEALTH_FRACTION = 0.35D;

    /**
     * 赶路时间的安全余量系数。
     *
     * <p>1.25 表示只在"预计耗时 × 1.25 仍来得及"时才出发。寻路绕行、被地形卡顿、路上被迫交火
     * 都会让实际耗时高于直线距离估算；不留余量会导致大量"跑到一半人就死了"的无效救援。
     */
    public static final double TRAVEL_SAFETY_FACTOR = 1.25D;

    private RevivePolicy() {
    }

    /**
     * 一个倒地队友的救援候选。
     *
     * @param distance          AI 到他的距离（格）
     * @param secondsLeft       他还剩多少秒流血至死
     * @param permitted         本 AI 是否有救援权限（同小队 / 支援兵 / 手持医疗针）
     * @param hasSyringe        本 AI 是否持有医疗针（决定救援耗时）
     * @param closerAllyGoing   是否已有更近的友军在赶去救他
     */
    public record Candidate(UUID targetId,
                            double distance,
                            int secondsLeft,
                            boolean permitted,
                            boolean hasSyringe,
                            boolean closerAllyGoing) {

        /** 本次救援本身需要的时长（秒）。 */
        public double reviveSeconds() {
            return hasSyringe ? SYRINGE_REVIVE_SECONDS : REVIVE_SECONDS;
        }
    }

    /**
     * 本 AI 当前状态。
     *
     * @param healthFraction         当前血量比例，{@code 0~1}
     * @param speedBlocksPerSecond   预估移动速度（格/秒）；步行约 4.3，疾跑约 5.4
     * @param engagedWithEnemy       是否正在与敌人交火
     */
    public record Situation(double healthFraction,
                           double speedBlocksPerSecond,
                           boolean engagedWithEnemy) {
    }

    /**
     * 赶到并完成救援所需的预估秒数（含安全余量）。
     *
     * <p>已在生效距离内时赶路耗时为零，只算救援本身。
     */
    public static double estimatedSeconds(Candidate candidate, Situation situation) {
        double speed = Math.max(0.1D, situation.speedBlocksPerSecond());
        double travel = Math.max(0.0D, candidate.distance() - REVIVE_RANGE_BLOCKS) / speed;
        return travel * TRAVEL_SAFETY_FACTOR + candidate.reviveSeconds();
    }

    /** 是否值得为这个候选脱离当前行动。 */
    public static boolean worthGoing(Candidate candidate, Situation situation) {
        if (!candidate.permitted() || candidate.closerAllyGoing()) {
            return false;
        }
        if (situation.healthFraction() < MIN_HEALTH_FRACTION) {
            return false;
        }
        if (candidate.secondsLeft() <= 0) {
            return false;
        }
        return estimatedSeconds(candidate, situation) <= candidate.secondsLeft();
    }

    /**
     * 选出最该去救的人：在所有来得及救的候选里取<b>最紧迫的</b>（剩余时间最短）。
     *
     * <p>刻意不取"最近的"：最近的那个可能还有 14 秒，而 5 格外那个只剩 3 秒。先救快死的，
     * 两个都能救回来；先救近的，远的必死。
     */
    public static Optional<Candidate> pick(List<Candidate> candidates, Situation situation) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (!worthGoing(candidate, situation)) {
                continue;
            }
            if (best == null || candidate.secondsLeft() < best.secondsLeft()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 是否已进入可以开始扶起的距离。 */
    public static boolean inRange(double distance) {
        return distance <= REVIVE_RANGE_BLOCKS;
    }
}
