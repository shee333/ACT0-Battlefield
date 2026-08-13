package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 按「对局维度 × 小队」划分的共享黑板：谁在打谁。
 *
 * <p><b>为什么需要共享状态。</b>集火要求 A 知道 B 在打谁，这无法从单个 bot 自身的状态推出。
 * 按小队而非按阵营分桶：集火的意义在于两三个人夹击同一个目标，把整个阵营几十人的交火目标混在
 * 一起会让"已有队友在打"几乎恒为真，加成随之失去区分度。
 *
 * <p><b>真人队友不会向黑板报告。</b>登记只由 bot 自己写入，因此人机混队时 bot 之间会集火，
 * 但不会"读到"真人队友在打谁——那需要窥探真人的准星，既做不到也不该做。
 *
 * <p>单例、仅在服务端主线程访问（与 {@link BotManager} 同一 tick 循环），故用普通 {@code HashMap}。
 */
final class BotSquadBoard {

    static final BotSquadBoard INSTANCE = new BotSquadBoard();

    private record Key(ResourceKey<Level> dimension, int squadId) {
    }

    private final Map<Key, Map<UUID, UUID>> engagements = new HashMap<>();

    private BotSquadBoard() {
    }

    /** 登记本 bot 的当前交火目标；{@code targetId} 为 {@code null} 表示脱火。 */
    void reportEngagement(ResourceKey<Level> dimension, int squadId, UUID botId,
                          @Nullable UUID targetId) {
        Map<UUID, UUID> perSquad = engagements.computeIfAbsent(new Key(dimension, squadId),
                k -> new HashMap<>());
        if (targetId == null) {
            perSquad.remove(botId);
        } else {
            perSquad.put(botId, targetId);
        }
    }

    /**
     * 是否有<b>别的</b>队友已经在打这个目标。
     *
     * <p>排除自己是关键：否则 bot 会因为"我自己在打他"而给当前目标叠一次集火加成，与黏滞重复计权，
     * 把本该只影响初次选目标的机制变成"锁死第一个目标永不切换"。
     */
    boolean teammateEngaging(ResourceKey<Level> dimension, int squadId, UUID askerId,
                             @Nullable UUID targetId) {
        Map<UUID, UUID> perSquad = engagements.get(new Key(dimension, squadId));
        if (perSquad == null || targetId == null) {
            return false;
        }
        for (Map.Entry<UUID, UUID> e : perSquad.entrySet()) {
            if (!e.getKey().equals(askerId) && targetId.equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** 对局结束时清空该维度的全部分桶；不清则每局都留下一组永不再被读取的键。 */
    void clearMatch(ResourceKey<Level> dimension) {
        Iterator<Key> keys = engagements.keySet().iterator();
        while (keys.hasNext()) {
            if (keys.next().dimension().equals(dimension)) {
                keys.remove();
            }
        }
    }

    void clear() {
        engagements.clear();
    }
}
