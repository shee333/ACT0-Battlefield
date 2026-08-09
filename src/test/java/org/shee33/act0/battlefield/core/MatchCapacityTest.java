package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchCapacityTest {

    /** 0 表示"该地图未设置"，必须回退到全局默认。 */
    @Test
    void resolveFallsBackToGlobalWhenUnset() {
        assertEquals(8, MatchCapacity.resolve(0, 8), "未设置应跟随全局");
        assertEquals(8, MatchCapacity.resolve(-3, 8), "负值同样视为未设置");
        assertEquals(12, MatchCapacity.resolve(12, 8), "设置过就用地图自己的值");
        assertEquals(1, MatchCapacity.resolve(1, 8));
    }

    /**
     * 奇数上限必须向上取整，否则最后一个名额进不来：上限 15 若两边各限 7，只能进 14 人。
     */
    @Test
    void perSideCapRoundsUpSoOddLimitsCanFill() {
        assertEquals(8, MatchCapacity.perSideCap(16));
        assertEquals(8, MatchCapacity.perSideCap(15), "15 人上限时单边应允许 8，才能凑成 8/7");
        assertEquals(1, MatchCapacity.perSideCap(1));
        assertEquals(1, MatchCapacity.perSideCap(2));
    }

    @Test
    void perSideCapUnlimitedWhenNoLimit() {
        assertEquals(MatchCapacity.UNLIMITED, MatchCapacity.perSideCap(0));
        assertEquals(MatchCapacity.UNLIMITED, MatchCapacity.perSideCap(-1));
    }

    /** 单边上限向上取整后，两边加起来不得超出总上限——总量另有前置校验兜底。 */
    @Test
    void perSideCapNeverLetsBothSidesExceedTotalAlone() {
        for (int max = 1; max <= 64; max++) {
            int side = MatchCapacity.perSideCap(max);
            assertTrue(side * 2 - max <= 1,
                    "上限 " + max + " 的单边容量 " + side + " 溢出超过 1 个名额");
        }
    }

    @Test
    void hasRoomRespectsLimit() {
        assertTrue(MatchCapacity.hasRoom(0, 4));
        assertTrue(MatchCapacity.hasRoom(3, 4));
        assertFalse(MatchCapacity.hasRoom(4, 4), "满员不得再进");
        assertFalse(MatchCapacity.hasRoom(5, 4));
        assertTrue(MatchCapacity.hasRoom(9999, 0), "无上限时永远有位置");
    }

    @Test
    void shortfallNeverNegative() {
        assertEquals(5, MatchCapacity.shortfall(3, 8));
        assertEquals(0, MatchCapacity.shortfall(8, 8));
        assertEquals(0, MatchCapacity.shortfall(20, 8), "超出开局人数后不应显示负数");
    }

    /** 开局人数大于人数上限时对局永远开不了，必须在设置时就拒绝。 */
    @Test
    void validateRejectsUnreachableStartThreshold() {
        assertNotNull(MatchCapacity.validate(20, 16), "开局人数超过上限应报错");
        assertNotNull(MatchCapacity.validate(0, 16), "开局人数为 0 应报错");
        assertNull(MatchCapacity.validate(16, 16), "恰好等于上限是合法的");
        assertNull(MatchCapacity.validate(8, 16));
        assertNull(MatchCapacity.validate(8, 0), "无人数上限时任何开局人数都合法");
    }
}
