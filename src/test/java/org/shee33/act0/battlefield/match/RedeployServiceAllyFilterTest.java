package org.shee33.act0.battlefield.match;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.Faction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedeployService#filterAllyCandidateIds} 是纯函数（只依赖阵营归属表 + 小队归属关系，
 * 不依赖 {@code ServerPlayer}/{@code ServerLevel}），覆盖"排除自己"/"排除敌方"/"排除同小队"。
 */
class RedeployServiceAllyFilterTest {

    @Test
    void excludesSelfEnemyAndSquadmateButKeepsOtherAllies() {
        Map<UUID, Faction> factionOf = new LinkedHashMap<>();
        UUID self = UUID.randomUUID();
        UUID squadmate = UUID.randomUUID();
        UUID otherAlly = UUID.randomUUID();
        UUID enemy = UUID.randomUUID();
        factionOf.put(self, Faction.ALPHA);
        factionOf.put(squadmate, Faction.ALPHA);
        factionOf.put(otherAlly, Faction.ALPHA);
        factionOf.put(enemy, Faction.BRAVO);

        SquadManager squadManager = new SquadManager(4, factionOf);
        squadManager.buildSquads();
        // buildSquads() 里 self 与 squadmate/otherAlly 天然会被分到同一个 4 人小队（同阵营、
        // 只有 3 人不会拆分），因此这里手动把 otherAlly 挪到一个不同的小队，制造"同阵营但不同队"
        // 的场景来验证过滤逻辑真的按小队归属而非阵营归属判断。
        int selfSquadId = squadManager.squadIdOf(self);
        squadManager.getSquads().get(selfSquadId).remove(otherAlly);
        squadManager.getSquadOf().put(otherAlly, selfSquadId + 999);
        squadManager.getSquads().computeIfAbsent(selfSquadId + 999, k -> new java.util.LinkedHashSet<>())
                .add(otherAlly);

        List<UUID> result = RedeployService.filterAllyCandidateIds(self, Faction.ALPHA, factionOf, squadManager);

        assertFalse(result.contains(self), "不应包含自己");
        assertFalse(result.contains(squadmate), "不应包含同小队成员");
        assertFalse(result.contains(enemy), "不应包含敌方玩家");
        assertTrue(result.contains(otherAlly), "应包含同阵营但不同小队的玩家");
    }

    @Test
    void returnsEmptyWhenOnlySelfInFaction() {
        Map<UUID, Faction> factionOf = new LinkedHashMap<>();
        UUID self = UUID.randomUUID();
        factionOf.put(self, Faction.ALPHA);
        SquadManager squadManager = new SquadManager(4, factionOf);
        squadManager.buildSquads();

        List<UUID> result = RedeployService.filterAllyCandidateIds(self, Faction.ALPHA, factionOf, squadManager);

        assertTrue(result.isEmpty());
    }
}
