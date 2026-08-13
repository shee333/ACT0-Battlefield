package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.bot.RevivePolicy.Candidate;
import org.shee33.act0.battlefield.bot.RevivePolicy.Situation;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevivePolicyTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    /** 满血、步行速度、未交火。 */
    private static final Situation HEALTHY = new Situation(1.0D, 4.3D, false);

    private static Candidate candidate(UUID id, double dist, int secs) {
        return new Candidate(id, dist, secs, true, false, false);
    }

    @Test
    void alreadyInRangeCostsOnlyTheReviveItself() {
        Candidate near = candidate(A, RevivePolicy.REVIVE_RANGE_BLOCKS, 10);
        assertEquals(RevivePolicy.REVIVE_SECONDS,
                RevivePolicy.estimatedSeconds(near, HEALTHY), 1.0e-9D);
    }

    @Test
    void syringeCutsReviveTimeToOneThird() {
        Candidate bare = new Candidate(A, 0.0D, 10, true, false, false);
        Candidate syringe = new Candidate(A, 0.0D, 10, true, true, false);
        assertEquals(RevivePolicy.REVIVE_SECONDS, bare.reviveSeconds(), 1.0e-9D);
        assertEquals(RevivePolicy.SYRINGE_REVIVE_SECONDS, syringe.reviveSeconds(), 1.0e-9D);
        assertTrue(RevivePolicy.estimatedSeconds(syringe, HEALTHY)
                < RevivePolicy.estimatedSeconds(bare, HEALTHY));
    }

    /**
     * 这条是本类存在的主要理由：朝一个必然流血至死的队友跑过去，等于用自己一条命换零收益，
     * 也是最容易让 AI 显得很蠢的行为。
     */
    @Test
    void refusesUnreachableRevive() {
        // 200 格外、只剩 4 秒：步行绝无可能
        assertFalse(RevivePolicy.worthGoing(candidate(A, 200.0D, 4), HEALTHY));
    }

    @Test
    void acceptsReachableRevive() {
        // 10 格外、还剩 12 秒：赶路约 1.4s×1.25 + 救援 3s，来得及
        assertTrue(RevivePolicy.worthGoing(candidate(A, 10.0D, 12), HEALTHY));
    }

    @Test
    void travelSafetyMarginRejectsMarginalCases() {
        // 构造一个"不留余量刚好赶上、留余量则赶不上"的距离，锁住安全系数确实生效
        double secs = 6.0D;
        double speed = 4.3D;
        double reachableExactly = (secs - RevivePolicy.REVIVE_SECONDS) * speed
                + RevivePolicy.REVIVE_RANGE_BLOCKS;
        Candidate marginal = candidate(A, reachableExactly, (int) secs);
        assertFalse(RevivePolicy.worthGoing(marginal, HEALTHY),
                "刚好够的距离必须因安全余量被拒——寻路绕行会吃掉这点富余");
    }

    @Test
    void lowHealthBotDoesNotAttemptRevive() {
        Situation wounded = new Situation(0.20D, 4.3D, false);
        assertFalse(RevivePolicy.worthGoing(candidate(A, 3.0D, 14), wounded),
                "残血扶人是送双杀");
    }

    @Test
    void healthGateSitsAboveRetreatThreshold() {
        assertTrue(RevivePolicy.MIN_HEALTH_FRACTION > RetreatPolicy.DEFAULT_BREAK_OFF_HEALTH,
                "救援门槛必须高于脱离血线，否则刚决定撤退就被救援拉回火线");
    }

    @Test
    void unpermittedCandidateIsSkipped() {
        Candidate noPermission = new Candidate(A, 3.0D, 14, false, false, false);
        assertFalse(RevivePolicy.worthGoing(noPermission, HEALTHY));
    }

    @Test
    void yieldsToACloserAlly() {
        Candidate taken = new Candidate(A, 3.0D, 14, true, false, true);
        assertFalse(RevivePolicy.worthGoing(taken, HEALTHY), "已有更近的友军在去，不该两人同去");
    }

    @Test
    void expiredCandidateIsSkipped() {
        assertFalse(RevivePolicy.worthGoing(candidate(A, 1.0D, 0), HEALTHY));
    }

    /**
     * 取最紧迫而非最近：最近那个可能还有 14 秒，5 格外那个只剩 4 秒。先救快死的两个都能活，
     * 先救近的远的必死。
     */
    @Test
    void picksMostUrgentNotNearest() {
        Candidate nearButSafe = candidate(A, 2.0D, 14);
        Candidate slightlyFarButDying = candidate(B, 5.0D, 5);
        assertEquals(B, RevivePolicy.pick(List.of(nearButSafe, slightlyFarButDying), HEALTHY)
                .orElseThrow().targetId());
    }

    @Test
    void pickSkipsUnreachableEvenIfMoreUrgent() {
        Candidate dyingButFar = candidate(A, 300.0D, 2);
        Candidate reachable = candidate(B, 6.0D, 13);
        assertEquals(B, RevivePolicy.pick(List.of(dyingButFar, reachable), HEALTHY)
                .orElseThrow().targetId());
    }

    @Test
    void pickReturnsEmptyWhenNothingIsWorthGoing() {
        assertTrue(RevivePolicy.pick(List.of(candidate(A, 500.0D, 3)), HEALTHY).isEmpty());
        assertTrue(RevivePolicy.pick(List.of(), HEALTHY).isEmpty());
    }

    @Test
    void inRangeMatchesTheServerSideDistanceCheck() {
        assertTrue(RevivePolicy.inRange(RevivePolicy.REVIVE_RANGE_BLOCKS));
        assertFalse(RevivePolicy.inRange(RevivePolicy.REVIVE_RANGE_BLOCKS + 0.01D));
        // 服务端判定是 distanceToSqr > 16.0，即 4 格
        assertEquals(16.0D, RevivePolicy.REVIVE_RANGE_BLOCKS * RevivePolicy.REVIVE_RANGE_BLOCKS, 1.0e-9D);
    }

    @Test
    void zeroSpeedDoesNotDivideByZero() {
        Situation frozen = new Situation(1.0D, 0.0D, false);
        assertTrue(RevivePolicy.estimatedSeconds(candidate(A, 50.0D, 10), frozen) > 0.0D);
    }
}
