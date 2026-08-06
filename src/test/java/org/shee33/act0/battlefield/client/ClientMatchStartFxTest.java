package org.shee33.act0.battlefield.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientMatchStartFxTest {

    @Test
    void negativeElapsedReturnsZero() {
        assertEquals(0, ClientMatchStartFx.computeAlpha(-1L));
    }

    @Test
    void fadeInStartIsBlack() {
        assertEquals(0, ClientMatchStartFx.computeAlpha(0L));
    }

    @Test
    void fadeInMidpointIsPartial() {
        int alpha = ClientMatchStartFx.computeAlpha(175L);
        assertTrue(alpha > 0 && alpha < 255, "淡入中段应处于 0~255 之间，实际=" + alpha);
    }

    @Test
    void holdPhaseIsFullyBlack() {
        assertEquals(255, ClientMatchStartFx.computeAlpha(350L));
        assertEquals(255, ClientMatchStartFx.computeAlpha(500L));
        assertEquals(255, ClientMatchStartFx.computeAlpha(649L));
    }

    @Test
    void fadeOutStartIsFullyBlack() {
        assertEquals(255, ClientMatchStartFx.computeAlpha(650L));
    }

    @Test
    void fadeOutMidpointIsPartial() {
        int alpha = ClientMatchStartFx.computeAlpha(825L);
        assertTrue(alpha > 0 && alpha < 255, "淡出中段应处于 0~255 之间，实际=" + alpha);
    }

    @Test
    void fadeOutEndReturnsZero() {
        assertEquals(0, ClientMatchStartFx.computeAlpha(1000L));
        assertEquals(0, ClientMatchStartFx.computeAlpha(2000L));
    }

    @Test
    void alphaMonotonicDuringFadeIn() {
        int a1 = ClientMatchStartFx.computeAlpha(50L);
        int a2 = ClientMatchStartFx.computeAlpha(150L);
        int a3 = ClientMatchStartFx.computeAlpha(300L);
        assertTrue(a1 < a2 && a2 < a3, "淡入阶段 alpha 应单调递增");
    }

    @Test
    void alphaMonotonicDuringFadeOut() {
        int a1 = ClientMatchStartFx.computeAlpha(700L);
        int a2 = ClientMatchStartFx.computeAlpha(850L);
        int a3 = ClientMatchStartFx.computeAlpha(950L);
        assertTrue(a1 > a2 && a2 > a3, "淡出阶段 alpha 应单调递减");
    }
}
