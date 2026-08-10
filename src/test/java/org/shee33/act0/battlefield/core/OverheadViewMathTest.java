package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住"部署俯瞰相机与 2D 缩略图必须同向"这条不变量。
 *
 * <p>这个 bug 报了三次才找对地方：前两次分别怀疑标记漂移动效和战斗区域不含据点，都改错了位置，
 * 因为投影数学从头到尾都是对的——真正的错配在相机 yaw 上，而相机与面板分别写在两个互不知情的
 * 文件里，没有任何一处代码或测试表达过"它们必须一致"。这组测试就是那个缺失的表达。
 */
class OverheadViewMathTest {

    private static final double EPS = 1.0e-9D;

    @Test
    void yawFollowsMinecraftCompassConvention() {
        assertEquals(1.0D, OverheadViewMath.screenUpDirection(0f)[1], EPS, "yaw=0 朝南(+Z)");
        assertEquals(-1.0D, OverheadViewMath.screenUpDirection(90f)[0], EPS, "yaw=90 朝西(-X)");
        assertEquals(-1.0D, OverheadViewMath.screenUpDirection(180f)[1], EPS, "yaw=180 朝北(-Z)");
        assertEquals(1.0D, OverheadViewMath.screenUpDirection(270f)[0], EPS, "yaw=270 朝东(+X)");
    }

    /** 面板按世界 X 增大向右、Z 增大向下绘制，即北上东右；相机必须给出同一套轴向。 */
    @Test
    void northUpYawGivesNorthUpAndEastRight() {
        double[] up = OverheadViewMath.screenUpDirection(OverheadViewMath.NORTH_UP_YAW);
        double[] right = OverheadViewMath.screenRightDirection(OverheadViewMath.NORTH_UP_YAW);
        assertEquals(0.0D, up[0], EPS);
        assertEquals(-1.0D, up[1], EPS, "屏幕上方必须是北(-Z)");
        assertEquals(1.0D, right[0], EPS, "屏幕右方必须是东(+X)");
        assertEquals(0.0D, right[1], EPS);
        assertTrue(OverheadViewMath.matchesNorthUpEastRight(OverheadViewMath.NORTH_UP_YAW));
    }

    /**
     * 回归用例：这正是线上表现出来的错误配置。
     *
     * <p>yaw=0 时屏幕上方是南、右方是西，与面板的北上东右在<b>两个轴上同时相反</b>——
     * 玩家看到的就是"整张图转了 180°"，而不是某一轴的镜像。
     */
    @Test
    void yawZeroIsExactlyOneHundredEightyDegreesOffFromThePanel() {
        double[] up = OverheadViewMath.screenUpDirection(0f);
        double[] right = OverheadViewMath.screenRightDirection(0f);
        assertEquals(1.0D, up[1], EPS, "屏幕上方是南——与面板的北相反");
        assertEquals(-1.0D, right[0], EPS, "屏幕右方是西——与面板的东相反");
        assertFalse(OverheadViewMath.matchesNorthUpEastRight(0f));

        double[] good = OverheadViewMath.screenUpDirection(OverheadViewMath.NORTH_UP_YAW);
        double[] goodRight = OverheadViewMath.screenRightDirection(OverheadViewMath.NORTH_UP_YAW);
        assertEquals(-good[0], up[0], EPS);
        assertEquals(-good[1], up[1], EPS);
        assertEquals(-goodRight[0], right[0], EPS);
        assertEquals(-goodRight[1], right[1], EPS);
    }

    /** 只有朝北这一个 yaw 能满足北上东右，避免"改成 90 或 270 好像也差不多"的误修。 */
    @Test
    void onlyNorthFacingSatisfiesThePanelConvention() {
        for (float yaw = 0f; yaw < 360f; yaw += 90f) {
            boolean expected = Math.abs(yaw - OverheadViewMath.NORTH_UP_YAW) < 0.5f;
            assertEquals(expected, OverheadViewMath.matchesNorthUpEastRight(yaw),
                    "yaw=" + yaw + " 的判定不符预期");
        }
    }

    @Test
    void screenAxesAreAlwaysPerpendicularUnitVectors() {
        for (float yaw = -360f; yaw <= 360f; yaw += 17f) {
            double[] up = OverheadViewMath.screenUpDirection(yaw);
            double[] right = OverheadViewMath.screenRightDirection(yaw);
            assertEquals(1.0D, Math.hypot(up[0], up[1]), 1e-9, "上方向必须是单位向量");
            assertEquals(1.0D, Math.hypot(right[0], right[1]), 1e-9, "右方向必须是单位向量");
            assertEquals(0.0D, up[0] * right[0] + up[1] * right[1], 1e-9, "两轴必须垂直");
        }
    }
}
