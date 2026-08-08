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

    @Test
    void withAlphaScalesOnlyAlphaChannel() {
        assertEquals(0x80FFFFFF, MinimapMath.withAlpha(0xFFFFFFFF, 0.502f));
        assertEquals(0x004A90D9, MinimapMath.withAlpha(MinimapMath.BLUE, 0f));
        assertEquals(0xFF4A90D9, MinimapMath.withAlpha(MinimapMath.BLUE, 1f));
        assertEquals(0xFF4A90D9, MinimapMath.withAlpha(MinimapMath.BLUE, 9f), "超范围 alpha 应被钳制");
    }
}
