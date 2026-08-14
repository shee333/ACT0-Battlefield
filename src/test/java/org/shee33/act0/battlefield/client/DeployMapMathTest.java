package org.shee33.act0.battlefield.client;

import com.mojang.math.Axis;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployMapMathTest {

    private static final float EPS = 0.05f;

    // ---------------- fittedRect ----------------

    @Test
    void squareAreaInSquareBoxFillsExactly() {
        float[] r = DeployMapMath.fittedRect(0, 0, 100, 100, 0, 0, 200, 200);
        assertEquals(0f, r[0], EPS);
        assertEquals(0f, r[1], EPS);
        assertEquals(200f, r[2], EPS);
        assertEquals(200f, r[3], EPS);
    }

    @Test
    void wideAreaInSquareBoxLetterboxesTopBottom() {
        // 区域 200x100(2:1),放进 100x100 的正方形框 → 应受宽度限制,高度留白居中。
        float[] r = DeployMapMath.fittedRect(0, 0, 200, 100, 0, 0, 100, 100);
        assertEquals(100f, r[2], EPS, "宽度应撑满 box 宽");
        assertEquals(50f, r[3], EPS, "高度按等比缩放应为一半");
        assertEquals(0f, r[0], EPS, "宽度撑满,X 不应留白");
        assertEquals(25f, r[1], EPS, "高度居中留白 25 上 25 下");
    }

    @Test
    void tallAreaInWideBoxLetterboxesLeftRight() {
        // 区域 100x200(1:2),放进 200x100 的宽框 → 受高度限制,宽度留白居中。
        float[] r = DeployMapMath.fittedRect(0, 0, 100, 200, 0, 0, 200, 100);
        assertEquals(100f, r[3], EPS, "高度应撑满 box 高");
        assertEquals(50f, r[2], EPS, "宽度按等比缩放应为一半");
        assertEquals(0f, r[1], EPS, "高度撑满,Y 不应留白");
        assertEquals(75f, r[0], EPS, "宽度居中留白 75 左 75 右");
    }

    @Test
    void boxOffsetIsRespected() {
        float[] r = DeployMapMath.fittedRect(0, 0, 100, 100, 10, 20, 100, 100);
        assertEquals(10f, r[0], EPS);
        assertEquals(20f, r[1], EPS);
    }

    @Test
    void degenerateZeroSpanAreaDoesNotCrashOrProduceNaN() {
        // 单点区域(minX==maxX, minZ==maxZ):不应抛异常/产生 NaN/Infinite。
        float[] r = DeployMapMath.fittedRect(50, 50, 50, 50, 0, 0, 200, 200);
        for (float v : r) {
            assertTrue(Float.isFinite(v), "退化区域不应产生非有限值: " + v);
        }
    }

    // ---------------- project ----------------

    @Test
    void projectsMinCornerToRectOrigin() {
        float[] r = DeployMapMath.fittedRect(0, 0, 100, 100, 0, 0, 200, 200);
        float[] p = DeployMapMath.project(0, 0, 0, 0, 100, 100, 0, 0, 200, 200);
        assertEquals(r[0], p[0], EPS);
        assertEquals(r[1], p[1], EPS);
    }

    @Test
    void projectsMaxCornerToRectFarCorner() {
        float[] r = DeployMapMath.fittedRect(0, 0, 100, 100, 0, 0, 200, 200);
        float[] p = DeployMapMath.project(100, 100, 0, 0, 100, 100, 0, 0, 200, 200);
        assertEquals(r[0] + r[2], p[0], EPS);
        assertEquals(r[1] + r[3], p[1], EPS);
    }

    @Test
    void projectsCenterToRectCenter() {
        float[] r = DeployMapMath.fittedRect(0, 0, 200, 100, 0, 0, 100, 100);
        float[] p = DeployMapMath.project(100, 50, 0, 0, 200, 100, 0, 0, 100, 100);
        assertEquals(r[0] + r[2] / 2f, p[0], EPS);
        assertEquals(r[1] + r[3] / 2f, p[1], EPS);
    }

    @Test
    void largerWorldXMapsFurtherRight() {
        float[] p1 = DeployMapMath.project(10, 50, 0, 0, 100, 100, 0, 0, 100, 100);
        float[] p2 = DeployMapMath.project(90, 50, 0, 0, 100, 100, 0, 0, 100, 100);
        assertTrue(p2[0] > p1[0], "更大的世界 X(更东)应映射到更右的屏幕 X");
    }

    @Test
    void largerWorldZMapsFurtherDown() {
        float[] p1 = DeployMapMath.project(50, 10, 0, 0, 100, 100, 0, 0, 100, 100);
        float[] p2 = DeployMapMath.project(50, 90, 0, 0, 100, 100, 0, 0, 100, 100);
        assertTrue(p2[1] > p1[1], "更大的世界 Z(更南)应映射到更下的屏幕 Y(北上南下惯例)");
    }

    @Test
    void projectOnAlreadyFittedRectIsIdempotent() {
        float[] r = DeployMapMath.fittedRect(0, 0, 200, 100, 5, 5, 100, 100);
        float[] direct = DeployMapMath.project(150, 75, 0, 0, 200, 100, 5, 5, 100, 100);
        float[] viaFitted = DeployMapMath.project(150, 75, 0, 0, 200, 100, r[0], r[1], r[2], r[3]);
        assertEquals(direct[0], viaFitted[0], EPS);
        assertEquals(direct[1], viaFitted[1], EPS);
    }

    // ---------------- pulsePhase ----------------

    @Test
    void pulsePhaseAtZeroIsHalf() {
        assertEquals(0.5f, DeployMapMath.pulsePhase(0L), EPS);
    }

    @Test
    void pulsePhaseStaysWithinUnitRange() {
        for (long t = 0; t < 10000; t += 91) {
            float p = DeployMapMath.pulsePhase(t);
            assertTrue(p >= 0f && p <= 1f, "脉冲相位应恒在[0,1]: " + p);
        }
    }

    // ---------------- edgeFadeAlpha ----------------

    @Test
    void edgeFadeAlphaZeroAtBothEnds() {
        assertEquals(0f, DeployMapMath.edgeFadeAlpha(0f), EPS);
        assertEquals(0f, DeployMapMath.edgeFadeAlpha(1f), EPS);
    }

    @Test
    void edgeFadeAlphaPeaksAtMidpoint() {
        assertEquals(0.65f, DeployMapMath.edgeFadeAlpha(0.5f), EPS);
    }

    @Test
    void edgeFadeAlphaMonotonicRiseThenFall() {
        float a0 = DeployMapMath.edgeFadeAlpha(0.1f);
        float a1 = DeployMapMath.edgeFadeAlpha(0.3f);
        float a2 = DeployMapMath.edgeFadeAlpha(0.5f);
        float a3 = DeployMapMath.edgeFadeAlpha(0.7f);
        float a4 = DeployMapMath.edgeFadeAlpha(0.9f);
        assertTrue(a0 < a1 && a1 < a2, "前半段应单调递增");
        assertTrue(a2 > a3 && a3 > a4, "后半段应单调递减");
    }

    @Test
    void edgeFadeAlphaClampsOutOfRangeInput() {
        assertEquals(0f, DeployMapMath.edgeFadeAlpha(-1f), EPS);
        assertEquals(0f, DeployMapMath.edgeFadeAlpha(2f), EPS);
    }

    // ---------------- insideRect (P0修复:地图标记裁剪/命中边界判断) ----------------

    @Test
    void insideRectAcceptsPointWithinBounds() {
        assertTrue(DeployMapMath.insideRect(50f, 50f, 0f, 0f, 100f, 100f));
    }

    @Test
    void insideRectAcceptsBoundaryPoints() {
        assertTrue(DeployMapMath.insideRect(0f, 0f, 0f, 0f, 100f, 100f), "左上边界应视为范围内");
        assertTrue(DeployMapMath.insideRect(100f, 100f, 0f, 0f, 100f, 100f), "右下边界应视为范围内");
    }

    @Test
    void insideRectRejectsPointOutsideEachDirection() {
        assertFalse(DeployMapMath.insideRect(-1f, 50f, 0f, 0f, 100f, 100f), "左侧越界应被拒绝");
        assertFalse(DeployMapMath.insideRect(101f, 50f, 0f, 0f, 100f, 100f), "右侧越界应被拒绝");
        assertFalse(DeployMapMath.insideRect(50f, -1f, 0f, 0f, 100f, 100f), "上方越界应被拒绝");
        assertFalse(DeployMapMath.insideRect(50f, 101f, 0f, 0f, 100f, 100f), "下方越界应被拒绝");
    }

    @Test
    void insideRectRejectsPointFarOutsideLikeAllyPastAreaBoundary() {
        // 复现场景:队友跑到战斗区域AABB之外约40格,letterbox内框对应的越界坐标应被拒绝命中。
        float[] r = DeployMapMath.fittedRect(0, 0, 100, 100, 0, 0, 100, 100);
        assertFalse(DeployMapMath.insideRect(r[0] + r[2] + 40f, r[1] + 10f, r[0], r[1], r[2], r[3]));
    }

    // ---------------- facingScreenDegrees ----------------

    @Test
    void facingNorthNeedsNoRotationBecauseMapIsNorthUp() {
        assertEquals(0f, DeployMapMath.facingScreenDegrees(180f), EPS, "yaw 180 是正北,北朝上故标记不旋转");
    }

    @Test
    void facingSouthPointsDown() {
        assertEquals(180f, DeployMapMath.facingScreenDegrees(0f), EPS, "yaw 0 是正南,应指向屏幕下方");
    }

    @Test
    void facingEastPointsRight() {
        assertEquals(90f, DeployMapMath.facingScreenDegrees(270f), EPS, "yaw 270 是正东,东在右(屏幕顺时针 90)");
    }

    @Test
    void facingWestPointsLeft() {
        assertEquals(270f, DeployMapMath.facingScreenDegrees(90f), EPS, "yaw 90 是正西,西在左");
    }

    @Test
    void facingNormalisesNegativeAndOverfullTurns() {
        // MC 的 getYRot() 不归一化到 [0,360):长期转视角会累积成任意大小的角,含负值。
        for (float yaw : new float[]{-450f, -90f, 270f, 630f}) {
            float deg = DeployMapMath.facingScreenDegrees(yaw);
            assertTrue(deg >= 0f && deg < 360f, "yaw=" + yaw + " 应归一化到 [0,360),实得 " + deg);
            assertEquals(90f, deg, EPS, "yaw=" + yaw + " 全部等价于正东");
        }
    }

    // ---------------- facingScreenDegrees × Axis.ZP 的实际旋转方向 ----------------
    //
    // facingScreenDegrees 的正确性依赖一个它自己测不到的前提:Axis.ZP 的正向旋转在屏幕上
    // 究竟是顺时针还是逆时针。前提若反了,上面四个方向测试会连同实现一起错——它们锁的只是
    // "我推导的那套约定"自洽,不是它与渲染管线一致。以下用真实的 Axis.ZP 四元数作用在
    // DeployMapPanel 实际绘制的三角顶点(局部朝上,y=-4)上,直接断言它落在屏幕的哪一侧。

    private static Vector3f rotatedApex(float yawDegrees) {
        Vector3f apex = new Vector3f(0f, -4f, 0f);
        apex.rotate(Axis.ZP.rotationDegrees(DeployMapMath.facingScreenDegrees(yawDegrees)));
        return apex;
    }

    @Test
    void apexPointsUpWhenFacingNorth() {
        Vector3f a = rotatedApex(180f);
        assertEquals(0f, a.x(), EPS);
        assertEquals(-4f, a.y(), EPS, "正北时三角顶点应在中心上方(屏幕 y 更小)");
    }

    @Test
    void apexPointsDownWhenFacingSouth() {
        Vector3f a = rotatedApex(0f);
        assertEquals(0f, a.x(), EPS);
        assertEquals(4f, a.y(), EPS, "正南时三角顶点应在中心下方");
    }

    @Test
    void apexPointsRightWhenFacingEast() {
        Vector3f a = rotatedApex(270f);
        assertEquals(4f, a.x(), EPS, "正东时三角顶点应在中心右侧——若 Axis.ZP 方向反了这里会得 -4");
        assertEquals(0f, a.y(), EPS);
    }

    @Test
    void apexPointsLeftWhenFacingWest() {
        Vector3f a = rotatedApex(90f);
        assertEquals(-4f, a.x(), EPS, "正西时三角顶点应在中心左侧");
        assertEquals(0f, a.y(), EPS);
    }
}
