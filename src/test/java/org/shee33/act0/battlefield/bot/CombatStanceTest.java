package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交火姿态（MC-free）单元测试：三档距离判定的边界，与侧移翻向的节奏及撞墙抢占。
 */
class CombatStanceTest {

    private static CombatStance stance() {
        return new CombatStance(16.0D, 5.0D, 30);
    }

    // ---------------- 距离分档 ----------------

    @Test
    void advancesWhenBeyondEngageRange() {
        assertEquals(CombatStance.Mode.ADVANCE, stance().modeFor(16.01D));
        assertEquals(CombatStance.Mode.ADVANCE, stance().modeFor(60.0D));
    }

    @Test
    void holdsInsideEngageBand() {
        assertEquals(CombatStance.Mode.HOLD, stance().modeFor(16.0D));
        assertEquals(CombatStance.Mode.HOLD, stance().modeFor(10.0D));
        assertEquals(CombatStance.Mode.HOLD, stance().modeFor(5.0D));
    }

    @Test
    void retreatsWhenTooClose() {
        assertEquals(CombatStance.Mode.RETREAT, stance().modeFor(4.99D));
        assertEquals(CombatStance.Mode.RETREAT, stance().modeFor(0.0D));
    }

    @Test
    void holdBandIsWideEnoughToAvoidThrashing() {
        // 保持带必须明显宽于 bot 单 tick 的位移（约 0.22 格），否则会在两档之间反复抽搐
        CombatStance s = stance();
        assertTrue(s.modeFor(5.5D) == CombatStance.Mode.HOLD && s.modeFor(15.5D) == CombatStance.Mode.HOLD,
                "保持带过窄");
    }

    // ---------------- 侧移翻向 ----------------

    @Test
    void strafeSignStartsNonZero() {
        assertNotEquals(0, stance().strafeSign());
    }

    @Test
    void strafeFlipsOnTimer() {
        CombatStance s = stance();
        int initial = s.strafeSign();
        for (int i = 0; i < 29; i++) {
            s.tick(false);
        }
        assertEquals(initial, s.strafeSign(), "未到间隔就翻向");
        s.tick(false);
        assertEquals(-initial, s.strafeSign(), "到达间隔应翻向");
        assertEquals(0, s.ticksSinceFlip(), "翻向后应重置计时");
    }

    @Test
    void strafeFlipsImmediatelyWhenBlocked() {
        CombatStance s = stance();
        int initial = s.strafeSign();
        s.tick(true);
        assertEquals(-initial, s.strafeSign(), "撞墙必须立即翻向，否则 bot 贴墙卡死");
    }

    @Test
    void strafeAlternatesAcrossManyFlips() {
        CombatStance s = stance();
        int initial = s.strafeSign();
        s.flip();
        assertEquals(-initial, s.strafeSign());
        s.flip();
        assertEquals(initial, s.strafeSign());
    }

    @Test
    void blockedFlipDoesNotAccumulateStaleTicks() {
        CombatStance s = stance();
        for (int i = 0; i < 10; i++) {
            s.tick(false);
        }
        s.tick(true);
        assertEquals(0, s.ticksSinceFlip());
    }
}
