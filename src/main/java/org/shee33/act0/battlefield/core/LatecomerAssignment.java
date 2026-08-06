package org.shee33.act0.battlefield.core;

import javax.annotation.Nullable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 中途加入(latecomer)阵营分配的纯逻辑工具类。MC-free，不依赖 ServerPlayer/ServerLevel/
 * ConquestMatch 等运行时状态，只接受两个阵营各自的当前人数与人数上限，方便脱离完整 MC
 * 环境单测。
 *
 * <p><b>背景</b>：ACT0-Battlefield 当前没有"每阵营最大人数"这个配置概念——
 * {@code ConquestMatch#capacityHint()} / {@code BreakthroughMatch#capacityHint()} 返回的固定值
 * 64 只是给 ACT0-Arcade 游戏浏览器展示用的估算容量，从未在任何加入/quickJoin 流程里被拿来做
 * 人数上限校验。因此调用方目前总是把 cap 参数传成 {@link Integer#MAX_VALUE}（等价于"没有上限"），
 * 本方法也就退化为纯 50/50 随机。但算法本身完整保留了"一方满一方没满，必须分到没满的一方"
 * 这条正确性约束——一旦以后真的引入了每方人数上限，调用方只需要把真实的 cap 值传进来，
 * 这里不需要再改。
 */
public final class LatecomerAssignment {

    private LatecomerAssignment() {
    }

    /**
     * 为中途加入的玩家挑选阵营：双方都还有名额时 50/50 随机；只有一方满员时强制分到未满的
     * 一方；双方都满员时返回 {@code null}，交由调用方决定兜底行为（例如拒绝加入）。
     *
     * @param alphaCount ALPHA（北大西洋公约）当前人数
     * @param alphaCap   ALPHA 人数上限；没有上限传 {@link Integer#MAX_VALUE}
     * @param bravoCount BRAVO（无邦军团）当前人数
     * @param bravoCap   BRAVO 人数上限；没有上限传 {@link Integer#MAX_VALUE}
     * @return 挑中的阵营；双方都已满员时返回 {@code null}
     */
    @Nullable
    public static Faction randomFaction(int alphaCount, int alphaCap, int bravoCount, int bravoCap) {
        boolean alphaFull = alphaCount >= alphaCap;
        boolean bravoFull = bravoCount >= bravoCap;
        if (alphaFull && bravoFull) {
            return null;
        }
        if (alphaFull) {
            return Faction.BRAVO;
        }
        if (bravoFull) {
            return Faction.ALPHA;
        }
        return ThreadLocalRandom.current().nextBoolean() ? Faction.ALPHA : Faction.BRAVO;
    }
}
