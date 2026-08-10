package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住"地图视图区域必须包含所有要画的点"这条不变量。
 *
 * <p>缩略地图把世界坐标按区域 AABB 线性映射进面板。一旦有据点落在区域<b>之外</b>，它的投影
 * 就会溢出可视矩形，看起来就是"据点位置乱、和实际地图对不上"——而显式设定的战斗区域完全
 * 不保证包含所有据点（先划区域再挪据点即可复现）。因此视图区域取并集。
 */
class BattleAreaUnionTest {

    private static BattleArea pointsOf(double... xz) {
        List<double[]> pts = new java.util.ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) {
            pts.add(new double[]{xz[i], 64, xz[i + 1]});
        }
        return BattleArea.derive(pts, 16.0);
    }

    @Test
    void unionCoversPointsOutsideExplicitArea() {
        BattleArea explicit = new BattleArea(0, 0, 0, 100, 128, 100);
        BattleArea points = pointsOf(500, 500);
        BattleArea view = explicit.union(points);

        assertTrue(view.contains(500, 64, 500), "区域外的据点必须落进视图区域");
        assertTrue(view.contains(50, 64, 50), "原显式区域内的位置也仍在视图区域内");
    }

    @Test
    void unionKeepsExplicitAreaWhenItAlreadyCoversEverything() {
        BattleArea explicit = new BattleArea(-1000, -64, -1000, 1000, 320, 1000);
        BattleArea view = explicit.union(pointsOf(10, 20, -30, 40));
        assertTrue(view.contains(-1000, 0, -1000));
        assertTrue(view.contains(1000, 0, 1000));
    }

    @Test
    void unionWithEmptyReturnsTheOtherSide() {
        BattleArea real = new BattleArea(0, 0, 0, 10, 10, 10);
        assertSame(real, real.union(BattleArea.EMPTY), "与空区域取并集应原样返回自己");
        assertSame(real, real.union(null), "null 视为空区域");
        assertSame(real, BattleArea.EMPTY.union(real), "自己为空则返回对方");
    }

    /** 并集只能变大，绝不能把任何一侧的范围裁掉。 */
    @Test
    void unionNeverShrinksEitherSide() {
        BattleArea a = new BattleArea(-50, 0, -20, 10, 100, 30);
        BattleArea b = new BattleArea(-5, 10, -80, 200, 60, 5);
        BattleArea u = a.union(b);
        for (BattleArea side : new BattleArea[]{a, b}) {
            assertTrue(u.contains(side.minX(), side.minY(), side.minZ()),
                    "并集丢掉了某一侧的下界");
            assertTrue(u.contains(side.maxX(), side.maxY(), side.maxZ()),
                    "并集丢掉了某一侧的上界");
        }
    }
}
