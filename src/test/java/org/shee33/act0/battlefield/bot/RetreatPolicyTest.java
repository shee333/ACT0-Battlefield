package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetreatPolicyTest {

    private static RetreatPolicy.State run(RetreatPolicy policy, float health, int ticks) {
        RetreatPolicy.State last = policy.state();
        for (int i = 0; i < ticks; i++) {
            last = policy.tick(health);
        }
        return last;
    }

    @Test
    void startsFighting() {
        RetreatPolicy policy = new RetreatPolicy();
        assertEquals(RetreatPolicy.State.FIGHT, policy.state());
        assertFalse(policy.shouldBreakOff());
    }

    @Test
    void breaksOffAtThreshold() {
        RetreatPolicy policy = new RetreatPolicy();
        assertEquals(RetreatPolicy.State.FIGHT, policy.tick(0.5F));
        assertEquals(RetreatPolicy.State.BREAK_OFF,
                policy.tick(RetreatPolicy.DEFAULT_BREAK_OFF_HEALTH));
        assertTrue(policy.shouldBreakOff());
    }

    /**
     * 迟滞是本类存在的主要理由：血量在阈值附近抖动时若前进一步后退一步，玩家看到的是原地抽搐。
     * 这条检查刚好恢复到脱离线之上、但远未到再交火线的情形。
     */
    @Test
    void doesNotReengageJustAboveBreakOffLine() {
        RetreatPolicy policy = new RetreatPolicy();
        policy.tick(0.10F);
        assertTrue(policy.shouldBreakOff());
        run(policy, RetreatPolicy.DEFAULT_BREAK_OFF_HEALTH + 0.05F, 200);
        assertTrue(policy.shouldBreakOff(), "刚过脱离线就回去打会在掩体口反复进出");
    }

    @Test
    void reengagesOnlyAboveReengageLineAndAfterMinimumDuration() {
        RetreatPolicy policy = new RetreatPolicy();
        policy.tick(0.10F);
        assertTrue(policy.shouldBreakOff());

        // 血量已够，但最短脱离时长未满
        run(policy, 1.0F, RetreatPolicy.DEFAULT_MIN_BREAK_OFF_TICKS - 2);
        assertTrue(policy.shouldBreakOff(), "最短脱离时长未满不得回身");

        run(policy, 1.0F, 3);
        assertFalse(policy.shouldBreakOff(), "血量与时长都满足后应回归交火");
    }

    @Test
    void minimumDurationAloneDoesNotReengage() {
        RetreatPolicy policy = new RetreatPolicy();
        policy.tick(0.10F);
        run(policy, 0.10F, RetreatPolicy.DEFAULT_MIN_BREAK_OFF_TICKS * 5);
        assertTrue(policy.shouldBreakOff(), "血量没回来就不该回去");
    }

    @Test
    void ticksInStateResetsOnTransition() {
        RetreatPolicy policy = new RetreatPolicy();
        run(policy, 1.0F, 50);
        assertEquals(50, policy.ticksInState());
        policy.tick(0.10F);
        assertEquals(0, policy.ticksInState(), "状态切换应重置计时");
    }

    @Test
    void resetReturnsToFight() {
        RetreatPolicy policy = new RetreatPolicy();
        policy.tick(0.05F);
        assertTrue(policy.shouldBreakOff());
        policy.reset();
        assertEquals(RetreatPolicy.State.FIGHT, policy.state());
        assertEquals(0, policy.ticksInState());
    }

    @Test
    void modeSpecificThresholdIsHonoured() {
        RetreatPolicy stubborn = new RetreatPolicy(0.15F, 0.70F, 60);
        assertEquals(RetreatPolicy.State.FIGHT, stubborn.tick(0.20F),
                "阈值更低的档位应在 20% 血时仍继续打");
        assertEquals(RetreatPolicy.State.BREAK_OFF, stubborn.tick(0.15F));
    }

    @Test
    void rejectsNonHystereticThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new RetreatPolicy(0.5F, 0.5F, 60));
        assertThrows(IllegalArgumentException.class, () -> new RetreatPolicy(0.7F, 0.3F, 60));
    }

    @Test
    void defaultsLeaveRoomForAFullRecoveryCycle() {
        assertTrue(RetreatPolicy.DEFAULT_REENGAGE_HEALTH - RetreatPolicy.DEFAULT_BREAK_OFF_HEALTH >= 0.3F,
                "两条线拉得太近会让撤退—回血—再战退化成掩体口反复进出");
    }
}
