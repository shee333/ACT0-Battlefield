package org.shee33.act0.battlefield.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployConfirmFxTest {

    private static final long NOT_LANDED = -1L;
    private static final float EPS = 0.01f;

    // ---------------- flashAlpha：未落地(纯淡入曲线) ----------------

    @Test
    void flashBeforeDelayIsZero() {
        assertEquals(0f, DeployConfirmFx.flashAlpha(0L, NOT_LANDED), EPS);
        assertEquals(0f, DeployConfirmFx.flashAlpha(599L, NOT_LANDED), EPS);
    }

    @Test
    void flashMidFadeInIsPartial() {
        float a = DeployConfirmFx.flashAlpha(800L, NOT_LANDED);
        assertTrue(a > 0f && a < 0.95f, "淡入中段应处于 0~0.95 之间，实际=" + a);
    }

    @Test
    void flashFadeInCompletesAtMaxAlpha() {
        assertEquals(0.95f, DeployConfirmFx.flashAlpha(1000L, NOT_LANDED), EPS);
        assertEquals(0.95f, DeployConfirmFx.flashAlpha(5000L, NOT_LANDED), EPS);
    }

    @Test
    void flashAlphaMonotonicDuringFadeIn() {
        float a1 = DeployConfirmFx.flashAlpha(650L, NOT_LANDED);
        float a2 = DeployConfirmFx.flashAlpha(800L, NOT_LANDED);
        float a3 = DeployConfirmFx.flashAlpha(1000L, NOT_LANDED);
        assertTrue(a1 < a2 && a2 < a3, "淡入阶段 alpha 应单调递增");
    }

    // ---------------- flashAlpha：落地后淡出 ----------------

    @Test
    void flashLandingSnapshotStartsFadeOutFromCurrentValue() {
        // 落地(sinceLanded=0)那一瞬间，退场起点应等于"未落地"曲线在同一时刻的值，不跳变。
        long sinceTrigger = 1000L;
        float beforeLanding = DeployConfirmFx.flashAlpha(sinceTrigger, NOT_LANDED);
        float atLandingInstant = DeployConfirmFx.flashAlpha(sinceTrigger, 0L);
        assertEquals(beforeLanding, atLandingInstant, EPS);
    }

    @Test
    void flashFadesOutAfterLanding() {
        float a1 = DeployConfirmFx.flashAlpha(1000L, 0L);
        float a2 = DeployConfirmFx.flashAlpha(1110L, 110L);
        float a3 = DeployConfirmFx.flashAlpha(1220L, 220L);
        assertTrue(a1 > a2, "落地后 alpha 应开始下降");
        assertEquals(0f, a3, EPS, "淡出时长结束后应归零");
    }

    @Test
    void flashLandingBeforeFadeInCompletesInterruptsSmoothly() {
        // 落地发生在白闪还没淡入满(比如网络极快)——退场应从"当时的淡入值"平滑往下走，而不是从 0.95 起跳。
        long sinceTrigger = 700L; // 淡入中段
        float atLandingInstant = DeployConfirmFx.flashAlpha(sinceTrigger, 0L);
        float rawAtSameElapsed = DeployConfirmFx.flashAlpha(sinceTrigger, NOT_LANDED);
        assertEquals(rawAtSameElapsed, atLandingInstant, EPS);
    }

    // ---------------- textAlpha ----------------

    @Test
    void textHiddenBeforeFlashNearlyFull() {
        assertEquals(0f, DeployConfirmFx.textAlpha(999L, NOT_LANDED), EPS);
    }

    @Test
    void textAppearsOnceFlashCompletes() {
        float a = DeployConfirmFx.textAlpha(1050L, NOT_LANDED);
        assertTrue(a > 0f, "白闪淡入完成后文字应开始显示，实际=" + a);
    }

    @Test
    void textFullyVisibleAfterFadeIn() {
        assertEquals(1f, DeployConfirmFx.textAlpha(1200L, NOT_LANDED), EPS);
    }

    @Test
    void textFadesOutAfterLanding() {
        float a1 = DeployConfirmFx.textAlpha(1200L, 0L);
        float a2 = DeployConfirmFx.textAlpha(1310L, 110L);
        float a3 = DeployConfirmFx.textAlpha(1420L, 220L);
        assertTrue(a1 > a2 && a2 > a3, "落地后文字 alpha 应单调递减");
        assertEquals(0f, a3, EPS);
    }
}
