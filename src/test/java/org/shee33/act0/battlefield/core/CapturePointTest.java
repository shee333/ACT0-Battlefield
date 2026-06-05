package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapturePointTest {

    private static final ConquestRules RULES = ConquestRules.builder()
            .captureSeconds(10).maxCaptureBoost(4).build();

    @Test
    void neutralPointCapturedBySoloAfterCaptureSeconds() {
        CapturePoint p = new CapturePoint(0, "A");
        // 单人占中立点：每秒 0.1 进度，10 秒占满
        for (int i = 0; i < 9; i++) {
            p.tick(1, 0, RULES, 1.0);
            assertNull(p.owner(), "9 秒内不应占满");
        }
        CapturePoint.CaptureStatus st = p.tick(1, 0, RULES, 1.0);
        assertSame(Faction.ALPHA, p.owner());
        assertSame(CapturePoint.CaptureStatus.CAPTURED, st);
    }

    @Test
    void contestedFreezesProgress() {
        CapturePoint p = new CapturePoint(0, "A");
        p.tick(1, 0, RULES, 5.0); // level 0.5
        double before = p.level();
        CapturePoint.CaptureStatus st = p.tick(2, 2, RULES, 5.0);
        assertSame(CapturePoint.CaptureStatus.CONTESTED, st);
        assertEquals(before, p.level(), 1e-9);
    }

    @Test
    void enemyMustNeutralizeBeforeCapturing() {
        CapturePoint p = new CapturePoint(0, "A");
        // 甲方占满
        p.tick(4, 0, RULES, 10.0);
        assertSame(Faction.ALPHA, p.owner());
        // 乙方推进：先把进度从 +1 拉到 0（中和），期间归属经过 null
        p.tick(0, 1, RULES, 10.0); // 单人 10 秒 = 1.0 反向，从 +1 到 0
        assertNull(p.owner(), "应被中和为中立");
        // 继续推进到乙方占满
        p.tick(0, 1, RULES, 10.0);
        assertSame(Faction.BRAVO, p.owner());
    }

    @Test
    void idleKeepsProgress() {
        CapturePoint p = new CapturePoint(0, "A");
        p.tick(1, 0, RULES, 3.0);
        double lvl = p.level();
        assertSame(CapturePoint.CaptureStatus.IDLE, p.tick(0, 0, RULES, 5.0));
        assertEquals(lvl, p.level(), 1e-9);
    }

    @Test
    void morePlayersCaptureFasterButCapped() {
        CapturePoint solo = new CapturePoint(0, "A");
        solo.tick(1, 0, RULES, 1.0);
        CapturePoint many = new CapturePoint(1, "B");
        many.tick(8, 0, RULES, 1.0); // 8 人但上限 4
        assertTrue(many.level() > solo.level());
        assertEquals(0.4, many.level(), 1e-9); // 4 人 * 0.1
    }
}
