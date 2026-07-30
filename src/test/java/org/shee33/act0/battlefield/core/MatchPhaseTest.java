package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MatchPhase} 各阶段谓词的穷举测试：每个方法在每个阶段下都给出预期断言。
 */
class MatchPhaseTest {

    @Test
    void isPreMatchOnlyTrueForCountdown() {
        assertTrue(MatchPhase.COUNTDOWN.isPreMatch());
        assertFalse(MatchPhase.LIVE.isPreMatch());
        assertFalse(MatchPhase.POST_MATCH.isPreMatch());
        assertFalse(MatchPhase.ENDED.isPreMatch());
    }

    @Test
    void isLiveOnlyTrueForLive() {
        assertFalse(MatchPhase.COUNTDOWN.isLive());
        assertTrue(MatchPhase.LIVE.isLive());
        assertFalse(MatchPhase.POST_MATCH.isLive());
        assertFalse(MatchPhase.ENDED.isLive());
    }

    @Test
    void isFinishedTrueForPostMatchAndEnded() {
        assertFalse(MatchPhase.COUNTDOWN.isFinished());
        assertFalse(MatchPhase.LIVE.isFinished());
        assertTrue(MatchPhase.POST_MATCH.isFinished());
        assertTrue(MatchPhase.ENDED.isFinished());
    }

    @Test
    void phasesAreMutuallyExclusiveForPreMatchAndLive() {
        for (MatchPhase p : MatchPhase.values()) {
            assertFalse(p.isPreMatch() && p.isLive(),
                    "同一阶段不应同时为开赛前与比赛中");
        }
    }
}