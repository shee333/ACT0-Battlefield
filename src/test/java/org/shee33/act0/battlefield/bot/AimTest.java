package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 瞄准模型（MC-free）单元测试：{@link AimModel} 参数校验与误差解算 + {@link AimTracker} 状态机。
 */
class AimTest {

    private static final float EPS = 1.0e-3F;

    private static AimModel model() {
        return AimModel.Difficulty.NORMAL.defaults();
    }

    // ---------------- AimModel 校验 ----------------

    @Test
    void rejectsErrorThatGrowsWithTrackingTime() {
        assertThrows(IllegalArgumentException.class, () ->
                new AimModel(8, 60f, 10f, 1.0f, 5.0f, 20, 0.5f, 0.2f, 3, 5, 10, 20),
                "误差必须随跟踪时间收敛，initial < settled 应被拒绝");
    }

    @Test
    void rejectsNonPositiveTurnRate() {
        assertThrows(IllegalArgumentException.class, () ->
                new AimModel(8, 60f, 0f, 4f, 1.5f, 20, 0.5f, 0.2f, 3, 5, 10, 20));
    }

    @Test
    void rejectsOutOfRangeFov() {
        assertThrows(IllegalArgumentException.class, () ->
                new AimModel(8, 0f, 10f, 4f, 1.5f, 20, 0.5f, 0.2f, 3, 5, 10, 20));
        assertThrows(IllegalArgumentException.class, () ->
                new AimModel(8, 181f, 10f, 4f, 1.5f, 20, 0.5f, 0.2f, 3, 5, 10, 20));
    }

    @Test
    void rejectsInvalidBurstRange() {
        assertThrows(IllegalArgumentException.class, () ->
                new AimModel(8, 60f, 10f, 4f, 1.5f, 20, 0.5f, 0.2f, 5, 3, 10, 20));
        assertThrows(IllegalArgumentException.class, () ->
                new AimModel(8, 60f, 10f, 4f, 1.5f, 20, 0.5f, 0.2f, 0, 3, 10, 20));
    }

    @Test
    void rejectsNegativeReactionAndZeroConverge() {
        assertThrows(IllegalArgumentException.class, () ->
                new AimModel(-1, 60f, 10f, 4f, 1.5f, 20, 0.5f, 0.2f, 3, 5, 10, 20));
        assertThrows(IllegalArgumentException.class, () ->
                new AimModel(8, 60f, 10f, 4f, 1.5f, 0, 0.5f, 0.2f, 3, 5, 10, 20));
    }

    // ---------------- 误差解算 ----------------

    @Test
    void baseErrorConvergesLinearlyFromInitialToSettled() {
        AimModel m = model();
        assertEquals(m.errorInitialDegrees(), m.baseErrorDegrees(0), EPS);
        assertEquals(m.errorSettledDegrees(), m.baseErrorDegrees(m.errorConvergeTicks()), EPS);
        assertEquals(m.errorSettledDegrees(), m.baseErrorDegrees(m.errorConvergeTicks() * 10), EPS);

        float mid = m.baseErrorDegrees(m.errorConvergeTicks() / 2);
        float expected = m.errorInitialDegrees()
                + (m.errorSettledDegrees() - m.errorInitialDegrees()) * 0.5F;
        assertEquals(expected, mid, EPS);
    }

    @Test
    void totalErrorAddsRecoilAndClampsAtCeiling() {
        AimModel m = model();
        float base = m.baseErrorDegrees(0);
        assertEquals(base + 2.0F, m.totalErrorDegrees(0, 2.0F), EPS);
        assertEquals(base, m.totalErrorDegrees(0, -5.0F), EPS, "负后坐力应视作 0");
        assertEquals(AimModel.MAX_ERROR_DEGREES, m.totalErrorDegrees(0, 999.0F), EPS);
    }

    @Test
    void fovIsSymmetricAroundFacing() {
        AimModel m = model();
        assertTrue(m.withinFov(0f));
        assertTrue(m.withinFov(m.fovHalfAngleDegrees()));
        assertTrue(m.withinFov(-m.fovHalfAngleDegrees()));
        assertFalse(m.withinFov(m.fovHalfAngleDegrees() + 0.1f));
    }

    @Test
    void lateralOffsetMatchesDocumentedCalibrationAnchor() {
        // 文档里的标定锚点：30 格处 1° ≈ 0.52 格，而玩家碰撞箱宽 0.6 格。
        assertEquals(0.5237D, AimModel.lateralOffsetBlocks(1.0F, 30.0D), 1.0e-3D);
    }

    @Test
    void documentedPerDifficultyOffsetsAreAccurate() {
        // 各档 javadoc 里写明的"30 格上偏移"必须与实际解算一致，否则调参依据是错的。
        assertEquals(1.57D, offsetAt30(AimModel.Difficulty.ROOKIE), 0.01D);
        assertEquals(0.79D, offsetAt30(AimModel.Difficulty.NORMAL), 0.01D);
        assertEquals(0.37D, offsetAt30(AimModel.Difficulty.ADVANCED), 0.01D);
    }

    private static double offsetAt30(AimModel.Difficulty difficulty) {
        return AimModel.lateralOffsetBlocks(difficulty.defaults().errorSettledDegrees(), 30.0D);
    }

    // ---------------- 难度单调性（设计不变量）----------------

    @Test
    void escalationLadderExcludesOnlyRealistic() {
        assertEquals(
                java.util.List.of(AimModel.Difficulty.ROOKIE, AimModel.Difficulty.NORMAL,
                        AimModel.Difficulty.ADVANCED, AimModel.Difficulty.ULTIMATE),
                java.util.Arrays.stream(AimModel.Difficulty.values())
                        .filter(AimModel.Difficulty::onEscalationLadder).toList(),
                "主阶梯应为四档纯递增，写实档是唯一的旁支");
    }

    /**
     * 写实档的设计意图锁：<b>枪法向终极靠、感知反而弱于高级</b>。
     *
     * <p>这条断言存在的唯一目的是防止后人把写实档"修正"成纯递增的一档——视野 ±60° 小于高级的
     * ±70°、记忆 24 tick 短于高级的 40 tick，单看数字很像手误，实际是这一档的全部立意所在
     * （可被战术击败，但难以对枪击败）。
     */
    @Test
    void realisticTierTradesPerceptionForGunplay() {
        AimModel realistic = AimModel.Difficulty.REALISTIC.defaults();
        AimModel advanced = AimModel.Difficulty.ADVANCED.defaults();
        AimModel ultimate = AimModel.Difficulty.ULTIMATE.defaults();

        assertTrue(realistic.fovHalfAngleDegrees() < advanced.fovHalfAngleDegrees(),
                "写实档视野必须窄于高级档——可被绕侧是本档立意");
        assertTrue(realistic.reacquireTicks() < advanced.reacquireTicks(),
                "写实档目标记忆必须短于高级档——会跟丢是本档立意");

        assertTrue(realistic.errorSettledDegrees() < advanced.errorSettledDegrees()
                        && realistic.errorSettledDegrees() > ultimate.errorSettledDegrees(),
                "写实档枪法应落在高级与终极之间");
        assertTrue(realistic.reactionTicks() < advanced.reactionTicks()
                        && realistic.reactionTicks() > ultimate.reactionTicks(),
                "写实档反应应落在高级与终极之间");
        assertFalse(realistic.errorSettledDegrees() <= 0.0F, "枪法再好也不得为零误差");
    }

    @Test
    void higherDifficultyIsStrictlyBetterInEveryDimension() {
        AimModel.Difficulty[] order = java.util.Arrays.stream(AimModel.Difficulty.values())
                .filter(AimModel.Difficulty::onEscalationLadder)
                .toArray(AimModel.Difficulty[]::new);
        for (int i = 1; i < order.length; i++) {
            AimModel lo = order[i - 1].defaults();
            AimModel hi = order[i].defaults();
            String pair = order[i - 1] + "→" + order[i];
            assertTrue(hi.reactionTicks() < lo.reactionTicks(), pair + " 反应应更快");
            assertTrue(hi.turnRateDegPerTick() > lo.turnRateDegPerTick(), pair + " 转向应更快");
            assertTrue(hi.errorInitialDegrees() < lo.errorInitialDegrees(), pair + " 初始误差应更小");
            assertTrue(hi.errorSettledDegrees() < lo.errorSettledDegrees(), pair + " 收敛误差应更小");
            assertTrue(hi.errorConvergeTicks() < lo.errorConvergeTicks(), pair + " 收敛应更快");
            assertTrue(hi.errorPerShotDegrees() < lo.errorPerShotDegrees(), pair + " 后坐力应更小");
            assertTrue(hi.errorRecoveryPerTick() > lo.errorRecoveryPerTick(), pair + " 回落应更快");
            assertTrue(hi.fovHalfAngleDegrees() > lo.fovHalfAngleDegrees(), pair + " 视野应更广");
            assertTrue(hi.burstPauseTicks() < lo.burstPauseTicks(), pair + " 点射停顿应更短");
            assertTrue(hi.reacquireTicks() > lo.reacquireTicks(), pair + " 目标记忆应更久");
        }
    }

    // ---------------- AimTracker 状态机 ----------------

    @Test
    void cannotFireBeforeReactionDelayElapses() {
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 1L);
        for (int i = 0; i < m.reactionTicks() - 1; i++) {
            tracker.tick(true);
            assertFalse(tracker.canFire(), "第 " + (i + 1) + " tick 就允许开火，反应延迟失效");
        }
        tracker.tick(true);
        assertTrue(tracker.canFire());
    }

    @Test
    void losingSightBeyondMemoryWindowResetsReactionDelay() {
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 1L);
        for (int i = 0; i < m.reactionTicks(); i++) {
            tracker.tick(true);
        }
        assertTrue(tracker.canFire());

        for (int i = 0; i <= m.reacquireTicks(); i++) {
            tracker.tick(false);
        }
        assertFalse(tracker.hasTargetMemory());
        assertEquals(0, tracker.ticksOnTarget());

        tracker.tick(true);
        assertFalse(tracker.canFire(), "重新获得目标后必须重走反应延迟");
    }

    @Test
    void targetMemoryHoldsWithinReacquireWindow() {
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 1L);
        for (int i = 0; i < m.reactionTicks(); i++) {
            tracker.tick(true);
        }
        for (int i = 0; i < m.reacquireTicks(); i++) {
            tracker.tick(false);
        }
        assertTrue(tracker.hasTargetMemory(), "记忆期内应仍记得目标");
    }

    @Test
    void burstDisciplineForcesPauseAfterBurstAndPauseIsPlayerWindow() {
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 42L);
        for (int i = 0; i < m.reactionTicks(); i++) {
            tracker.tick(true);
        }

        int shots = 0;
        while (tracker.canFire() && shots < m.burstMaxShots() + 1) {
            tracker.onShotFired();
            shots++;
        }
        assertTrue(shots >= m.burstMinShots() && shots <= m.burstMaxShots(),
                "点射长度应落在 [" + m.burstMinShots() + "," + m.burstMaxShots() + "]，实际 " + shots);
        assertFalse(tracker.canFire(), "打满一轮后应强制停顿");
        assertEquals(m.burstPauseTicks(), tracker.burstPauseRemaining());

        for (int i = 0; i < m.burstPauseTicks(); i++) {
            tracker.tick(true);
        }
        assertTrue(tracker.canFire(), "停顿结束后应恢复开火");
    }

    @Test
    void recoilGrowsWhileFiringAndDecaysWhenIdle() {
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 7L);
        assertEquals(0.0F, tracker.recoilDegrees(), EPS);

        tracker.onShotFired();
        float afterOneShot = tracker.recoilDegrees();
        assertEquals(m.errorPerShotDegrees(), afterOneShot, EPS);

        tracker.tick(true);
        assertTrue(tracker.recoilDegrees() < afterOneShot, "空闲 tick 应回落后坐力");

        for (int i = 0; i < 200; i++) {
            tracker.tick(true);
        }
        assertEquals(0.0F, tracker.recoilDegrees(), EPS, "长时间不开火应完全回落");
    }

    @Test
    void errorIsNeverBelowSettledNorAboveCeiling() {
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 3L);
        for (int i = 0; i < 500; i++) {
            tracker.tick(true);
            if (tracker.canFire()) {
                tracker.onShotFired();
            }
            float err = tracker.errorDegrees();
            assertTrue(err >= m.errorSettledDegrees() - EPS, "误差不应低于收敛下限");
            assertTrue(err <= AimModel.MAX_ERROR_DEGREES + EPS, "误差不应超过上限");
        }
    }

    @Test
    void forgetTargetClearsTrackingAndPause() {
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 5L);
        for (int i = 0; i < m.reactionTicks(); i++) {
            tracker.tick(true);
        }
        tracker.onShotFired();
        tracker.forgetTarget();
        assertEquals(0, tracker.ticksOnTarget());
        assertEquals(0, tracker.burstPauseRemaining());
        assertFalse(tracker.hasTargetMemory());
        assertFalse(tracker.canFire());
    }

    @Test
    void aimOffsetNeverExceedsCurrentErrorCircle() {
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 11L);
        for (int i = 0; i < 300; i++) {
            tracker.tick(true);
            if (tracker.canFire()) {
                tracker.onShotFired();
            }
            AimTracker.AimOffset offset = tracker.rollAimOffset();
            assertTrue(offset.magnitudeDegrees() <= tracker.errorDegrees() + EPS,
                    "偏移 " + offset.magnitudeDegrees() + " 超出误差圆 " + tracker.errorDegrees());
        }
    }

    @Test
    void aimOffsetIsUniformOverDiscNotClusteredAtCentre() {
        // r = R·√u 采样下，落在半径 R/2 内的样本占比应约为面积比 25%；
        // 若误用 r = R·u，占比会变成约 50%，bot 命中率将显著高于误差圆所声称的水平。
        AimModel m = model();
        AimTracker tracker = new AimTracker(m, 2024L);
        for (int i = 0; i < m.reactionTicks(); i++) {
            tracker.tick(true);
        }
        float radius = tracker.errorDegrees();
        int samples = 20000;
        int inner = 0;
        for (int i = 0; i < samples; i++) {
            if (tracker.rollAimOffset().magnitudeDegrees() <= radius / 2.0F) {
                inner++;
            }
        }
        double fraction = (double) inner / samples;
        assertTrue(fraction > 0.22D && fraction < 0.28D,
                "内半径样本占比应约 0.25（面积比），实际 " + fraction);
    }

    @Test
    void sameSeedProducesSameBurstPattern() {
        AimModel m = model();
        assertEquals(burstPattern(m, 99L), burstPattern(m, 99L));
    }

    private static String burstPattern(AimModel m, long seed) {
        AimTracker tracker = new AimTracker(m, seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            tracker.tick(true);
            if (tracker.canFire()) {
                tracker.onShotFired();
                sb.append('X');
            } else {
                sb.append('.');
            }
        }
        return sb.toString();
    }
}
