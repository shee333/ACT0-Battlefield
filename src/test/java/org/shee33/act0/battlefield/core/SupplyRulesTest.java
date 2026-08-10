package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.SupplyRules.HealPhase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyRulesTest {

    @Test
    void inRangeUsesFlatCircleHorizontallyNotSphere() {
        double r = SupplyRules.RADIUS;
        assertTrue(SupplyRules.inRange(0, 0, 0, r), "正中心必须在范围内");
        assertTrue(SupplyRules.inRange(3.0, 0, 0, r), "水平恰好在半径上算范围内");
        assertFalse(SupplyRules.inRange(3.01, 0, 0, r), "水平超出半径算范围外");
        assertTrue(SupplyRules.inRange(2.0, 0, 2.0, r), "水平对角线 2.83 < 3 仍在范围内");
        assertFalse(SupplyRules.inRange(2.2, 0, 2.2, r), "水平对角线 3.11 > 3 应出范围");
    }

    /**
     * 判别性用例：水平与垂直<b>同时</b>接近半径的斜角位置，柱形判定收、球形判定不收。
     *
     * <p>不加这条，所有垂直用例的 dx/dz 都是 0，而在正上方球形与柱形恰好给出相同结果——
     * 整组测试会对"圆柱还是球"这个核心区分完全失明（首次写完时正是如此，靠变异测试才发现）。
     */
    @Test
    void inRangeAcceptsDiagonalThatASphereWouldReject() {
        assertTrue(SupplyRules.inRange(2.9, 2.9, 0, SupplyRules.RADIUS),
                "水平 2.9(<3) 且垂直 2.9(<3) 必须在范围内——球形判定会错误地排除它");
        assertTrue(SupplyRules.inRange(2.0, 2.5, 2.0, SupplyRules.RADIUS),
                "水平 2.83(<3) 且垂直 2.5(<3) 同样在范围内");
    }

    /** 站在箱子正上方够高就该出范围——否则楼上的人会被楼下的箱子隔着地板补给。 */
    @Test
    void inRangeRejectsFarVerticalEvenWhenHorizontallyCentered() {
        double r = SupplyRules.RADIUS;
        assertTrue(SupplyRules.inRange(0, 3.0, 0, r), "垂直恰好在半径上仍算范围内");
        assertFalse(SupplyRules.inRange(0, 3.01, 0, r), "垂直超出半径应出范围");
        assertFalse(SupplyRules.inRange(0, -4.0, 0, r), "下方超出同样出范围");
    }

    @Test
    void expiryAndRemainingAgreeAtTheBoundary() {
        long deploy = 1000L;
        int life = SupplyRules.LIFETIME_TICKS;
        assertFalse(SupplyRules.expired(deploy, deploy, life));
        assertFalse(SupplyRules.expired(deploy + life - 1, deploy, life));
        assertTrue(SupplyRules.expired(deploy + life, deploy, life), "到达存活期即到期");

        assertEquals(life, SupplyRules.remainingTicks(deploy, deploy, life));
        assertEquals(1, SupplyRules.remainingTicks(deploy + life - 1, deploy, life));
        assertEquals(0, SupplyRules.remainingTicks(deploy + life, deploy, life));
        assertEquals(0, SupplyRules.remainingTicks(deploy + life + 999, deploy, life),
                "过期后剩余时间不得为负");
    }

    @Test
    void refillAddsGrantAndRespectsLoadoutCap() {
        assertEquals(100, SupplyRules.refilledAmmo(40, 200, 60), "未触顶时按增量补给");
        assertEquals(90, SupplyRules.refilledAmmo(30, 90, 60), "补给不得超过配装上限");
        assertEquals(60, SupplyRules.refilledAmmo(0, 0, 60), "无上限记录时按纯增量");
        assertEquals(60, SupplyRules.refilledAmmo(-5, 0, 60), "负数备弹按 0 处理");
    }

    /** 已超上限（例如管理员手动发弹）时不能把玩家的弹药倒扣回上限。 */
    @Test
    void refillNeverRemovesAmmoFromAnOverCappedGun() {
        assertEquals(500, SupplyRules.refilledAmmo(500, 90, 60));
    }

    @Test
    void healPhaseWalksDelayThenHealThenDone() {
        int d = SupplyRules.MEDIC_DELAY_TICKS;
        int h = SupplyRules.MEDIC_HEAL_TICKS;
        assertEquals(HealPhase.DELAY, SupplyRules.healPhase(0, d, h));
        assertEquals(HealPhase.DELAY, SupplyRules.healPhase(d - 1, d, h));
        assertEquals(HealPhase.HEALING, SupplyRules.healPhase(d, d, h), "延迟结束当 tick 即开始回血");
        assertEquals(HealPhase.HEALING, SupplyRules.healPhase(d + h - 1, d, h));
        assertEquals(HealPhase.DONE, SupplyRules.healPhase(d + h, d, h), "延迟+回血时长到达即完成");
    }

    @Test
    void healProgressIsZeroThroughDelayAndReachesOneExactlyAtEnd() {
        int d = SupplyRules.MEDIC_DELAY_TICKS;
        int h = SupplyRules.MEDIC_HEAL_TICKS;
        assertEquals(0.0D, SupplyRules.healProgress(0, d, h), 1e-9);
        assertEquals(0.0D, SupplyRules.healProgress(d - 1, d, h), 1e-9, "延迟期内不得回血");
        assertEquals(0.0D, SupplyRules.healProgress(d, d, h), 1e-9);
        assertEquals(0.5D, SupplyRules.healProgress(d + h / 2, d, h), 1e-9);
        assertEquals(1.0D, SupplyRules.healProgress(d + h, d, h), 1e-9);
        assertEquals(1.0D, SupplyRules.healProgress(d + h + 100, d, h), 1e-9, "超时后钳在 1");
    }

    @Test
    void healthAtInterpolatesFromStartHealthToFull() {
        assertEquals(6.0f, SupplyRules.healthAt(6.0f, 20.0f, 0.0D), 1e-4);
        assertEquals(13.0f, SupplyRules.healthAt(6.0f, 20.0f, 0.5D), 1e-4);
        assertEquals(20.0f, SupplyRules.healthAt(6.0f, 20.0f, 1.0D), 1e-4, "结束时必须精确回满");
    }

    /** 3 秒回满是硬要求：走完整条时间线后血量必须精确等于上限，不能差一点点。 */
    @Test
    void fullHealTimelineEndsExactlyAtMaxHealth() {
        int d = SupplyRules.MEDIC_DELAY_TICKS;
        int h = SupplyRules.MEDIC_HEAL_TICKS;
        float from = 1.0f;
        float max = 20.0f;
        float last = from;
        for (long t = 0; t <= d + h; t++) {
            float hp = SupplyRules.healthAt(from, max, SupplyRules.healProgress(t, d, h));
            assertTrue(hp >= last - 1e-4, "血量在治疗过程中不得回落");
            last = hp;
        }
        assertEquals(max, last, 1e-4);
    }

    @Test
    void damageDetectionIgnoresFloatingPointNoiseButCatchesRealHits() {
        assertFalse(SupplyRules.damagedDuringHeal(12.999f, 13.0f, 0.05f), "浮点误差不算受伤");
        assertFalse(SupplyRules.damagedDuringHeal(13.5f, 13.0f, 0.05f), "高于预期(吃了别的治疗)不算受伤");
        assertTrue(SupplyRules.damagedDuringHeal(9.0f, 13.0f, 0.05f), "明显掉血必须判定为受伤");
    }

    @Test
    void retriggerCooldownSpansExactlyTenSeconds() {
        int cd = SupplyRules.MEDIC_RETRIGGER_TICKS;
        assertTrue(SupplyRules.onRetriggerCooldown(500, 500, cd), "触发当 tick 即进入冷却");
        assertTrue(SupplyRules.onRetriggerCooldown(500 + cd - 1, 500, cd));
        assertFalse(SupplyRules.onRetriggerCooldown(500 + cd, 500, cd), "满 10 秒即可再次触发");
    }

    @Test
    void syringeRevivesThreeTimesFaster() {
        assertEquals(60, SupplyRules.reviveDuration(60, false));
        assertEquals(20, SupplyRules.reviveDuration(60, true));
    }

    /** 时长必须恒 >= 1 tick，否则 3 倍速会把极短的配置直接除成 0，导致救援瞬间完成。 */
    @Test
    void reviveDurationNeverCollapsesToZero() {
        assertEquals(1, SupplyRules.reviveDuration(2, true));
        assertEquals(1, SupplyRules.reviveDuration(1, true));
        assertEquals(1, SupplyRules.reviveDuration(0, false));
    }
}
