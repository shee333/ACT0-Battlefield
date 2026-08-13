package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.bot.ConquestTactics.PointAssessment;
import org.shee33.act0.battlefield.bot.ConquestTactics.PointStance;
import org.shee33.act0.battlefield.bot.ConquestTactics.Situation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConquestTacticsTest {

    private static final Situation EVEN = new Situation(2, 2, 1.0D);
    private static final Situation LOSING = new Situation(1, 3, 0.4D);

    private static PointAssessment point(int id, PointStance stance, double dist,
                                        int allies, int enemies, boolean ordered) {
        return new PointAssessment(id, stance, 0.0D, dist, allies, enemies, ordered);
    }

    @Test
    void secureOwnPointIsWorthMuchLessThanNeutral() {
        double own = ConquestTactics.value(point(1, PointStance.MINE, 10, 0, 0, false), EVEN);
        double neutral = ConquestTactics.value(point(2, PointStance.NEUTRAL, 10, 0, 0, false), EVEN);
        assertTrue(neutral > own * 2.0D, "空的己方点不该吸引 AI 去蹲守");
    }

    @Test
    void ownPointUnderAttackBeatsDistantNeutral() {
        double threatened = ConquestTactics.value(point(1, PointStance.MINE, 40, 1, 2, false), EVEN);
        double faraway = ConquestTactics.value(point(2, PointStance.NEUTRAL, 40, 0, 0, false), EVEN);
        assertTrue(threatened > faraway, "正在被翻的己方点必须优先回防");
    }

    @Test
    void neutralOutranksEnemyHeldAtEqualDistance() {
        double neutral = ConquestTactics.value(point(1, PointStance.NEUTRAL, 20, 0, 0, false), EVEN);
        double enemy = ConquestTactics.value(point(2, PointStance.ENEMY, 20, 0, 0, false), EVEN);
        assertTrue(neutral > enemy, "中立点只需推进，敌方点要先中立化，耗时约两倍");
    }

    @Test
    void closerPointWinsAllElseEqual() {
        double near = ConquestTactics.value(point(1, PointStance.NEUTRAL, 10, 0, 0, false), EVEN);
        double far = ConquestTactics.value(point(2, PointStance.NEUTRAL, 120, 0, 0, false), EVEN);
        assertTrue(near > far);
    }

    /**
     * 拥挤折减是征服尺度的"散开"：没有它整支 AI 部队会全部叠在同一个点上，
     * 而票数由控点数之差决定，散开守三个点远比四人守一个点有价值。
     */
    @Test
    void crowdingPushesLaterArrivalsToOtherPoints() {
        PointAssessment crowdedNear = point(1, PointStance.NEUTRAL, 10, 5, 0, false);
        PointAssessment emptyFar = point(2, PointStance.NEUTRAL, 45, 0, 0, false);
        assertTrue(ConquestTactics.value(emptyFar, EVEN) > ConquestTactics.value(crowdedNear, EVEN),
                "已挤了 5 个人的近点应让位给空着的远点");
    }

    @Test
    void crowdingNeverProducesNegativeValue() {
        PointAssessment swarmed = point(1, PointStance.NEUTRAL, 5, 99, 0, false);
        assertTrue(ConquestTactics.value(swarmed, EVEN) >= 0.0D);
    }

    /**
     * 指令是分层而非加权的：任何固定倍数都会被足够大的距离差击穿，而大战场点间距可达数百格。
     */
    @Test
    void squadOrderDominatesEvenAcrossTheWholeMap() {
        PointAssessment ordered = point(1, PointStance.ENEMY, 400, 0, 0, true);
        PointAssessment convenient = point(2, PointStance.NEUTRAL, 8, 0, 0, false);
        assertEquals(1, ConquestTactics.pick(List.of(ordered, convenient), EVEN).orElseThrow().pointId(),
                "真人队长的指令应压过 AI 自己的几何判断，不论多远");
    }

    @Test
    void fulfilledOrderReleasesTheSquad() {
        // 指令目标已被本方控制且无人争夺 -> 指令完成，AI 恢复自主判断
        PointAssessment done = point(1, PointStance.MINE, 5, 2, 0, true);
        PointAssessment elsewhere = point(2, PointStance.NEUTRAL, 60, 0, 0, false);
        assertTrue(ConquestTactics.orderFulfilled(done));
        assertEquals(2, ConquestTactics.pick(List.of(done, elsewhere), EVEN).orElseThrow().pointId(),
                "已拿下且无人来抢的指令点不该把整队钉到对局结束");
    }

    @Test
    void orderStillBindsWhileContested() {
        PointAssessment underAttack = point(1, PointStance.MINE, 300, 1, 3, true);
        PointAssessment convenient = point(2, PointStance.NEUTRAL, 10, 0, 0, false);
        assertFalse(ConquestTactics.orderFulfilled(underAttack), "有敌人就不算完成");
        assertEquals(1, ConquestTactics.pick(List.of(underAttack, convenient), EVEN).orElseThrow().pointId());
    }

    @Test
    void multipleOrderedPointsAreComparedAmongThemselves() {
        PointAssessment nearOrdered = point(1, PointStance.ENEMY, 30, 0, 0, true);
        PointAssessment farOrdered = point(2, PointStance.ENEMY, 300, 0, 0, true);
        assertEquals(1, ConquestTactics.pick(List.of(farOrdered, nearOrdered), EVEN).orElseThrow().pointId(),
                "多个指令目标之间仍按几何价值取舍");
    }

    @Test
    void bleedingSideBoostsFlippingPointsNotDefending() {
        PointAssessment enemyHeld = point(1, PointStance.ENEMY, 30, 0, 0, false);
        double even = ConquestTactics.value(enemyHeld, EVEN);
        double losing = ConquestTactics.value(enemyHeld, LOSING);
        assertTrue(losing > even, "控点落后时翻点是唯一止血手段，应加权");

        PointAssessment ownSecure = point(2, PointStance.MINE, 30, 0, 0, false);
        assertEquals(ConquestTactics.value(ownSecure, EVEN), ConquestTactics.value(ownSecure, LOSING),
                1.0e-9D, "落后不该让 AI 更想蹲自己的空点");
    }

    @Test
    void bleedingIsDefinedByPointCountNotTickets() {
        assertTrue(new Situation(1, 2, 1.0D).bleeding());
        assertFalse(new Situation(2, 2, 0.1D).bleeding(), "票数少但控点持平时不流失");
        assertFalse(new Situation(3, 1, 0.1D).bleeding());
    }

    @Test
    void pickReturnsEmptyForEmptyList() {
        assertTrue(ConquestTactics.pick(List.of(), EVEN).isEmpty());
    }

    @Test
    void saturationOnlyAppliesToUncontestedOwnPoints() {
        assertTrue(ConquestTactics.saturated(point(1, PointStance.MINE, 5, 4, 0, false), 4));
        assertFalse(ConquestTactics.saturated(point(1, PointStance.MINE, 5, 4, 1, false), 4),
                "有敌人就永远不算饱和");
        assertFalse(ConquestTactics.saturated(point(1, PointStance.MINE, 5, 2, 0, false), 4),
                "人不够不算饱和");
        assertFalse(ConquestTactics.saturated(point(1, PointStance.NEUTRAL, 5, 9, 0, false), 4),
                "非己方点不谈饱和");
    }

    @Test
    void contestedFlagRequiresBothSidesPresent() {
        assertTrue(point(1, PointStance.NEUTRAL, 5, 1, 1, false).contested());
        assertFalse(point(1, PointStance.NEUTRAL, 5, 0, 3, false).contested());
        assertFalse(point(1, PointStance.NEUTRAL, 5, 3, 0, false).contested());
    }
}
