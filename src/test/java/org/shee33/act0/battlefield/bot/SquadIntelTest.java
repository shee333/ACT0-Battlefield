package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquadIntelTest {

    private static final UUID ENEMY = UUID.randomUUID();

    // ---------------- 限时 ----------------

    @Test
    void contactIsVisibleWithinTtl() {
        SquadIntel intel = new SquadIntel();
        intel.report(ENEMY, 10.0D, 64.0D, 10.0D, 100L);
        assertTrue(intel.lookup(ENEMY, 100L).isPresent());
        assertTrue(intel.lookup(ENEMY, 100L + SquadIntel.TTL_TICKS - 1).isPresent());
    }

    /**
     * 到期即消失是"断视线躲两秒就能重新甩掉全队"这一玩法的实现基础；若情报不过期，
     * 玩家一旦被任意一名 bot 看到就会被全队永久记住，那就是变相透视。
     */
    @Test
    void contactExpiresExactlyAtTtl() {
        SquadIntel intel = new SquadIntel();
        intel.report(ENEMY, 10.0D, 64.0D, 10.0D, 100L);
        assertFalse(intel.lookup(ENEMY, 100L + SquadIntel.TTL_TICKS).isPresent());
        assertTrue(intel.active(100L + SquadIntel.TTL_TICKS).isEmpty());
    }

    @Test
    void freshSightingOverwritesOlderOne() {
        SquadIntel intel = new SquadIntel();
        intel.report(ENEMY, 10.0D, 64.0D, 10.0D, 100L);
        intel.report(ENEMY, 100.0D, 64.0D, 100.0D, 130L);
        assertEquals(1, intel.size(), "同一敌人只保留一条");
        SquadIntel.Contact latest = intel.lookup(ENEMY, 130L).orElseThrow();
        assertEquals(SquadIntel.snapToGrid(100.0D), latest.x());
        assertTrue(latest.isActive(100L + SquadIntel.TTL_TICKS), "新情报应刷新过期时间");
    }

    // ---------------- 限精度 ----------------

    @Test
    void positionsAreQuantizedOnWrite() {
        SquadIntel intel = new SquadIntel();
        intel.report(ENEMY, 13.7D, 64.2D, -5.1D, 0L);
        SquadIntel.Contact contact = intel.lookup(ENEMY, 0L).orElseThrow();
        assertEquals(SquadIntel.snapToGrid(13.7D), contact.x());
        assertEquals(SquadIntel.snapToGrid(64.2D), contact.y());
        assertEquals(SquadIntel.snapToGrid(-5.1D), contact.z());
    }

    @Test
    void quantizationErrorNeverExceedsHalfGrid() {
        double half = SquadIntel.POSITION_GRID_BLOCKS / 2.0D;
        for (double v = -40.0D; v <= 40.0D; v += 0.37D) {
            assertTrue(Math.abs(SquadIntel.snapToGrid(v) - v) <= half + 1.0e-9D,
                    "量化误差不得超过半个网格，位置 " + v);
        }
    }

    /**
     * 吸附到网格<b>中心</b>而非下界：吸到下界会让情报系统性地偏向坐标轴负方向，
     * 一整局里接收方总往同一侧偏。这里检查误差的正负号在网格内两侧都出现。
     */
    @Test
    void quantizationHasNoSystematicDirectionalBias() {
        boolean sawPositive = false;
        boolean sawNegative = false;
        for (double v = 0.0D; v < SquadIntel.POSITION_GRID_BLOCKS; v += 0.5D) {
            double err = SquadIntel.snapToGrid(v) - v;
            sawPositive |= err > 1.0e-9D;
            sawNegative |= err < -1.0e-9D;
        }
        assertTrue(sawPositive && sawNegative, "量化误差应在网格内对称分布，而非单侧偏移");
    }

    @Test
    void quantizationIsCoarseEnoughToRequireSearching() {
        // 8 格网格意味着同一房间内的多个位置会塌缩成同一个报点——接收方仍需自己搜最后一段
        assertEquals(SquadIntel.snapToGrid(1.0D), SquadIntel.snapToGrid(7.9D));
    }

    // ---------------- 清理 ----------------

    @Test
    void pruneRemovesOnlyExpiredEntries() {
        SquadIntel intel = new SquadIntel();
        UUID stale = UUID.randomUUID();
        intel.report(stale, 0.0D, 0.0D, 0.0D, 0L);
        intel.report(ENEMY, 0.0D, 0.0D, 0.0D, 100L);
        assertEquals(2, intel.size());
        assertEquals(1, intel.pruneExpired(100L));
        assertEquals(1, intel.size());
        assertTrue(intel.lookup(ENEMY, 100L).isPresent());
    }

    @Test
    void entriesAccumulateWithoutPruning() {
        // 记录这处已知特性：lookup 会过滤过期项，但条目本身不会自行消失，故必须周期性清理
        SquadIntel intel = new SquadIntel();
        for (int i = 0; i < 20; i++) {
            intel.report(UUID.randomUUID(), 0.0D, 0.0D, 0.0D, 0L);
        }
        assertEquals(20, intel.size());
        assertTrue(intel.active(1000L).isEmpty(), "全部过期");
        assertEquals(20, intel.size(), "但未清理前条目仍在");
        intel.pruneExpired(1000L);
        assertEquals(0, intel.size());
    }

    @Test
    void nullEnemyIsIgnored() {
        SquadIntel intel = new SquadIntel();
        intel.report(null, 1.0D, 1.0D, 1.0D, 0L);
        assertEquals(0, intel.size());
    }
}
