package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标选择打分（MC-free）单元测试：可交火判定、威胁排序与黏滞。
 */
class TargetScoringTest {

    private static final AimModel MODEL = AimModel.Difficulty.NORMAL.defaults();

    private static final UUID NEAR = UUID.nameUUIDFromBytes("near".getBytes());
    private static final UUID FAR = UUID.nameUUIDFromBytes("far".getBytes());

    private static TargetScoring.Candidate candidate(UUID id, double distance, float angle,
                                                     boolean los, boolean current) {
        return new TargetScoring.Candidate(id, distance, angle, los, current);
    }

    // ---------------- 可交火判定 ----------------

    @Test
    void targetWithoutLineOfSightIsNotEngageable() {
        TargetScoring.Candidate blocked = candidate(NEAR, 10, 0f, false, false);
        assertFalse(TargetScoring.isEngageable(blocked, MODEL));
        assertEquals(0.0F, TargetScoring.score(blocked, MODEL, 0f));
    }

    @Test
    void targetOutsideFieldOfViewIsNotEngageable() {
        float beyond = MODEL.fovHalfAngleDegrees() + 1.0F;
        TargetScoring.Candidate behind = candidate(NEAR, 10, beyond, true, false);
        assertFalse(TargetScoring.isEngageable(behind, MODEL));
        assertEquals(0.0F, TargetScoring.score(behind, MODEL, 0f));
    }

    @Test
    void targetAtFovEdgeIsStillEngageable() {
        TargetScoring.Candidate edge = candidate(NEAR, 10, MODEL.fovHalfAngleDegrees(), true, false);
        assertTrue(TargetScoring.isEngageable(edge, MODEL));
        assertTrue(TargetScoring.score(edge, MODEL, 0f) > 0.0F);
    }

    // ---------------- 排序 ----------------

    @Test
    void closerTargetOutranksFartherAtSameAngle() {
        float near = TargetScoring.score(candidate(NEAR, 8, 0f, true, false), MODEL, 0f);
        float far = TargetScoring.score(candidate(FAR, 40, 0f, true, false), MODEL, 0f);
        assertTrue(near > far, "近者应更高分：near=" + near + " far=" + far);
    }

    @Test
    void moreCentredTargetOutranksPeripheralAtSameDistance() {
        float centred = TargetScoring.score(candidate(NEAR, 20, 0f, true, false), MODEL, 0f);
        float peripheral = TargetScoring.score(
                candidate(FAR, 20, MODEL.fovHalfAngleDegrees() * 0.9F, true, false), MODEL, 0f);
        assertTrue(centred > peripheral, "视野更居中者应更高分");
    }

    @Test
    void selectPicksHighestScoringEngageableTarget() {
        Optional<TargetScoring.Candidate> chosen = TargetScoring.select(List.of(
                candidate(FAR, 40, 0f, true, false),
                candidate(NEAR, 8, 0f, true, false)), MODEL, 0f);
        assertTrue(chosen.isPresent());
        assertEquals(NEAR, chosen.get().id());
    }

    @Test
    void selectSkipsBlockedTargetEvenWhenMuchCloser() {
        Optional<TargetScoring.Candidate> chosen = TargetScoring.select(List.of(
                candidate(NEAR, 3, 0f, false, false),
                candidate(FAR, 40, 0f, true, false)), MODEL, 0f);
        assertTrue(chosen.isPresent());
        assertEquals(FAR, chosen.get().id(), "无视线者不应被选中，哪怕近得多");
    }

    @Test
    void selectReturnsEmptyWhenNothingEngageable() {
        assertTrue(TargetScoring.select(List.of(), MODEL, 0f).isEmpty());
        assertTrue(TargetScoring.select(List.of(
                candidate(NEAR, 5, 0f, false, false),
                candidate(FAR, 9, MODEL.fovHalfAngleDegrees() + 5f, true, false)), MODEL, 0f).isEmpty());
    }

    // ---------------- 黏滞 ----------------

    @Test
    void stickinessKeepsCurrentTargetAgainstMarginallyBetterRival() {
        // 当前目标 20 格居中；对手 18 格居中——仅近 10%，不应引发切换
        Optional<TargetScoring.Candidate> chosen = TargetScoring.select(List.of(
                        candidate(NEAR, 20, 0f, true, true),
                        candidate(FAR, 18, 0f, true, false)),
                MODEL, TargetScoring.DEFAULT_STICKINESS);
        assertTrue(chosen.isPresent());
        assertEquals(NEAR, chosen.get().id(), "略优的对手不应抢走当前目标");
    }

    @Test
    void clearlyBetterRivalStillOverridesStickiness() {
        // 当前目标 40 格且接近视野边缘；对手 5 格居中——差距足够大，应切换
        Optional<TargetScoring.Candidate> chosen = TargetScoring.select(List.of(
                        candidate(NEAR, 40, 50f, true, true),
                        candidate(FAR, 5, 0f, true, false)),
                MODEL, TargetScoring.DEFAULT_STICKINESS);
        assertTrue(chosen.isPresent());
        assertEquals(FAR, chosen.get().id(), "明显更值得打的目标应能覆盖黏滞");
    }

    @Test
    void withoutStickinessMarginallyBetterRivalWins() {
        // 关掉黏滞后同一组态势会切换——反证黏滞确实在起作用，而非距离项本身导致的结果
        Optional<TargetScoring.Candidate> chosen = TargetScoring.select(List.of(
                candidate(NEAR, 20, 0f, true, true),
                candidate(FAR, 18, 0f, true, false)), MODEL, 0.0F);
        assertTrue(chosen.isPresent());
        assertEquals(FAR, chosen.get().id());
    }

    @Test
    void stickinessBonusIsProportionalAndIgnoresNegativeValues() {
        TargetScoring.Candidate current = candidate(NEAR, 20, 0f, true, true);
        float plain = TargetScoring.score(candidate(NEAR, 20, 0f, true, false), MODEL, 0f);
        float sticky = TargetScoring.score(current, MODEL, 0.5F);
        assertEquals(plain * 1.5F, sticky, 1.0e-4F);
        assertEquals(plain, TargetScoring.score(current, MODEL, -1.0F), 1.0e-4F,
                "负黏滞应被视作 0，不得反向惩罚当前目标");
    }
}
