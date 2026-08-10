package org.shee33.act0.battlefield.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinimapMathTest {

    /** 小地图北朝上，箭头基础形状指北：面北不转，面东顺时针 90°，依此类推。 */
    @Test
    void screenAngleMapsCardinalDirections() {
        assertEquals(0f, MinimapMath.screenAngleFor(180f), 0.001f, "面北(yaw=180)箭头应指上、不旋转");
        assertEquals(90f, MinimapMath.screenAngleFor(270f), 0.001f, "面东(yaw=270)箭头应指右");
        assertEquals(180f, MinimapMath.screenAngleFor(0f), 0.001f, "面南(yaw=0)箭头应指下");
        assertEquals(270f, MinimapMath.screenAngleFor(90f), 0.001f, "面西(yaw=90)箭头应指左");
    }

    /** MC 不保证 yaw 归一化，负值与超过一圈的值都必须落回 [0,360)。 */
    @Test
    void screenAngleNormalisesUnboundedYaw() {
        assertEquals(0f, MinimapMath.screenAngleFor(-180f), 0.001f);
        assertEquals(90f, MinimapMath.screenAngleFor(-90f), 0.001f);
        assertEquals(0f, MinimapMath.screenAngleFor(540f), 0.001f);
        float angle = MinimapMath.screenAngleFor(-3600f - 90f);
        assertTrue(angle >= 0f && angle < 360f, "归一化结果越界: " + angle);
    }

    /** +X 向右、-Z 向上（北）。 */
    @Test
    void projectionOrientation() {
        assertEquals(25, MinimapMath.offsetX(50.0, 0.5));
        assertEquals(-25, MinimapMath.offsetX(-50.0, 0.5));
        assertEquals(-25, MinimapMath.offsetY(50.0, 0.5), "+Z 是南，屏幕上应向下(负偏移向上，故为 -25)");
        assertEquals(25, MinimapMath.offsetY(-50.0, 0.5), "-Z 是北，屏幕上应向上");
    }

    @Test
    void boundsRejectMarkersOutsidePanel() {
        assertTrue(MinimapMath.withinBounds(50, 50, 0, 0, 100, 3));
        assertTrue(MinimapMath.withinBounds(3, 3, 0, 0, 100, 3), "恰好在内缩边界上应算可见");
        assertFalse(MinimapMath.withinBounds(2, 50, 0, 0, 100, 3));
        assertFalse(MinimapMath.withinBounds(50, 98, 0, 0, 100, 3));
    }

    /** 征服模式颜色相对查看者阵营翻转。 */
    @Test
    void conquestColorIsRelativeToViewer() {
        assertEquals(MinimapMath.BLUE, MinimapMath.conquestPointColor(2, 0, 2), "BRAVO 看自己的点是蓝");
        assertEquals(MinimapMath.RED, MinimapMath.conquestPointColor(2, 0, 1), "ALPHA 看 BRAVO 的点是红");
        assertEquals(MinimapMath.GREY, MinimapMath.conquestPointColor(0, 0, 1));
        assertEquals(MinimapMath.YELLOW, MinimapMath.conquestPointColor(1, 40, 1), "争夺中优先于归属色");
        assertEquals(MinimapMath.BLUE, MinimapMath.conquestPointColor(1, 0, 0), "观战者无阵营时 ALPHA 恒蓝");
    }

    /** 突破模式颜色是绝对的，不随查看者阵营翻转。 */
    @Test
    void breakthroughColorIsAbsolute() {
        assertEquals(MinimapMath.BLUE, MinimapMath.breakthroughPointColor(1, 0));
        assertEquals(MinimapMath.RED, MinimapMath.breakthroughPointColor(2, 0));
        assertEquals(MinimapMath.GREY, MinimapMath.breakthroughPointColor(0, 0));
        assertEquals(MinimapMath.YELLOW, MinimapMath.breakthroughPointColor(2, 60));
    }

    /** 倒地队友仍算 alive，配色必须以 downed 为准。 */
    @Test
    void squadMateColorPrefersDownedState() {
        assertEquals(MinimapMath.SQUAD_DOWNED, MinimapMath.squadMateColor(true));
        assertEquals(MinimapMath.SQUAD_ALIVE, MinimapMath.squadMateColor(false));
    }

    // ---------------- 大修新增：旋转模式与丝滑系统 ----------------

    /** 尺度以半径语义为准：面板多大，50 格都要正好铺满半宽。 */
    @Test
    void pixelsPerBlockKeepsViewRadiusConstant() {
        assertEquals(0.84, MinimapMath.pixelsPerBlock(84), 1e-9);
        assertEquals(1.28, MinimapMath.pixelsPerBlock(128), 1e-9);
        for (int size : new int[]{64, 84, 100, 128, 210}) {
            double half = MinimapMath.pixelsPerBlock(size) * MinimapMath.VIEW_R;
            assertEquals(size / 2.0, half, 1e-9, "size=" + size + " 时 50 格应恰好等于半宽");
        }
    }

    /** ±180° 边界不得绕远路：350°→10° 应往前走 20° 而不是倒退 340°。 */
    @Test
    void shortestArcTakesShortWayAroundWrap() {
        assertEquals(20f, MinimapMath.shortestArcStep(350f, 10f, 1f), 1e-3f);
        assertEquals(-20f, MinimapMath.shortestArcStep(10f, 350f, 1f), 1e-3f);
        assertEquals(10f, MinimapMath.shortestArcStep(0f, 10f, 1f), 1e-3f);
        assertEquals(0f, MinimapMath.shortestArcStep(90f, 90f, 1f), 1e-3f);
    }

    @Test
    void shortestArcStepScalesByFactor() {
        assertEquals(2f, MinimapMath.shortestArcStep(350f, 10f, 0.1f), 1e-3f);
        float step = MinimapMath.shortestArcStep(0f, 180f, 0.06f);
        assertTrue(Math.abs(step) <= 180f * 0.06f + 1e-3f, "单步不应超过差值×系数");
    }

    /** 追赶插值必须单调收敛,且永不越过目标。 */
    @Test
    void chaseConvergesWithoutOvershoot() {
        double shown = 0.0;
        for (int i = 0; i < 200; i++) {
            shown = MinimapMath.chase(shown, 10.0, 0.10);
            assertTrue(shown <= 10.0 + 1e-9, "追赶不应越过目标: " + shown);
        }
        assertEquals(10.0, shown, 1e-3);
    }

    /** 边缘 6 格线性渐隐,超出可视半径为 0(即"超界不画")。 */
    @Test
    void edgeFadeRampsInLastSixBlocks() {
        assertEquals(1f, MinimapMath.edgeFade(0, 50, 6), 1e-4f);
        assertEquals(1f, MinimapMath.edgeFade(44, 50, 6), 1e-4f);
        assertEquals(0.5f, MinimapMath.edgeFade(47, 50, 6), 1e-4f);
        assertEquals(0f, MinimapMath.edgeFade(50, 50, 6), 1e-4f, "恰好在可视半径上即不可见");
        assertEquals(0f, MinimapMath.edgeFade(80, 50, 6), 1e-4f);
    }

    /** 两种模式共用同一换算:北朝上只是 mapRot 恒 0,不需要特判分支。 */
    @Test
    void screenBearingUnifiesBothModes() {
        double world = Math.toRadians(30);
        assertEquals(world, MinimapMath.screenBearing(world, 0), 1e-9, "北朝上模式方位不偏移");
        assertEquals(world + Math.toRadians(90),
                MinimapMath.screenBearing(world, Math.toRadians(90)), 1e-9);
    }

    /** 世界方位:正北为 0,顺时针增长。 */
    @Test
    void worldBearingUsesNorthZeroClockwise() {
        assertEquals(0, MinimapMath.worldBearing(0, -10), 1e-9, "-Z 是正北");
        assertEquals(Math.PI / 2, MinimapMath.worldBearing(10, 0), 1e-9, "+X 是正东");
        assertEquals(Math.PI, Math.abs(MinimapMath.worldBearing(0, 10)), 1e-9, "+Z 是正南");
        assertEquals(-Math.PI / 2, MinimapMath.worldBearing(-10, 0), 1e-9, "-X 是正西");
    }

    /** 北朝上模式(mapRot=0)下投影应退化为纯平移缩放,与旧行为一致。 */
    @Test
    void projectWithoutRotationMatchesPlainOffset() {
        double s = MinimapMath.pixelsPerBlock(84);
        double[] p = MinimapMath.project(10, -20, 0, 0, 0, s, 42, 42);
        assertEquals(42 + 10 * s, p[0], 1e-9);
        assertEquals(42 - 20 * s, p[1], 1e-9);
    }

    /**
     * 旋转模式核心不变量：玩家正前方的目标，投影后必须落在面板中心的<b>正上方</b>。
     * 这是"前方朝上"的全部含义,错了整个旋转模式就是错的。
     */
    @Test
    void projectRotationPutsFacingTargetDirectlyAbove() {
        double s = MinimapMath.pixelsPerBlock(84);
        double cx = 42;
        double cy = 42;
        // 玩家面朝正东(yaw=270)，正东 20 格处有个目标
        float yaw = 270f;
        double mapRot = Math.toRadians(MinimapMath.mapRotationFor(yaw, false));
        double[] p = MinimapMath.project(20, 0, 0, 0, mapRot, s, cx, cy);
        assertEquals(cx, p[0], 1e-6, "正前方目标应在中心正上方(x 不偏)");
        assertTrue(p[1] < cy - 1, "正前方目标应在中心上方,实际 y=" + p[1]);
        assertEquals(20 * s, cy - p[1], 1e-6, "距离应被保留");
    }

    /** 旋转模式对四个基本朝向都要成立。 */
    @Test
    void projectRotationHoldsForAllCardinalFacings() {
        double s = MinimapMath.pixelsPerBlock(84);
        double cx = 42;
        double cy = 42;
        // {yaw, 该朝向正前方 20 格的世界坐标}
        double[][] cases = {
                {180, 0, -20},   // 面北 → 目标在 -Z
                {270, 20, 0},    // 面东 → 目标在 +X
                {0, 0, 20},      // 面南 → 目标在 +Z
                {90, -20, 0},    // 面西 → 目标在 -X
        };
        for (double[] c : cases) {
            double mapRot = Math.toRadians(MinimapMath.mapRotationFor((float) c[0], false));
            double[] p = MinimapMath.project(c[1], c[2], 0, 0, mapRot, s, cx, cy);
            assertEquals(cx, p[0], 1e-6, "yaw=" + c[0] + " 时前方目标 x 应居中");
            assertEquals(20 * s, cy - p[1], 1e-6, "yaw=" + c[0] + " 时前方目标应在正上方 20 格处");
        }
    }

    /** 北朝上模式的地图旋转角恒为 0。 */
    @Test
    void mapRotationIsZeroInNorthUpMode() {
        for (float yaw : new float[]{0f, 90f, 180f, 270f, -45f, 720f}) {
            assertEquals(0f, MinimapMath.mapRotationFor(yaw, true), 1e-6f);
        }
    }

    /** 极坐标落点:正北在中心正上方,正东在正右方。 */
    @Test
    void polarPlacesBearingsCorrectly() {
        double[] north = MinimapMath.polar(0, 10, 42, 42);
        assertEquals(42, north[0], 1e-9);
        assertEquals(32, north[1], 1e-9);
        double[] east = MinimapMath.polar(Math.PI / 2, 10, 42, 42);
        assertEquals(52, east[0], 1e-9);
        assertEquals(42, east[1], 1e-9);
    }

    @Test
    void withAlphaScalesOnlyAlphaChannel() {
        assertEquals(0x80FFFFFF, MinimapMath.withAlpha(0xFFFFFFFF, 0.502f));
        assertEquals(0x004A90D9, MinimapMath.withAlpha(MinimapMath.BLUE, 0f));
        assertEquals(0xFF4A90D9, MinimapMath.withAlpha(MinimapMath.BLUE, 1f));
        assertEquals(0xFF4A90D9, MinimapMath.withAlpha(MinimapMath.BLUE, 9f), "超范围 alpha 应被钳制");
    }
}
