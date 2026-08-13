package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 基础层（MC-free）单元测试：{@link BotNames} 身份派生 + {@link Steering} 朝向解算。
 * 按包主题分组，风格参照 {@code ArcadeArenaTest}。
 */
class BotFoundationTest {

    private static final float EPS = 1.0e-4F;

    // ---------------- BotNames ----------------

    @Test
    void poolSizeMatchesDeclaredConstant() {
        assertEquals(BotNames.POOL_SIZE, BotNames.size());
        assertEquals(BotNames.POOL_SIZE, BotNames.pool().size());
    }

    @Test
    void poolNamesAreUniqueAndLegalProfileNames() {
        Set<String> seen = new HashSet<>();
        for (String name : BotNames.pool()) {
            assertTrue(seen.add(name.toLowerCase()), "名池出现重复名：" + name);
            assertTrue(name.length() >= 3 && name.length() <= 16, "名字长度越界：" + name);
            assertTrue(name.matches("[A-Za-z0-9_]+"), "名字含非法字符：" + name);
        }
    }

    @Test
    void uuidDerivationIsStableAcrossCalls() {
        UUID first = BotNames.uuidOf("Novak");
        UUID second = BotNames.uuidOf("Novak");
        assertEquals(first, second);
    }

    @Test
    void differentNamesDeriveDifferentUuids() {
        assertNotEquals(BotNames.uuidOf("Novak"), BotNames.uuidOf("Ivanov"));
    }

    @Test
    void botUuidDoesNotCollideWithVanillaOfflinePlayerUuid() {
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:Novak").getBytes(StandardCharsets.UTF_8));
        assertNotEquals(offline, BotNames.uuidOf("Novak"),
                "bot UUID 必须与原版离线玩家命名空间区隔，否则同名真人会被顶掉");
    }

    @Test
    void uuidOfRejectsNull() {
        assertThrows(NullPointerException.class, () -> BotNames.uuidOf(null));
    }

    @Test
    void atWrapsAroundInBothDirections() {
        assertEquals(BotNames.at(0), BotNames.at(BotNames.size()));
        assertEquals(BotNames.at(BotNames.size() - 1), BotNames.at(-1));
    }

    @Test
    void pickReturnsRequestedCountOfDistinctNames() {
        List<String> picked = BotNames.pick(5, Set.of(), 42L);
        assertEquals(5, picked.size());
        assertEquals(5, new HashSet<>(picked).size());
    }

    @Test
    void pickSkipsTakenNamesCaseInsensitively() {
        String taken = BotNames.at(0);
        List<String> picked = BotNames.pick(BotNames.size(), Set.of(taken.toUpperCase()), 7L);
        assertFalse(picked.stream().anyMatch(n -> n.equalsIgnoreCase(taken)),
                "已占用名必须被跳过，否则 tab 列表与名牌会重名");
        assertEquals(BotNames.size() - 1, picked.size());
    }

    @Test
    void pickIsDeterministicForSameSeed() {
        assertEquals(BotNames.pick(6, Set.of(), 1234L), BotNames.pick(6, Set.of(), 1234L));
    }

    @Test
    void pickReturnsEmptyForNonPositiveCountAndCapsAtPoolSize() {
        assertTrue(BotNames.pick(0, Set.of(), 1L).isEmpty());
        assertTrue(BotNames.pick(-3, Set.of(), 1L).isEmpty());
        assertEquals(BotNames.size(), BotNames.pick(BotNames.size() + 10, null, 1L).size());
    }

    // ---------------- Steering ----------------

    @Test
    void wrapDegreesNormalisesToHalfOpenRange() {
        assertEquals(0.0F, Steering.wrapDegrees(360.0F), EPS);
        assertEquals(-180.0F, Steering.wrapDegrees(180.0F), EPS);
        assertEquals(-180.0F, Steering.wrapDegrees(-180.0F), EPS);
        assertEquals(-90.0F, Steering.wrapDegrees(270.0F), EPS);
        assertEquals(90.0F, Steering.wrapDegrees(-270.0F), EPS);
    }

    @Test
    void yawTowardMatchesMinecraftAxisConvention() {
        assertEquals(0.0F, Steering.yawToward(0, 1), EPS);      // +Z
        assertEquals(-90.0F, Steering.yawToward(1, 0), EPS);    // +X
        assertEquals(-180.0F, Steering.yawToward(0, -1), EPS);  // -Z
        assertEquals(90.0F, Steering.yawToward(-1, 0), EPS);    // -X
    }

    @Test
    void pitchTowardIsNegativeWhenLookingUp() {
        assertTrue(Steering.pitchToward(5, 5) < 0.0F);
        assertTrue(Steering.pitchToward(-5, 5) > 0.0F);
        assertEquals(0.0F, Steering.pitchToward(0, 5), EPS);
        assertEquals(-45.0F, Steering.pitchToward(5, 5), EPS);
    }

    @Test
    void pitchTowardHandlesZeroHorizontalDistance() {
        assertEquals(-90.0F, Steering.pitchToward(3, 0), EPS);
        assertEquals(90.0F, Steering.pitchToward(-3, 0), EPS);
        assertEquals(0.0F, Steering.pitchToward(0, 0), EPS);
    }

    @Test
    void pitchTowardIsClampedToLookLimits() {
        assertTrue(Steering.pitchToward(1000, 1) >= -90.0F);
        assertTrue(Steering.pitchToward(-1000, 1) <= 90.0F);
    }

    @Test
    void turnTowardTakesShortestArcAcrossTheWrapBoundary() {
        // 170° → -170° 的最短弧是 +20°，而非绕远路的 -340°
        assertEquals(175.0F, Steering.turnToward(170.0F, -170.0F, 5.0F), EPS);
        assertEquals(-175.0F, Steering.turnToward(-170.0F, 170.0F, 5.0F), EPS);
    }

    @Test
    void turnTowardStopsExactlyOnTargetWhenWithinRate() {
        assertEquals(90.0F, Steering.turnToward(0.0F, 90.0F, 200.0F), EPS);
    }

    @Test
    void turnTowardIsNoOpForNonPositiveRate() {
        assertEquals(30.0F, Steering.turnToward(30.0F, 120.0F, 0.0F), EPS);
        assertEquals(30.0F, Steering.turnToward(30.0F, 120.0F, -5.0F), EPS);
    }

    @Test
    void angleBetweenUsesShortestArc() {
        assertEquals(20.0F, Steering.angleBetween(170.0F, -170.0F), EPS);
        assertEquals(90.0F, Steering.angleBetween(0.0F, 90.0F), EPS);
        assertEquals(0.0F, Steering.angleBetween(45.0F, 45.0F), EPS);
    }

    // ============================================================
    // moveInput —— 边瞄边动的分解数学
    // ============================================================

    @Test
    void moveInputIsPureForwardWhenMovingWhereTheBodyFaces() {
        // yaw 0 面朝 +Z，往 +Z 走应是纯前进
        Steering.MoveInput m = Steering.moveInput(0.0D, 1.0D, 0.0F);
        assertEquals(1.0F, m.forward(), EPS);
        assertEquals(0.0F, m.strafe(), EPS);
    }

    @Test
    void moveInputIsPureStrafeWhenMovingPerpendicularToFacing() {
        // yaw 0 面朝 +Z，往 +X 走应是纯侧移
        Steering.MoveInput m = Steering.moveInput(1.0D, 0.0D, 0.0F);
        assertEquals(0.0F, m.forward(), EPS);
        assertEquals(1.0F, m.strafe(), EPS);
    }

    @Test
    void moveInputFollowsBodyYaw() {
        // yaw -90 面朝 +X：往 +X 走是前进，往 +Z 走是侧移
        Steering.MoveInput forward = Steering.moveInput(1.0D, 0.0D, -90.0F);
        assertEquals(1.0F, forward.forward(), EPS);
        assertEquals(0.0F, forward.strafe(), EPS);

        Steering.MoveInput side = Steering.moveInput(0.0D, 1.0D, -90.0F);
        assertEquals(0.0F, side.forward(), EPS);
        assertEquals(-1.0F, side.strafe(), EPS);
    }

    @Test
    void moveInputNormalisesMagnitude() {
        Steering.MoveInput m = Steering.moveInput(0.0D, 37.0D, 0.0F);
        assertEquals(1.0F, Math.hypot(m.forward(), m.strafe()), EPS);
    }

    @Test
    void moveInputReturnsZeroForZeroDirection() {
        assertEquals(Steering.MoveInput.ZERO, Steering.moveInput(0.0D, 0.0D, 33.0F));
    }

    /**
     * 决定性的一条：把分解结果重新代入原版 {@code getInputVector} 的公式，必须还原出原始方向。
     *
     * <p>逐个手算象限只能覆盖挑好的几例，而符号弄反在某些象限仍会"看起来对"。
     * 这里对多组朝向与方向做往返验证，等价于直接对着原版公式证明分解是其逆变换。
     */
    @Test
    void moveInputRoundTripsThroughVanillaInputVector() {
        float[] yaws = {0.0F, 45.0F, -90.0F, 137.0F, -179.0F, 90.0F};
        double[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {3, 4}, {-2, 7}, {5, -1}};
        for (float yaw : yaws) {
            for (double[] dir : dirs) {
                Steering.MoveInput m = Steering.moveInput(dir[0], dir[1], yaw);
                double rad = Math.toRadians(yaw);
                double sin = Math.sin(rad);
                double cos = Math.cos(rad);
                // 原版：vx = xxa·cosθ − zza·sinθ，vz = zza·cosθ + xxa·sinθ
                double vx = m.strafe() * cos - m.forward() * sin;
                double vz = m.forward() * cos + m.strafe() * sin;
                double length = Math.hypot(dir[0], dir[1]);
                assertEquals(dir[0] / length, vx, 1.0e-4D,
                        "yaw=" + yaw + " dir=(" + dir[0] + "," + dir[1] + ") 的 X 分量未还原");
                assertEquals(dir[1] / length, vz, 1.0e-4D,
                        "yaw=" + yaw + " dir=(" + dir[0] + "," + dir[1] + ") 的 Z 分量未还原");
            }
        }
    }
}
