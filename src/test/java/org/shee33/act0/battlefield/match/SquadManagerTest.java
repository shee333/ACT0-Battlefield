package org.shee33.act0.battlefield.match;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.Faction;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquadManagerTest {

    @Test
    void latecomerJoinsTheOnlyUnderfullExistingSquad() {
        Map<UUID, Faction> factionOf = new LinkedHashMap<>();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();
        factionOf.put(a1, Faction.ALPHA);
        factionOf.put(a2, Faction.ALPHA);
        factionOf.put(a3, Faction.ALPHA);

        SquadManager sm = new SquadManager(4, factionOf);
        sm.buildSquads();
        int existingSquadId = sm.squadIdOf(a1);
        assertEquals(3, sm.getSquads().get(existingSquadId).size());

        UUID newcomer = UUID.randomUUID();
        factionOf.put(newcomer, Faction.ALPHA);
        sm.assignLatecomer(newcomer, Faction.ALPHA);

        assertEquals(existingSquadId, sm.squadIdOf(newcomer));
        assertEquals(4, sm.getSquads().get(existingSquadId).size());
        assertEquals(1, sm.getSquads().size(), "不应该新开小队");
    }

    @Test
    void latecomerOpensNewSquadWhenAllExistingSquadsAreFull() {
        Map<UUID, Faction> factionOf = new LinkedHashMap<>();
        UUID[] alphas = new UUID[4];
        for (int i = 0; i < alphas.length; i++) {
            alphas[i] = UUID.randomUUID();
            factionOf.put(alphas[i], Faction.ALPHA);
        }
        SquadManager sm = new SquadManager(4, factionOf);
        sm.buildSquads();
        int fullSquadId = sm.squadIdOf(alphas[0]);
        assertEquals(4, sm.getSquads().get(fullSquadId).size());

        UUID newcomer = UUID.randomUUID();
        factionOf.put(newcomer, Faction.ALPHA);
        sm.assignLatecomer(newcomer, Faction.ALPHA);

        int newSquadId = sm.squadIdOf(newcomer);
        assertNotEquals(fullSquadId, newSquadId);
        assertEquals(1, sm.getSquads().get(newSquadId).size());
        assertTrue(sm.isSquadLeader(newcomer), "新开小队里落单的玩家应自动成为队长");
        assertEquals(4, sm.getSquads().get(fullSquadId).size(), "原小队不受影响");
    }

    @Test
    void firstLatecomerOfANewFactionOpensSquadAtFactionBaseNumber() {
        Map<UUID, Faction> factionOf = new LinkedHashMap<>();
        UUID a1 = UUID.randomUUID();
        factionOf.put(a1, Faction.ALPHA);
        SquadManager sm = new SquadManager(4, factionOf);
        sm.buildSquads();
        assertEquals(1, sm.squadIdOf(a1));

        UUID firstBravo = UUID.randomUUID();
        factionOf.put(firstBravo, Faction.BRAVO);
        sm.assignLatecomer(firstBravo, Faction.BRAVO);

        assertEquals(101, sm.squadIdOf(firstBravo));
        assertTrue(sm.isSquadLeader(firstBravo));
        assertEquals(1, sm.squadIdOf(a1), "ALPHA 现有小队编号不受影响");
        assertEquals(1, sm.getSquads().get(1).size(), "ALPHA 现有小队成员不受影响");
    }

    @Test
    void latecomerDoesNotDisturbOtherPlayersExistingAssignments() {
        Map<UUID, Faction> factionOf = new LinkedHashMap<>();
        UUID leaderA = UUID.randomUUID();
        UUID memberA = UUID.randomUUID();
        UUID leaderB = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        factionOf.put(leaderA, Faction.ALPHA);
        factionOf.put(memberA, Faction.ALPHA);
        factionOf.put(leaderB, Faction.BRAVO);
        factionOf.put(memberB, Faction.BRAVO);

        SquadManager sm = new SquadManager(4, factionOf);
        sm.buildSquads();
        int alphaSquadId = sm.squadIdOf(leaderA);
        int bravoSquadId = sm.squadIdOf(leaderB);
        // 显式把队长晋升为 memberA，制造"队长不是小队里第一个成员"的场景，用来验证增量分配
        // 不会像 buildSquads() 那样重新按迭代顺序指派队长。
        sm.promoteLeader(alphaSquadId, memberA);

        UUID newcomer = UUID.randomUUID();
        factionOf.put(newcomer, Faction.ALPHA);
        sm.assignLatecomer(newcomer, Faction.ALPHA);

        assertEquals(alphaSquadId, sm.squadIdOf(leaderA));
        assertEquals(alphaSquadId, sm.squadIdOf(memberA));
        assertEquals(bravoSquadId, sm.squadIdOf(leaderB));
        assertEquals(bravoSquadId, sm.squadIdOf(memberB));
        assertTrue(sm.isSquadLeader(memberA), "增量分配不应覆盖其他小队已有的队长指派");
        assertEquals(2, sm.getSquads().get(bravoSquadId).size(), "BRAVO 小队不受 ALPHA 侧增量分配影响");
    }

    @Test
    void latecomerRandomlyPicksAmongMultipleUnderfullSquadsOfSameFaction() {
        Set<Integer> chosen = new HashSet<>();
        for (int trial = 0; trial < 200 && chosen.size() < 2; trial++) {
            Map<UUID, Faction> factionOf = new LinkedHashMap<>();
            UUID[] alphas = new UUID[8];
            for (int i = 0; i < alphas.length; i++) {
                alphas[i] = UUID.randomUUID();
                factionOf.put(alphas[i], Faction.ALPHA);
            }
            SquadManager sm = new SquadManager(4, factionOf);
            sm.buildSquads();
            int squad1 = sm.squadIdOf(alphas[0]);
            int squad2 = sm.squadIdOf(alphas[4]);
            assertNotEquals(squad1, squad2);
            // buildSquads() 天然产出的两个小队都是满的(4/4)；直接操作 getSquads() 返回的活跳集合
            // 各抽走一人，人为制造"同阵营同时存在两个未满现有小队"的局面，用来验证 assignLatecomer
            // 是在多个候选里真随机，而不是永远选第一个/最后一个候选。
            sm.getSquads().get(squad1).remove(alphas[0]);
            sm.getSquads().get(squad2).remove(alphas[4]);

            UUID newcomer = UUID.randomUUID();
            factionOf.put(newcomer, Faction.ALPHA);
            sm.assignLatecomer(newcomer, Faction.ALPHA);
            chosen.add(sm.squadIdOf(newcomer));
        }
        assertEquals(2, chosen.size(), "多次试验后应同时观察到两个候选小队都被随机选中过");
    }
}
