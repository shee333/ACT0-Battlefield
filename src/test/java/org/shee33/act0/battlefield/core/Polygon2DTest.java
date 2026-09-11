package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁住不规则据点区域的几何算法：点在多边形内判定与耳切三角化。 */
class Polygon2DTest {

    private static double[] v(double x, double z) {
        return new double[]{x, z};
    }

    @Test
    void tooFewVerticesIsNotAPolygon() {
        assertNull(Polygon2D.of(null), "null 顶点应返回 null");
        assertNull(Polygon2D.of(List.of()), "空顶点应返回 null");
        assertNull(Polygon2D.of(List.of(v(0, 0), v(1, 1))), "2 个顶点不构成多边形");
    }

    @Test
    void squareContainsInsideOnly() {
        Polygon2D square = Polygon2D.of(List.of(v(0, 0), v(10, 0), v(10, 10), v(0, 10)));
        assertNotNull(square);
        assertTrue(square.contains(5, 5), "中心在正方形内");
        assertTrue(square.contains(1, 9), "靠近角的内点在正方形内");
        assertFalse(square.contains(11, 5), "右侧外部点不在正方形内");
        assertFalse(square.contains(-1, 5), "左侧外部点不在正方形内");
        assertFalse(square.contains(5, 11), "下侧外部点不在正方形内");
    }

    @Test
    void clockwiseVertexOrderStillWorks() {
        // 顺时针顶点（与逆时针相反）——判定与三角化都应正常。
        Polygon2D square = Polygon2D.of(List.of(v(0, 0), v(0, 10), v(10, 10), v(10, 0)));
        assertNotNull(square);
        assertTrue(square.contains(5, 5));
        assertFalse(square.contains(11, 5));
    }

    @Test
    void triangleContainsInside() {
        Polygon2D tri = Polygon2D.of(List.of(v(0, 0), v(10, 0), v(0, 10)));
        assertNotNull(tri);
        assertTrue(tri.contains(2, 2), "三角形内部");
        assertFalse(tri.contains(8, 8), "斜边外侧（0,0-10,0-0,10 之外）");
    }

    @Test
    void concavePolygonExcludesNotch() {
        // L 形：下横条 (0,0)-(4,2) + 左竖条 (0,0)-(2,4)，右上 (2..4, 2..4) 是凹口。
        Polygon2D lShape = Polygon2D.of(List.of(
                v(0, 0), v(4, 0), v(4, 2), v(2, 2), v(2, 4), v(0, 4)));
        assertNotNull(lShape);
        assertTrue(lShape.contains(1, 1), "下横条内");
        assertTrue(lShape.contains(3, 1), "下横条右侧内");
        assertTrue(lShape.contains(1, 3), "左竖条内");
        assertFalse(lShape.contains(3, 3), "右上凹口必须是外部（凹多边形的关键）");
        assertFalse(lShape.contains(5, 5), "整体外部");
    }

    @Test
    void boundsMatchVertices() {
        Polygon2D poly = Polygon2D.of(List.of(v(-3, 5), v(7, 5), v(7, 20), v(-3, 20)));
        assertNotNull(poly);
        assertEquals(-3.0, poly.minX(), 1e-9);
        assertEquals(7.0, poly.maxX(), 1e-9);
        assertEquals(5.0, poly.minZ(), 1e-9);
        assertEquals(20.0, poly.maxZ(), 1e-9);
    }

    @Test
    void convexQuadTriangulatesIntoTwoTriangles() {
        Polygon2D square = Polygon2D.of(List.of(v(0, 0), v(10, 0), v(10, 10), v(0, 10)));
        assertNotNull(square);
        int[] tris = square.triangulate();
        assertEquals(2 * 3, tris.length, "四边形应切出 2 个三角形（n-2）");
    }

    @Test
    void concavePolygonTriangulatesFully() {
        Polygon2D lShape = Polygon2D.of(List.of(
                v(0, 0), v(4, 0), v(4, 2), v(2, 2), v(2, 4), v(0, 4)));
        assertNotNull(lShape);
        int[] tris = lShape.triangulate();
        assertEquals(4 * 3, tris.length, "6 顶点凹多边形应切出 4 个三角形（n-2）");
        for (int idx : tris) {
            assertTrue(idx >= 0 && idx < 6, "三角形索引必须在顶点范围内");
        }
    }

    @Test
    void degenerateInputNeverThrows() {
        // 所有顶点共线（面积 0）——三角化尽力而为，不抛异常。
        Polygon2D line = Polygon2D.of(List.of(v(0, 0), v(1, 0), v(2, 0), v(3, 0)));
        assertNotNull(line);
        assertFalse(line.contains(1, 1), "共线退化多边形不含平面内点");
        assertEquals(0, line.triangulate().length % 3, "返回的索引数必须是 3 的倍数");
    }
}
