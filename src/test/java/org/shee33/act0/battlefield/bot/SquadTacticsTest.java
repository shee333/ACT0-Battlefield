package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquadTacticsTest {

    private static final AimModel MODEL = AimModel.Difficulty.NORMAL.defaults();

    // ---------------- 集火 ----------------

    @Test
    void focusFireOnlyAppliesWhenTeammateIsEngaging() {
        assertEquals(SquadTactics.FOCUS_FIRE_BONUS, SquadTactics.focusFireBonus(true));
        assertEquals(0.0F, SquadTactics.focusFireBonus(false));
    }

    /**
     * 集火加成必须弱于黏滞，否则已在对枪的 bot 会被队友的视野拽走目标——全队枪口来回摆，
     * 正是黏滞机制要消除的那种"AI 味"。
     */
    @Test
    void focusFireIsWeakerThanStickiness() {
        assertTrue(SquadTactics.FOCUS_FIRE_BONUS < TargetScoring.DEFAULT_STICKINESS,
                "集火加成必须压在黏滞之下");
    }

    @Test
    void focusFireBreaksTiesButDoesNotStealCurrentTarget() {
        UUID current = UUID.randomUUID();
        UUID focused = UUID.randomUUID();
        // 两个候选态势完全相同：一个是当前目标，一个被队友集火
        TargetScoring.Candidate mine =
                new TargetScoring.Candidate(current, 20.0D, 0.0F, true, true, 0.0F);
        TargetScoring.Candidate theirs = new TargetScoring.Candidate(
                focused, 20.0D, 0.0F, true, false, SquadTactics.FOCUS_FIRE_BONUS);
        assertTrue(TargetScoring.score(mine, MODEL, TargetScoring.DEFAULT_STICKINESS)
                        > TargetScoring.score(theirs, MODEL, TargetScoring.DEFAULT_STICKINESS),
                "当前目标应压过被集火的新目标");

        // 没有当前目标时，被集火者应胜出
        TargetScoring.Candidate plain =
                new TargetScoring.Candidate(current, 20.0D, 0.0F, true, false, 0.0F);
        assertTrue(TargetScoring.score(theirs, MODEL, TargetScoring.DEFAULT_STICKINESS)
                        > TargetScoring.score(plain, MODEL, TargetScoring.DEFAULT_STICKINESS),
                "无当前目标时应优先集火");
    }

    @Test
    void focusFireNeverOverridesLineOfSight() {
        TargetScoring.Candidate blocked = new TargetScoring.Candidate(
                UUID.randomUUID(), 10.0D, 0.0F, false, false, 10.0F);
        assertEquals(0.0F, TargetScoring.score(blocked, MODEL, TargetScoring.DEFAULT_STICKINESS),
                "再高的集火权重也不得让不可见目标可被选中");
    }

    // ---------------- 散开 ----------------

    @Test
    void separationIsZeroWhenSpacedEnough() {
        assertEquals(0.0D, SquadTactics.separationStrength(SquadTactics.SPACING_MIN_BLOCKS));
        assertEquals(0.0D, SquadTactics.separationStrength(50.0D));
        assertEquals(0.0D, SquadTactics.separationStrength(Double.MAX_VALUE));
    }

    @Test
    void separationRampsSmoothlyRatherThanSwitching() {
        double prev = 0.0D;
        for (double d = SquadTactics.SPACING_MIN_BLOCKS; d >= 0.0D; d -= 0.25D) {
            double s = SquadTactics.separationStrength(d);
            assertTrue(s >= prev, "排斥强度必须随距离缩短单调不减");
            assertTrue(s <= 1.0D);
            prev = s;
        }
        assertEquals(1.0D, SquadTactics.separationStrength(0.0D));
        assertEquals(0.5D, SquadTactics.separationStrength(SquadTactics.SPACING_MIN_BLOCKS / 2), 1.0e-9D);
    }

    // ---------------- 角色分配 ----------------

    @Test
    void lowFlankBiasKeepsEveryoneSuppressing() {
        for (int rank = 0; rank < 6; rank++) {
            assertEquals(SquadTactics.Role.SUPPRESS, SquadTactics.roleFor(rank, 0.0F));
            assertEquals(0.0F, SquadTactics.flankOffsetDegrees(rank, 0.0F));
        }
    }

    /** 序号 0 恒为压制：小队里必须有人钉住目标，全员绕侧等于全员脱火。 */
    @Test
    void rankZeroAlwaysAnchors() {
        assertEquals(SquadTactics.Role.SUPPRESS, SquadTactics.roleFor(0, 1.0F));
        assertEquals(0.0F, SquadTactics.flankOffsetDegrees(0, 1.0F));
    }

    @Test
    void rolesAlternateSoSquadDoesNotAllFlank() {
        float bias = 1.0F;
        assertEquals(SquadTactics.Role.SUPPRESS, SquadTactics.roleFor(0, bias));
        assertEquals(SquadTactics.Role.FLANK, SquadTactics.roleFor(1, bias));
        assertEquals(SquadTactics.Role.SUPPRESS, SquadTactics.roleFor(2, bias));
        assertEquals(SquadTactics.Role.FLANK, SquadTactics.roleFor(3, bias));
    }

    @Test
    void twoFlankersSplitToOppositeSides() {
        float bias = 1.0F;
        float first = SquadTactics.flankOffsetDegrees(1, bias);
        float second = SquadTactics.flankOffsetDegrees(3, bias);
        assertNotEquals(0.0F, first);
        assertNotEquals(0.0F, second);
        assertTrue(first * second < 0.0F, "两名绕侧者必须分走目标两侧，否则叠在同一边毫无意义");
    }

    // ---------------- 接近方向旋转 ----------------

    @Test
    void rotationPreservesLength() {
        double[] out = SquadTactics.rotateApproach(3.0D, 4.0D, SquadTactics.FLANK_OFFSET_DEGREES);
        assertEquals(5.0D, Math.hypot(out[0], out[1]), 1.0e-9D, "旋转只转向不缩放");
    }

    @Test
    void rotationByZeroIsIdentity() {
        double[] out = SquadTactics.rotateApproach(1.0D, -2.0D, 0.0F);
        assertEquals(1.0D, out[0], 1.0e-9D);
        assertEquals(-2.0D, out[1], 1.0e-9D);
    }

    @Test
    void rotationByNinetyDegreesIsPerpendicular() {
        double[] out = SquadTactics.rotateApproach(1.0D, 0.0D, 90.0F);
        assertEquals(0.0D, out[0], 1.0e-9D);
        assertEquals(1.0D, out[1], 1.0e-9D);
    }
}
