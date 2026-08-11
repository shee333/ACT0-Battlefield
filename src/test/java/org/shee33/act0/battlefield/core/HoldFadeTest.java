package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoldFadeTest {

    private static final float EPS = 1e-5f;

    @Test
    void easedWalksFromStartToTargetAcrossTheDuration() {
        assertEquals(0f, HoldFade.eased(0f, 1f, 0, 180), EPS);
        assertEquals(0.875f, HoldFade.eased(0f, 1f, 90, 180), EPS, "半程走完 easeOutCubic(0.5)=0.875");
        assertEquals(1f, HoldFade.eased(0f, 1f, 180, 180), EPS);
        assertEquals(1f, HoldFade.eased(0f, 1f, 9999, 180), EPS, "超时必须钳在目标值");
    }

    /** 反向（松开复原）与正向对称。 */
    @Test
    void easedWorksInReverse() {
        assertEquals(1f, HoldFade.eased(1f, 0f, 0, 180), EPS);
        assertEquals(0.125f, HoldFade.eased(1f, 0f, 90, 180), EPS);
        assertEquals(0f, HoldFade.eased(1f, 0f, 180, 180), EPS);
    }

    /**
     * 打断不回跳：渐出播到 0.5 时松手，必须<b>从 0.5 往回走</b>，而不是先跳到 1 再走。
     * 这正是把"当前值"作为新起点的意义。
     */
    @Test
    void interruptingMidFadeResumesFromTheCurrentValue() {
        float mid = HoldFade.eased(0f, 1f, 90, 180);
        assertEquals(0.875f, mid, EPS);
        assertEquals(mid, HoldFade.eased(mid, 0f, 0, 180), EPS, "松手瞬间不得跳变");
        assertTrue(HoldFade.eased(mid, 0f, 90, 180) < mid, "必须从当前值继续往回走");
        assertEquals(0f, HoldFade.eased(mid, 0f, 180, 180), EPS);
    }

    @Test
    void easedIsMonotonicAndStaysInRange() {
        float prev = -1f;
        for (int ms = 0; ms <= 200; ms += 5) {
            float v = HoldFade.eased(0f, 1f, ms, 180);
            assertTrue(v >= prev, "推进过程不得回退");
            assertTrue(v >= 0f && v <= 1f, "必须留在 [0,1]");
            prev = v;
        }
    }

    @Test
    void zeroDurationSnapsImmediately() {
        assertEquals(1f, HoldFade.eased(0f, 1f, 0, 0), EPS);
    }

    @Test
    void easeOutCubicIsClampedAndEndsFlat() {
        assertEquals(0f, HoldFade.easeOutCubic(0f), EPS);
        assertEquals(1f, HoldFade.easeOutCubic(1f), EPS);
        assertEquals(0f, HoldFade.easeOutCubic(-3f), EPS, "越界输入必须钳住");
        assertEquals(1f, HoldFade.easeOutCubic(3f), EPS);
        assertTrue(HoldFade.easeOutCubic(0.5f) > 0.5f, "ease-out 前段应快于线性");
    }
}
