package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行军战术（MC-free）单元测试：疾跑门槛与扫视波形。
 */
class MarchTacticsTest {

    private static final float EPS = 1.0e-3F;

    // ---------------- 疾跑 ----------------

    @Test
    void neverSprintsWhileEngaged() {
        // TaCZ 疾跑时拒绝开火，交火中疾跑等于让 bot 变成不还手的靶子
        assertFalse(MarchTactics.shouldSprint(true, 100.0D));
        assertFalse(MarchTactics.shouldSprint(true, MarchTactics.SPRINT_MIN_DISTANCE));
    }

    @Test
    void sprintsOnlyBeyondThreshold() {
        assertTrue(MarchTactics.shouldSprint(false, MarchTactics.SPRINT_MIN_DISTANCE));
        assertTrue(MarchTactics.shouldSprint(false, 60.0D));
        assertFalse(MarchTactics.shouldSprint(false, MarchTactics.SPRINT_MIN_DISTANCE - 0.01D));
        assertFalse(MarchTactics.shouldSprint(false, 0.0D));
    }

    @Test
    void sprintThresholdLeavesRoomToDecelerateBeforeEngageRange() {
        // 疾跑门槛必须明显高于交火距离，否则会疾跑冲进交火距离、到面前才收枪
        assertTrue(MarchTactics.SPRINT_MIN_DISTANCE > CombatStance.DEFAULT_ENGAGE_RANGE + 4.0D,
                "疾跑门槛距交火距离的余量不足");
    }

    // ---------------- 扫视 ----------------

    @Test
    void scanStartsCentredAndStaysWithinAmplitude() {
        assertEquals(0.0F, MarchTactics.scanOffsetDegrees(0L, 0), EPS);
        for (long t = 0; t < MarchTactics.SCAN_PERIOD_TICKS * 3L; t++) {
            float offset = MarchTactics.scanOffsetDegrees(t, 0);
            assertTrue(Math.abs(offset) <= MarchTactics.SCAN_AMPLITUDE_DEGREES + EPS,
                        "tick " + t + " 的扫视偏移 " + offset + " 超出幅度");
        }
    }

    @Test
    void scanReachesBothExtremes() {
        float max = -999.0F;
        float min = 999.0F;
        for (long t = 0; t < MarchTactics.SCAN_PERIOD_TICKS; t++) {
            float offset = MarchTactics.scanOffsetDegrees(t, 0);
            max = Math.max(max, offset);
            min = Math.min(min, offset);
        }
        // 必须真的扫到两侧，否则等于只加宽了一边的视野
        assertTrue(max > MarchTactics.SCAN_AMPLITUDE_DEGREES * 0.98F, "未扫到右侧极值，实际 " + max);
        assertTrue(min < -MarchTactics.SCAN_AMPLITUDE_DEGREES * 0.98F, "未扫到左侧极值，实际 " + min);
    }

    @Test
    void scanIsPeriodic() {
        for (long t = 0; t < 20; t++) {
            assertEquals(MarchTactics.scanOffsetDegrees(t, 0),
                    MarchTactics.scanOffsetDegrees(t + MarchTactics.SCAN_PERIOD_TICKS, 0), EPS);
        }
    }

    @Test
    void phaseOffsetDesynchronisesBots() {
        // 整队 bot 同步摆头会立刻暴露它们是同一套程序
        float a = MarchTactics.scanOffsetDegrees(0L, 0);
        float b = MarchTactics.scanOffsetDegrees(0L, MarchTactics.SCAN_PERIOD_TICKS / 4);
        assertTrue(Math.abs(a - b) > 1.0F, "不同相位偏移应给出不同的扫视角");
    }

    /**
     * 扫视后仍须为身后留出死角。
     *
     * <p>{@link AimModel} 的视野参数是<b>半角</b>（五档 ±50°~±80°），叠加扫视后的有效半角
     * 若逼近 180°，bot 就成了背后也能察觉的全知体——那比原本的窄视野更伤玩法。
     * 故上限定在 120°：仍给玩家留下从侧后方接近的空间。
     */
    @Test
    void scanKeepsARealBlindSpotBehind() {
        for (AimModel.Difficulty tier : AimModel.Difficulty.values()) {
            float effective = halfFovOf(tier) + MarchTactics.SCAN_AMPLITUDE_DEGREES;
            assertTrue(effective <= 120.0F,
                    tier + " 档扫视后有效半角达 " + effective + "°，已接近全知");
        }
    }

    @Test
    void scanActuallyExtendsTheNarrowestTier() {
        // 最窄的一档必须确实被扩宽，否则扫视对察觉毫无贡献、只剩动画意义
        AimModel.Difficulty narrowest = AimModel.Difficulty.ROOKIE;
        float half = halfFovOf(narrowest);
        assertFalse(narrowest.defaults().withinFov(half + 1.0F), "探测到的半角有误");
        assertTrue(MarchTactics.SCAN_AMPLITUDE_DEGREES >= 10.0F,
                "扫视幅度过小，对最窄档的察觉没有实质补充");
    }

    /** 用 {@code withinFov} 逐度探测某档的视野半角——{@link AimModel} 未暴露该字段的读取器。 */
    private static float halfFovOf(AimModel.Difficulty tier) {
        for (float angle = 0.0F; angle <= 180.0F; angle += 1.0F) {
            if (!tier.defaults().withinFov(angle)) {
                return angle - 1.0F;
            }
        }
        return 180.0F;
    }
}
