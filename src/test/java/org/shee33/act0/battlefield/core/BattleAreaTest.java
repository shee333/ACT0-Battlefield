package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleAreaTest {

    @Test
    void emptyByDefault() {
        assertTrue(BattleArea.EMPTY.isEmpty());
        assertFalse(BattleArea.EMPTY.isSet());
    }

    @Test
    void requiresMaxGreaterThanMin() {
        BattleArea a = new BattleArea(0, 0, 0, 10, 10, 10);
        assertTrue(a.isSet());
        assertTrue(a.contains(5, 5, 5));
        assertFalse(a.contains(-1, 5, 5));
    }

    @Test
    void containsIncludesBoundaries() {
        BattleArea a = new BattleArea(0, 0, 0, 10, 10, 10);
        assertTrue(a.contains(0, 0, 0));
        assertTrue(a.contains(10, 10, 10));
    }

    @Test
    void deriveExpandsByPadding() {
        List<double[]> pts = List.of(
                new double[]{0, 64, 0},
                new double[]{100, 70, 50});
        BattleArea a = BattleArea.derive(pts, 16.0);
        assertEquals(-16.0, a.minX(), 1e-9);
        assertEquals(-16.0, a.minZ(), 1e-9);
        assertEquals(116.0, a.maxX(), 1e-9);
        assertEquals(66.0, a.maxZ(), 1e-9);
        assertEquals(48.0, a.minY(), 1e-9);
        assertEquals(86.0, a.maxY(), 1e-9);
    }

    @Test
    void deriveFromEmptyReturnsEmpty() {
        assertTrue(BattleArea.derive(List.of(), 16.0).isEmpty());
        assertTrue(BattleArea.derive(null, 16.0).isEmpty());
    }

    @Test
    void dimensions() {
        BattleArea a = new BattleArea(-10, 0, -5, 10, 64, 15);
        assertEquals(20.0, a.sizeX(), 1e-9);
        assertEquals(64.0, a.sizeY(), 1e-9);
        assertEquals(20.0, a.sizeZ(), 1e-9);
        assertEquals(0.0, a.centerX(), 1e-9);
        assertEquals(5.0, a.centerZ(), 1e-9);
    }

    @Test
    void rejectsNaN() {
        try {
            new BattleArea(Double.NaN, 0, 0, 1, 1, 1);
            assert false : "should have thrown";
        } catch (IllegalArgumentException ignored) {
            // expected
        }
    }

    @Test
    void nullBoundaryFallsBackToRectangle() {
        BattleArea a = new BattleArea(0, 0, 0, 100, 64, 100);
        assertTrue(a.contains(50, 30, 50, null));
        assertFalse(a.contains(-1, 30, 50, null));
    }

    @Test
    void polygonBoundaryNarrowsXzJudgement() {
        BattleArea a = new BattleArea(0, 0, 0, 100, 64, 100);
        Polygon2D tri = Polygon2D.of(List.of(
                new double[]{0, 0},
                new double[]{100, 0},
                new double[]{0, 100}));
        assertTrue(a.contains(10, 30, 10, tri), "三角形内侧应判在内");
        assertTrue(a.contains(90, 30, 90), "矩形判定本身认为右上角在内");
        assertFalse(a.contains(90, 30, 90, tri), "有边界时必须按多边形判为在外");
    }

    @Test
    void verticalRangeStillAppliesWithBoundary() {
        BattleArea a = new BattleArea(0, 0, 0, 100, 64, 100);
        Polygon2D tri = Polygon2D.of(List.of(
                new double[]{0, 0},
                new double[]{100, 0},
                new double[]{0, 100}));
        assertFalse(a.contains(10, 100, 10, tri), "超出垂直范围应判在外");
        assertFalse(a.contains(10, -1, 10, tri));
    }

    @Test
    void boundaryOutsideRectangleStillCounts() {
        // 多边形完全落在矩形 XZ 之外：有边界时 XZ 只认多边形，矩形仅提供垂直范围。
        BattleArea a = new BattleArea(0, 0, 0, 10, 64, 10);
        Polygon2D poly = Polygon2D.of(List.of(
                new double[]{200, 200},
                new double[]{300, 200},
                new double[]{250, 300}));
        assertTrue(a.contains(250, 30, 200 + 100.0 / 3.0, poly), "多边形内、矩形 XZ 外也应算在内");
        assertFalse(a.contains(250, 30, 305, poly), "多边形之外（z 超过顶点）应判在外");
    }
}