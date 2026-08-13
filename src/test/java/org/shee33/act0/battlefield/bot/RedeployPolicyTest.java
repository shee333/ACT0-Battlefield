package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.bot.RedeployPolicy.Kind;
import org.shee33.act0.battlefield.bot.RedeployPolicy.Option;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedeployPolicyTest {

    private static Option opt(Kind kind, String id, double dist, boolean safe) {
        return new Option(kind, id, dist, safe);
    }

    /**
     * 不安全落点是硬门槛而非低权重：落地即被打死的重生不管多近都是负收益。
     * 服务端已按"队友身边 12 格内有敌人"做过校验，这里只是不让它参与比较。
     */
    @Test
    void unsafeOptionsAreExcludedNotJustDeprioritized() {
        Option unsafeButAdjacent = opt(Kind.SQUADMATE, "mate", 1.0D, false);
        Option safeButFar = opt(Kind.BASE, "", 300.0D, true);
        assertEquals(Kind.BASE, RedeployPolicy.best(List.of(unsafeButAdjacent, safeButFar))
                .orElseThrow().kind());
    }

    @Test
    void picksClosestToObjective() {
        Option far = opt(Kind.POINT, "1", 200.0D, true);
        Option near = opt(Kind.POINT, "2", 20.0D, true);
        assertEquals("2", RedeployPolicy.best(List.of(far, near)).orElseThrow().targetId());
    }

    @Test
    void squadmateWinsTiesAgainstAPoint() {
        Option mate = opt(Kind.SQUADMATE, "mate", 50.0D, true);
        Option point = opt(Kind.POINT, "1", 50.0D, true);
        assertEquals(Kind.SQUADMATE, RedeployPolicy.best(List.of(point, mate)).orElseThrow().kind(),
                "同距离下在队友身上重生能立刻形成两人协同");
    }

    @Test
    void squadmatePreferenceIsSmallEnoughToLoseToRealDistance() {
        Option distantMate = opt(Kind.SQUADMATE, "mate", 100.0D, true);
        Option closePoint = opt(Kind.POINT, "1", 40.0D, true);
        assertEquals(Kind.POINT, RedeployPolicy.best(List.of(distantMate, closePoint)).orElseThrow().kind(),
                "类别偏好不该压过真实的路程差距");
    }

    @Test
    void baseIsPenalisedAgainstAnEquallyDistantPoint() {
        Option base = opt(Kind.BASE, "", 60.0D, true);
        Option point = opt(Kind.POINT, "1", 60.0D, true);
        assertEquals(Kind.POINT, RedeployPolicy.best(List.of(base, point)).orElseThrow().kind(),
                "从基地出发要穿过整段纵深，被拦截概率更高");
    }

    @Test
    void weightedDistanceOrderingMatchesConstants() {
        assertEquals(50.0D * RedeployPolicy.SQUADMATE_PREFERENCE,
                opt(Kind.SQUADMATE, "m", 50.0D, true).weightedDistance(), 1.0e-9D);
        assertEquals(50.0D, opt(Kind.POINT, "1", 50.0D, true).weightedDistance(), 1.0e-9D);
        assertEquals(50.0D * RedeployPolicy.BASE_PENALTY,
                opt(Kind.BASE, "", 50.0D, true).weightedDistance(), 1.0e-9D);
        assertTrue(RedeployPolicy.SQUADMATE_PREFERENCE < 1.0D);
        assertTrue(RedeployPolicy.BASE_PENALTY > 1.0D);
    }

    @Test
    void negativeDistanceIsClampedNotPropagated() {
        assertEquals(0.0D, opt(Kind.POINT, "1", -5.0D, true).weightedDistance(), 1.0e-9D);
    }

    @Test
    void noSafeOptionYieldsEmptySoCallerKeepsWaiting() {
        assertTrue(RedeployPolicy.best(List.of(opt(Kind.SQUADMATE, "m", 5.0D, false))).isEmpty());
        assertTrue(RedeployPolicy.best(List.of()).isEmpty());
    }

    // ---------------- 倒地放弃判定 ----------------

    @Test
    void givesUpWhenNobodyCanReach() {
        assertTrue(RedeployPolicy.shouldGiveUp(12, Double.MAX_VALUE), "无人能救就不该躺满 15 秒");
    }

    @Test
    void givesUpWhenRescueCannotFinishInTime() {
        assertTrue(RedeployPolicy.shouldGiveUp(4, 9.0D), "救援耗时超过剩余流血时间，扶也扶不完");
    }

    /**
     * 不能一倒地就放弃：那会让真人玩家的救援永远来不及，救援机制形同不存在。
     */
    @Test
    void waitsWhenRescueIsActuallyComingInTime() {
        assertFalse(RedeployPolicy.shouldGiveUp(14, 5.0D));
        assertFalse(RedeployPolicy.shouldGiveUp(6, 6.0D), "刚好赶上应当继续等");
    }

    @Test
    void alreadyExpiredAlwaysGivesUp() {
        assertTrue(RedeployPolicy.shouldGiveUp(0, 1.0D));
        assertTrue(RedeployPolicy.shouldGiveUp(-3, 1.0D));
    }
}
