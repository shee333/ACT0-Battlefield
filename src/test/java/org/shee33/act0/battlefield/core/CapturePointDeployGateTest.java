package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住"正在翻转的据点不能当重生点"这条闸门。
 *
 * <p>放行一个正在被夺的据点，等于让防守方靠源源不断的空投增援守点而不是靠交火，据点争夺就废了；
 * 反过来误拦一个稳态据点，玩家会莫名其妙找不到落点。两个方向都必须钉住。
 */
class CapturePointDeployGateTest {

    private static final ConquestRules RULES = ConquestRules.builder()
            .captureSeconds(10).maxCaptureBoost(4).build();

    private static CapturePoint ownedByAlpha() {
        CapturePoint p = new CapturePoint(0, "A");
        for (int i = 0; i < 12; i++) {
            p.tick(1, 0, RULES, 1.0);
        }
        assertEquals(Faction.ALPHA, p.owner());
        return p;
    }

    @Test
    void freshPointStartsIdleAndUnblocked() {
        CapturePoint p = new CapturePoint(0, "A");
        assertEquals(CapturePoint.CaptureStatus.IDLE, p.lastStatus());
        assertFalse(p.deployBlocked());
    }

    @Test
    void contestedBlocks() {
        CapturePoint p = ownedByAlpha();
        p.tick(1, 1, RULES, 1.0);
        assertEquals(CapturePoint.CaptureStatus.CONTESTED, p.lastStatus());
        assertTrue(p.deployBlocked());
    }

    @Test
    void enemyCapturingBlocks() {
        CapturePoint p = ownedByAlpha();
        p.tick(0, 1, RULES, 1.0);
        assertEquals(CapturePoint.CaptureStatus.CAPTURING, p.lastStatus());
        assertTrue(p.deployBlocked());
    }

    @Test
    void neutralizedBlocks() {
        CapturePoint p = ownedByAlpha();
        while (p.owner() != null) {
            p.tick(0, 1, RULES, 1.0);
        }
        assertEquals(CapturePoint.CaptureStatus.NEUTRALIZED, p.lastStatus());
        assertTrue(p.deployBlocked());
    }

    @Test
    void secureDoesNotBlock() {
        CapturePoint p = ownedByAlpha();
        p.tick(1, 0, RULES, 1.0);
        assertEquals(CapturePoint.CaptureStatus.SECURE, p.lastStatus());
        assertFalse(p.deployBlocked());
    }

    @Test
    void idleDoesNotBlock() {
        CapturePoint p = ownedByAlpha();
        p.tick(0, 0, RULES, 1.0);
        assertEquals(CapturePoint.CaptureStatus.IDLE, p.lastStatus());
        assertFalse(p.deployBlocked());
    }

    /** DEFENDING 是敌人已经离开、控制方把进度推回，区内无敌人，刻意放行。 */
    @Test
    void defendingDoesNotBlock() {
        CapturePoint p = ownedByAlpha();
        p.tick(0, 1, RULES, 2.0);
        p.tick(1, 0, RULES, 1.0);
        assertEquals(CapturePoint.CaptureStatus.DEFENDING, p.lastStatus());
        assertFalse(p.deployBlocked());
    }

    @Test
    void capturedDoesNotBlock() {
        CapturePoint p = new CapturePoint(0, "A");
        while (p.owner() == null) {
            p.tick(1, 0, RULES, 1.0);
        }
        assertEquals(CapturePoint.CaptureStatus.CAPTURED, p.lastStatus());
        assertFalse(p.deployBlocked());
    }
}
