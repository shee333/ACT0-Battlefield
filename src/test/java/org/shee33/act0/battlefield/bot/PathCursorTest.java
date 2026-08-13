package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径跟随游标（MC-free）单元测试：节点推进、前瞻跳过、卡死计数与恢复。
 */
class PathCursorTest {

    /** 沿 +X 一字排开的 5 个节点，y 恒定。 */
    private static PathCursor straightLine() {
        return new PathCursor(
                new double[]{0.5, 1.5, 2.5, 3.5, 4.5},
                new double[]{0, 0, 0, 0, 0},
                new double[]{0.5, 0.5, 0.5, 0.5, 0.5},
                0.8D, true);
    }

    @Test
    void rejectsMismatchedArraysAndNonPositiveRadius() {
        assertThrows(IllegalArgumentException.class, () -> new PathCursor(
                new double[]{0}, new double[]{0, 1}, new double[]{0}, 0.8D, true));
        assertThrows(IllegalArgumentException.class, () -> new PathCursor(
                new double[]{0}, new double[]{0}, new double[]{0}, 0.0D, true));
    }

    @Test
    void startsAtFirstNodeAndIsNotFinished() {
        PathCursor cursor = straightLine();
        assertEquals(0, cursor.index());
        assertEquals(5, cursor.nodeCount());
        assertFalse(cursor.isFinished());
        assertTrue(cursor.reachesGoal());
        assertEquals(0.5D, cursor.targetX(), 1.0e-9D);
    }

    @Test
    void advancesPastNodeOnceWithinArrivalRadius() {
        PathCursor cursor = straightLine();
        assertFalse(cursor.advance(0.5, 0, 0.5), "站在首节点上应推进但未走完");
        assertEquals(1, cursor.index());
        assertEquals(1.5D, cursor.targetX(), 1.0e-9D);
    }

    @Test
    void lookAheadSkipsIntermediateNodesAlreadyReached() {
        PathCursor cursor = straightLine();
        // 站在第 3 个节点（索引 2）上：前瞻应直接跳到索引 3，而非逐个推进
        cursor.advance(2.5, 0, 0.5);
        assertEquals(3, cursor.index(), "应跳过已抵达的中间节点");
    }

    @Test
    void reportsFinishedAfterPassingLastNode() {
        PathCursor cursor = straightLine();
        assertTrue(cursor.advance(4.5, 0, 0.5), "站在末节点上应判定走完");
        assertTrue(cursor.isFinished());
    }

    @Test
    void verticalToleranceRejectsNodesTooFarAbove() {
        PathCursor cursor = straightLine();
        // 水平已到首节点但高出 5 格（如站在楼上）：不应算抵达
        assertFalse(cursor.advance(0.5, 5.0, 0.5));
        assertEquals(0, cursor.index(), "垂直差超出容差不应推进");
    }

    @Test
    void verticalToleranceAcceptsOneStepHeightDifference() {
        PathCursor cursor = straightLine();
        cursor.advance(0.5, 1.0, 0.5);
        assertEquals(1, cursor.index(), "一级台阶的高度差应仍算抵达");
    }

    @Test
    void stuckCounterGrowsWhenDistanceStopsShrinking() {
        PathCursor cursor = straightLine();
        // 停在同一处不动，且离首节点尚有距离
        for (int i = 0; i < 5; i++) {
            cursor.advance(-3.0, 0, 0.5);
        }
        assertTrue(cursor.ticksWithoutProgress() >= 4,
                "原地不动应累计无进展 tick，实际 " + cursor.ticksWithoutProgress());
    }

    @Test
    void stuckCounterResetsWhileApproaching() {
        PathCursor cursor = straightLine();
        cursor.advance(-5.0, 0, 0.5);
        cursor.advance(-4.0, 0, 0.5);
        cursor.advance(-3.0, 0, 0.5);
        assertEquals(0, cursor.ticksWithoutProgress(), "持续靠近时不应累计卡死");
    }

    @Test
    void stepBackRetreatsOneNodeAndClearsProgress() {
        PathCursor cursor = straightLine();
        cursor.advance(2.5, 0, 0.5);
        int before = cursor.index();
        for (int i = 0; i < 3; i++) {
            cursor.advance(-9.0, 0, 0.5);
        }
        assertTrue(cursor.ticksWithoutProgress() > 0);

        cursor.stepBack();
        assertEquals(before - 1, cursor.index());
        assertEquals(0, cursor.ticksWithoutProgress(), "退回节点后应清空无进展计数");
    }

    @Test
    void stepBackAtStartStaysAtStart() {
        PathCursor cursor = straightLine();
        cursor.stepBack();
        assertEquals(0, cursor.index());
    }

    @Test
    void targetClampsToLastNodeAfterFinishing() {
        PathCursor cursor = straightLine();
        cursor.advance(4.5, 0, 0.5);
        assertTrue(cursor.isFinished());
        assertEquals(4.5D, cursor.targetX(), 1.0e-9D, "走完后取值应钳制到末节点而非越界");
    }

    @Test
    void partialPathIsReportedAsNotReachingGoal() {
        PathCursor partial = new PathCursor(
                new double[]{0.5}, new double[]{0}, new double[]{0.5}, 0.8D, false);
        assertFalse(partial.reachesGoal(), "原版判定为尽力靠近的部分路径应如实透出");
    }
}
