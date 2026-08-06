package org.shee33.act0.battlefield.match;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedeployService#isSlotOverrideThrottled} 是纯函数（只依赖"上次处理tick"与"当前tick"，
 * 不依赖 {@code ServerPlayer}/{@code MinecraftServer}），覆盖 P1-2 修复的节流边界。
 */
class RedeployServiceSlotOverrideThrottleTest {

    @Test
    void firstRequestIsNeverThrottled() {
        assertFalse(RedeployService.isSlotOverrideThrottled(null, 0L), "从未处理过的玩家第一次请求不应被节流");
    }

    @Test
    void requestWithinIntervalIsThrottled() {
        assertTrue(RedeployService.isSlotOverrideThrottled(100L, 101L), "间隔1 tick(<2 tick)应被节流拒绝");
    }

    @Test
    void requestAtExactIntervalIsNotThrottled() {
        assertFalse(RedeployService.isSlotOverrideThrottled(100L, 102L), "间隔恰好等于门槛(2 tick)应放行");
    }

    @Test
    void requestBeyondIntervalIsNotThrottled() {
        assertFalse(RedeployService.isSlotOverrideThrottled(100L, 200L), "间隔远超门槛应放行");
    }
}
