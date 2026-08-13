package org.shee33.act0.battlefield.bot;

import java.util.List;
import java.util.Optional;

/**
 * 重新部署的落点取舍：阵亡后从哪里回到战场。MC-free 纯函数，可单测。
 *
 * <p><b>为什么不是固定的"小队 &gt; 前沿 &gt; 基地"优先链。</b>那条链是本模组给真人玩家做的默认
 * 推荐，但它只考虑"离前线多近"，不考虑"离我想去的那个点多近"。AI 已经通过
 * {@link ConquestTactics} 选好了目标据点，落点就该按<b>到目标的距离</b>排序——从基地跑回来常常
 * 比在一个远侧翼的队友身上重生更快到位。
 *
 * <p><b>安全性是硬门槛而非权重。</b>本模组已在服务端校验"队友身边 12 格内有敌人则不可作为出生点"
 * （{@code SQUAD_DEPLOY_ENEMY_BLOCK_RADIUS}）。适配层把这个校验结果作为 {@link Option#safe}
 * 传进来；不安全的落点直接排除，不参与比较——落地即被打死的重生不管多近都是负收益。
 */
public final class RedeployPolicy {

    /** 落点类别，与服务端 {@code DeployActionPacket.DeployKind} 的取值一一对应。 */
    public enum Kind {
        /** 在小队队友身上重生。 */
        SQUADMATE,
        /** 在本方控制的据点重生。 */
        POINT,
        /** 在本方基地重生。 */
        BASE
    }

    /**
     * 同类落点之间的偏好加成。
     *
     * <p>队友落点略优于同距离的据点落点：在队友身上重生能立刻形成两人协同，而据点重生往往是
     * 孤身一人。取 0.85 的距离折扣（等效于"看起来近 15%"），小到不会压过真实的路程差距。
     */
    public static final double SQUADMATE_PREFERENCE = 0.85D;

    /**
     * 基地落点的距离惩罚。
     *
     * <p>基地永远安全可用，因此它是兜底项。乘 1.15 让它在与前沿落点距离相当时排在后面
     * ——从基地出发要穿过整段纵深，途中被拦截的概率高于从前沿据点出发。
     */
    public static final double BASE_PENALTY = 1.15D;

    private RedeployPolicy() {
    }

    /**
     * 一个可选落点。
     *
     * @param targetId            传给服务端的目标标识：队友落点为其 UUID 字符串，
     *                            据点落点为据点 id 的十进制串，基地落点为空串
     * @param distanceToObjective 该落点到 AI 目标据点的距离（格）；无目标时传该落点到敌方基地的距离
     * @param safe                服务端校验通过（队友存活且身边无敌人 / 据点确属本方 / 基地已配置）
     */
    public record Option(Kind kind, String targetId, double distanceToObjective, boolean safe) {

        /** 参与排序的有效距离，已计入类别偏好。 */
        public double weightedDistance() {
            double d = Math.max(0.0D, distanceToObjective);
            return switch (kind) {
                case SQUADMATE -> d * SQUADMATE_PREFERENCE;
                case POINT -> d;
                case BASE -> d * BASE_PENALTY;
            };
        }
    }

    /**
     * 选出最佳落点：在所有安全落点里取加权距离最小的一个。
     *
     * <p>没有任何安全落点时返回空——调用方应当继续等待（队友可能被救起、据点可能被夺回），
     * 而不是硬选一个不安全的落点。基地通常恒为安全，所以实际上很少返回空。
     */
    public static Optional<Option> best(List<Option> options) {
        Option best = null;
        for (Option option : options) {
            if (!option.safe()) {
                continue;
            }
            if (best == null || option.weightedDistance() < best.weightedDistance()) {
                best = option;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 倒地的 AI 是否应当放弃等待救援、直接申请重新部署。
     *
     * <p>两种情况值得放弃：<b>没有任何队友来得及救</b>，或<b>剩余流血时间已经短于救援本身的耗时</b>
     * ——后者意味着即便有人正在扶也扶不完。躺满 15 秒再重生，对 AI 而言是纯粹的离场时间；
     * 但也不能一倒地就放弃，那会让真人玩家的救援永远来不及，救援机制形同不存在。
     *
     * @param secondsLeft        剩余流血秒数
     * @param rescuerSecondsAway 最快的潜在救援者预计多少秒能完成救援；无人可救传
     *                           {@link Double#MAX_VALUE}
     */
    public static boolean shouldGiveUp(int secondsLeft, double rescuerSecondsAway) {
        if (secondsLeft <= 0) {
            return true;
        }
        if (rescuerSecondsAway == Double.MAX_VALUE) {
            return true;
        }
        return rescuerSecondsAway > secondsLeft;
    }
}
