package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlayerMatchState} 各状态机谓词的穷举测试：每个方法在每个状态下都给出预期断言。
 */
class PlayerMatchStateTest {

    @Test
    void canCaptureOnlyTrueForActive() {
        assertTrue(PlayerMatchState.ACTIVE.canCapture());
        assertFalse(PlayerMatchState.DOWNED.canCapture());
        assertFalse(PlayerMatchState.DEPLOYING.canCapture());
        assertFalse(PlayerMatchState.OFFLINE.canCapture());
        assertFalse(PlayerMatchState.LEFT.canCapture());
    }

    @Test
    void canBeSpawnTargetOnlyTrueForActive() {
        assertTrue(PlayerMatchState.ACTIVE.canBeSpawnTarget());
        assertFalse(PlayerMatchState.DOWNED.canBeSpawnTarget());
        assertFalse(PlayerMatchState.DEPLOYING.canBeSpawnTarget());
        assertFalse(PlayerMatchState.OFFLINE.canBeSpawnTarget());
        assertFalse(PlayerMatchState.LEFT.canBeSpawnTarget());
    }

    @Test
    void isCombatReadyOnlyTrueForActive() {
        assertTrue(PlayerMatchState.ACTIVE.isCombatReady());
        assertFalse(PlayerMatchState.DOWNED.isCombatReady());
        assertFalse(PlayerMatchState.DEPLOYING.isCombatReady());
        assertFalse(PlayerMatchState.OFFLINE.isCombatReady());
        assertFalse(PlayerMatchState.LEFT.isCombatReady());
    }

    @Test
    void exactlyOneStateIsActive() {
        int activeCount = 0;
        for (PlayerMatchState s : PlayerMatchState.values()) {
            if (s.canCapture() && s.canBeSpawnTarget() && s.isCombatReady()) {
                activeCount++;
            }
        }
        assertTrue(activeCount == 1, "有且仅有一个状态满足全部战斗谓词");
    }
}