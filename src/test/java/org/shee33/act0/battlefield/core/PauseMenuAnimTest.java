package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PauseMenuAnimTest {

    private static final float EPS = 1e-5f;

    @Test
    void progressStaysAtZeroUntilTheDelayElapses() {
        assertEquals(0f, PauseMenuAnim.progress(0, 140, 260), EPS);
        assertEquals(0f, PauseMenuAnim.progress(140, 140, 260), EPS, "延迟刚满时仍是 0，不得闪现");
        assertEquals(0.5f, PauseMenuAnim.progress(270, 140, 260), EPS);
        assertEquals(1f, PauseMenuAnim.progress(400, 140, 260), EPS);
        assertEquals(1f, PauseMenuAnim.progress(9999, 140, 260), EPS);
    }

    /** 级联的意义就是各项延迟不同：第 i 项必须比第 i-1 项晚 55ms 起步。 */
    @Test
    void itemsCascadeWithFiftyFiveMillisecondStagger() {
        for (int i = 0; i < 5; i++) {
            long startAt = PauseMenuAnim.ITEM_DELAY_MS + i * PauseMenuAnim.ITEM_STAGGER_MS;
            assertEquals(0f, PauseMenuAnim.itemOpenProgress(startAt, i), EPS,
                    "第 " + i + " 项在自己的延迟点上应仍未开始");
            assertTrue(PauseMenuAnim.itemOpenProgress(startAt + 10, i) > 0f,
                    "第 " + i + " 项过了延迟点应已开始");
        }
        long t = PauseMenuAnim.ITEM_DELAY_MS + 30;
        assertTrue(PauseMenuAnim.itemOpenProgress(t, 0) > 0f, "首项已在推进");
        assertEquals(0f, PauseMenuAnim.itemOpenProgress(t, 1), EPS, "次项此刻还没开始");
    }

    @Test
    void allItemsFinishByTheEndOfTheOpeningSequence() {
        long total = PauseMenuAnim.ITEM_DELAY_MS + 4 * PauseMenuAnim.ITEM_STAGGER_MS + PauseMenuAnim.ITEM_IN_MS;
        for (int i = 0; i < 5; i++) {
            assertEquals(1f, PauseMenuAnim.itemOpenProgress(total, i), EPS);
        }
    }

    @Test
    void closeSequenceCoversBothItemsAndOverlay() {
        int total = PauseMenuAnim.closeTotalMs(5);
        assertTrue(total >= PauseMenuAnim.CLOSE_OVERLAY_DELAY_MS + PauseMenuAnim.CLOSE_OVERLAY_MS,
                "关闭总时长不能短于遮罩淡出");
        for (int i = 0; i < 5; i++) {
            assertEquals(1f, PauseMenuAnim.itemCloseProgress(total, i), EPS, "第 " + i + " 项必须已淡出完毕");
        }
    }

    /** 隐藏菜单不能早于遮罩淡完，否则会看到世界"啪"一下露出来。 */
    @Test
    void closeTotalNeverTruncatesTheOverlayFade() {
        assertEquals(340, PauseMenuAnim.closeTotalMs(1));
        assertTrue(PauseMenuAnim.closeTotalMs(20) > 340, "项数很多时应由项级联决定总时长");
    }

    @Test
    void indicatorStretchPeaksMidSlideAndReturnsToOneAtBothEnds() {
        assertEquals(1f, PauseMenuAnim.indicatorStretch(0f), EPS, "起点必须无拉伸");
        assertEquals(1f, PauseMenuAnim.indicatorStretch(1f), EPS, "终点必须回到无拉伸");
        assertEquals(1f + PauseMenuAnim.INDICATOR_STRETCH, PauseMenuAnim.indicatorStretch(0.5f), EPS);
        assertTrue(PauseMenuAnim.indicatorStretch(0.25f) > 1f);
    }

    @Test
    void holdFillReachesOneExactlyAtEightHundredMillis() {
        assertEquals(0f, PauseMenuAnim.holdFill(0, -1f, 0), EPS);
        assertEquals(0.5f, PauseMenuAnim.holdFill(400, -1f, 0), EPS);
        assertEquals(1f, PauseMenuAnim.holdFill(800, -1f, 0), EPS);
        assertFalse(PauseMenuAnim.holdCompleted(799));
        assertTrue(PauseMenuAnim.holdCompleted(800));
    }

    /**
     * 回退必须从<b>松手当刻的宽度</b>开始，而不是从满条开始。
     *
     * <p>按到 10% 就松手却看到满条缩回去，会让玩家误以为自己差点触发了退出战局。
     */
    @Test
    void holdReleaseRewindsFromTheWidthAtReleaseNotFromFull() {
        float atRelease = PauseMenuAnim.holdFill(80, -1f, 0);
        assertEquals(0.1f, atRelease, EPS);
        assertEquals(atRelease, PauseMenuAnim.holdFill(80, atRelease, 0), EPS, "松手瞬间不得跳变");
        assertTrue(PauseMenuAnim.holdFill(80, atRelease, 90) < atRelease, "应当在回退");
        assertEquals(0f, PauseMenuAnim.holdFill(80, atRelease, PauseMenuAnim.HOLD_RELEASE_MS), EPS);
    }

    @Test
    void holdFillNeverLeavesTheUnitRange() {
        for (long ms = 0; ms <= 1200; ms += 37) {
            float v = PauseMenuAnim.holdFill(ms, -1f, 0);
            assertTrue(v >= 0f && v <= 1f, "填充比例越界: " + v);
        }
        for (long ms = 0; ms <= 400; ms += 13) {
            float v = PauseMenuAnim.holdFill(500, 0.62f, ms);
            assertTrue(v >= 0f && v <= 0.62f, "回退比例越界: " + v);
        }
    }

    @Test
    void toastFadesInHoldsThenFadesOut() {
        assertEquals(0f, PauseMenuAnim.toastAlpha(0), EPS);
        assertEquals(1f, PauseMenuAnim.toastAlpha(PauseMenuAnim.TOAST_IN_MS), EPS);
        assertEquals(1f, PauseMenuAnim.toastAlpha(PauseMenuAnim.TOAST_IN_MS + 700), EPS, "停留期恒为 1");
        int end = PauseMenuAnim.TOAST_IN_MS + PauseMenuAnim.TOAST_HOLD_MS + PauseMenuAnim.TOAST_OUT_MS;
        assertEquals(0f, PauseMenuAnim.toastAlpha(end), EPS);
        assertEquals(0f, PauseMenuAnim.toastAlpha(end + 5000), EPS);
        assertTrue(PauseMenuAnim.toastAlpha(end - 100) > 0f, "淡出中途仍应可见");
    }

    /** 左重右轻是"游戏没停"的载体：右缘必须明显比左缘透，否则战场看不见。 */
    @Test
    void overlayIsHeavyOnTheLeftAndLightOnTheRight() {
        assertEquals(0.92f, PauseMenuAnim.overlayAlphaAt(0f), EPS);
        assertEquals(0.82f, PauseMenuAnim.overlayAlphaAt(0.45f), EPS);
        assertEquals(0.55f, PauseMenuAnim.overlayAlphaAt(1f), EPS);
        assertTrue(PauseMenuAnim.overlayAlphaAt(1f) < PauseMenuAnim.overlayAlphaAt(0f) - 0.3f,
                "右缘必须显著更透，战场要保持可见");
    }

    @Test
    void overlayAlphaIsMonotonicallyDecreasing() {
        float prev = 1f;
        for (float x = 0f; x <= 1.0001f; x += 0.02f) {
            float a = PauseMenuAnim.overlayAlphaAt(x);
            assertTrue(a <= prev + EPS, "遮罩不透明度不得回升: x=" + x);
            prev = a;
        }
    }

    @Test
    void easingsAreClampedAndHitTheirEndpoints() {
        assertEquals(0f, PauseMenuAnim.outCubic(0f), EPS);
        assertEquals(1f, PauseMenuAnim.outCubic(1f), EPS);
        assertEquals(0f, PauseMenuAnim.inCubic(0f), EPS);
        assertEquals(1f, PauseMenuAnim.inCubic(1f), EPS);
        assertEquals(1f, PauseMenuAnim.outExpo(1f), EPS);
        assertEquals(0f, PauseMenuAnim.outBack(0f), EPS);
        assertEquals(1f, PauseMenuAnim.outBack(1f), EPS);
        assertEquals(0f, PauseMenuAnim.outCubic(-5f), EPS, "越界输入必须钳住");
        assertEquals(1f, PauseMenuAnim.outCubic(5f), EPS);
        assertTrue(PauseMenuAnim.outBack(0.75f) > 1f, "outBack 应有过冲");
    }
}
