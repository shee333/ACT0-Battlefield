package org.shee33.act0.battlefield.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployModeLabelTest {

    private static final float EPS = 0.02f;

    @Test
    void beforeStartIsHidden() {
        assertEquals(0f, DeployModeLabel.slideInProgress(1000L, 1000L, 0L, 280L), EPS);
    }

    @Test
    void midSlideIsPartial() {
        float t = DeployModeLabel.slideInProgress(1140L, 1000L, 0L, 280L);
        assertTrue(t > 0f && t < 1f, "入场中段应处于 0~1 之间，实际=" + t);
    }

    @Test
    void completesAtDuration() {
        assertEquals(1f, DeployModeLabel.slideInProgress(1280L, 1000L, 0L, 280L), EPS);
        assertEquals(1f, DeployModeLabel.slideInProgress(2000L, 1000L, 0L, 280L), EPS);
    }

    @Test
    void delayedLineStartsLaterThanFirstLine() {
        long now = 1150L;
        long openedAt = 1000L;
        float modeT = DeployModeLabel.slideInProgress(now, openedAt, 0L, 280L);
        float mapT = DeployModeLabel.slideInProgress(now, openedAt, 80L, 280L);
        assertTrue(modeT > mapT, "模式名先入，同一时刻进度应领先于错峰跟进的地图名");
    }

    @Test
    void monotonicDuringSlide() {
        float a1 = DeployModeLabel.slideInProgress(1050L, 1000L, 0L, 280L);
        float a2 = DeployModeLabel.slideInProgress(1150L, 1000L, 0L, 280L);
        float a3 = DeployModeLabel.slideInProgress(1280L, 1000L, 0L, 280L);
        assertTrue(a1 < a2 && a2 < a3, "入场阶段进度应单调递增");
    }
}
