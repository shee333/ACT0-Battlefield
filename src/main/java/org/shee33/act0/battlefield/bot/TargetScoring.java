package org.shee33.act0.battlefield.bot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 目标选择的威胁打分。MC-free 纯函数，可单测。
 *
 * <p>刻意把"枚举与视线判定"（MC 相关，见 {@code BotPerception}）与"选谁"（纯逻辑，本类）分开：
 * 选择策略是最需要反复调整与验证的部分，抽成纯函数后可以直接用单测覆盖各种战场态势，
 * 不必开服务器摆人。
 *
 * <p><b>黏滞是本类存在的主要理由。</b>两个威胁值接近的敌人会让"每 tick 取最高分"的朴素实现
 * 反复横跳，表现为 bot 枪口在两人之间来回摆而始终不开火——这是最典型的"AI 味"之一。
 * 给当前目标一个加成后，只有新目标明显更值得打时才会换。
 */
public final class TargetScoring {

    /**
     * 距离参考值（格）：得分随距离衰减的尺度。
     *
     * <p>取 24 格是因为街机竞技场的典型交火距离在 10~40 格之间，取中段可使该区间内的
     * 距离差异对得分有明显区分度；过大则远近几乎同分，过小则只会盯着最近的人。
     */
    public static final double DISTANCE_SCALE = 24.0D;

    /** 视野中心度对得分的权重；其余为距离权重。 */
    public static final float CENTRALITY_WEIGHT = 0.4F;

    /** 当前目标的默认黏滞加成（30%）。 */
    public static final float DEFAULT_STICKINESS = 0.30F;

    private TargetScoring() {
    }

    /**
     * 一个候选目标的态势快照。
     *
     * @param id               目标标识
     * @param distance         与 bot 的距离（格）
     * @param angleFromFacing  与 bot 当前朝向的夹角绝对值（度）
     * @param hasLineOfSight   眼到目标之间是否无遮挡
     * @param isCurrentTarget  是否为当前正在交火的目标
     * @param extraBonus       调用方施加的额外权重（当前用于集火，见 {@link SquadTactics}），
     *                         与 {@code stickiness} 同量纲，负值按 0 处理
     */
    public record Candidate(UUID id,
                            double distance,
                            float angleFromFacing,
                            boolean hasLineOfSight,
                            boolean isCurrentTarget,
                            float extraBonus) {

        public Candidate {
            extraBonus = Math.max(0.0F, extraBonus);
        }

        /** 无额外权重的候选。 */
        public Candidate(UUID id, double distance, float angleFromFacing,
                         boolean hasLineOfSight, boolean isCurrentTarget) {
            this(id, distance, angleFromFacing, hasLineOfSight, isCurrentTarget, 0.0F);
        }
    }

    /**
     * 该候选是否可交火：必须有视线，且落在视野锥内。
     *
     * <p>无视线者直接排除而非低分排除——打不到的人给再低的分也不该被选中，
     * 否则会出现"bot 死盯着墙后的人不换目标"。
     */
    public static boolean isEngageable(Candidate candidate, AimModel model) {
        return candidate.hasLineOfSight() && model.withinFov(candidate.angleFromFacing());
    }

    /**
     * 威胁得分，越高越优先。不可交火者返回 {@code 0}。
     *
     * <p>构成：距离衰减为主，视野中心度为辅，再叠加两项同量纲的权重——当前目标的黏滞加成，
     * 以及调用方给出的 {@link Candidate#extraBonus}（集火）。
     * 中心度参与打分的理由是——已经大致对着的人更可能是正在交火的人，切给他能减少无谓转身。
     *
     * <p>黏滞与集火<b>相加</b>而非相乘：两者语义上是并列的偏好，相乘会让"既是当前目标又被队友集火"
     * 的人得到远超两项之和的分，从而彻底锁死目标切换。
     */
    public static float score(Candidate candidate, AimModel model, float stickiness) {
        if (!isEngageable(candidate, model)) {
            return 0.0F;
        }
        double proximity = 1.0D / (1.0D + Math.max(0.0D, candidate.distance()) / DISTANCE_SCALE);
        float centrality = 1.0F - Math.min(1.0F,
                Math.abs(candidate.angleFromFacing()) / model.fovHalfAngleDegrees());
        float base = (float) proximity * ((1.0F - CENTRALITY_WEIGHT) + CENTRALITY_WEIGHT * centrality);
        float weight = candidate.extraBonus()
                + (candidate.isCurrentTarget() ? Math.max(0.0F, stickiness) : 0.0F);
        return base * (1.0F + weight);
    }

    /** 取得分最高的可交火目标；无可交火者返回空。 */
    public static Optional<Candidate> select(List<Candidate> candidates, AimModel model, float stickiness) {
        Candidate best = null;
        float bestScore = 0.0F;
        for (Candidate candidate : candidates) {
            float score = score(candidate, model, stickiness);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 以默认黏滞取目标。 */
    public static Optional<Candidate> select(List<Candidate> candidates, AimModel model) {
        return select(candidates, model, DEFAULT_STICKINESS);
    }
}
