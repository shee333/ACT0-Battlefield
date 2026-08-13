package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.match.ConquestMatch;

import javax.annotation.Nullable;

/**
 * AI 士兵的敌我判定策略。
 *
 * <p><b>判定必须知道"是谁在问"。</b>同一个候选者对不同 bot 的敌我关系不同；写成只看候选者的
 * {@code Predicate<ServerPlayer>} 会让全体 bot 共用一份结果，队友也会被当成敌人。故一律接收
 * {@code asker}。
 *
 * <p><b>默认策略同时覆盖对局内与对局外</b>：
 * <ul>
 *   <li>提问方在征服对局内 → 同局且不同阵营为敌；不在同局者（旁观者、别局玩家）不是敌人；</li>
 *   <li>提问方不在对局内 → 混战，除自己以外皆为敌。这是调试形态，两个裸生成的 bot 可以直接互射。</li>
 * </ul>
 *
 * <p>本轮只接征服模式，突破模式的对局不参与判定（其中的 bot 会退化成上面的混战分支）——
 * 这与"本轮只做征服"的范围决定一致，接突破时在这里补一条查找即可。
 */
public final class BotHostility {

    private BotHostility() {
    }

    /** {@code candidate} 是否为 {@code asker} 的敌人。调用方已先行剔除自身。 */
    public static boolean isEnemy(ServerPlayer asker, ServerPlayer candidate) {
        ConquestMatch match = Act0Battlefield.manager().activeContaining(asker.getUUID());
        if (match == null) {
            return decide(false, -1, -1);
        }
        return decide(true,
                sideIndex(match.factionOf(asker.getUUID())),
                sideIndex(match.factionOf(candidate.getUUID())));
    }

    /**
     * 把阵营映射为方索引：{@code null}（不在本局／中立）为 {@code -1}。
     *
     * <p>转成 int 只为了复用 {@link #decide} 那套已被穷尽单测的三分支逻辑；直接对 {@code Faction}
     * 写判定会多出一组 {@code null} 组合要重新测一遍。
     */
    private static int sideIndex(@Nullable Faction faction) {
        if (faction == null) {
            return -1;
        }
        return faction == Faction.ALPHA ? 0 : 1;
    }

    /**
     * 敌我判定的纯逻辑部分，与 MC 无关，可穷尽单测。
     *
     * <p>三个分支各对应一种真实误判：把队友当敌人、把别局的玩家当敌人、以及裸生成的调试 bot
     * 互不为敌导致完全测不动。
     *
     * @param askerInMatch   提问方是否在某场对局中
     * @param askerSide      提问方的方索引（不在局内时无意义）
     * @param candidateSide  候选者在<b>提问方那场对局</b>中的方索引；{@code < 0} 表示不在该局
     */
    public static boolean decide(boolean askerInMatch, int askerSide, int candidateSide) {
        if (!askerInMatch) {
            return true;
        }
        if (candidateSide < 0) {
            return false;
        }
        return askerSide != candidateSide;
    }
}
