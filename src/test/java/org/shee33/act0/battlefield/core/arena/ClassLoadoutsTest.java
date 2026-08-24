package org.shee33.act0.battlefield.core.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁住 4×4 配装网格的一行：一个兵种下的 4 套配装组。 */
class ClassLoadoutsTest {

    @Test
    void initialHasFourEmptyPresetsActiveAtZero() {
        ClassLoadouts g = ClassLoadouts.initial();
        assertEquals(0, g.activeIndex());
        assertEquals(ClassLoadouts.PRESET_COUNT, g.presets().size());
        for (int i = 0; i < ClassLoadouts.PRESET_COUNT; i++) {
            assertTrue(g.preset(i).isEmpty(), "初始第 " + i + " 套应为空");
        }
        assertTrue(g.isEmpty());
    }

    @Test
    void pickLandsInTheTargetedPresetOnly() {
        ClassLoadouts g = ClassLoadouts.initial()
                .withPick(1, LoadoutSlot.PRIMARY, "tacz:ak47");
        assertEquals("tacz:ak47", g.preset(1).loadout().pick(LoadoutSlot.PRIMARY));
        assertTrue(g.preset(0).isEmpty(), "改第 1 套不能碰到第 0 套");
        assertTrue(g.preset(2).isEmpty());
        // 激活套仍是第 0 套的空套。
        assertTrue(g.active().isEmpty());
    }

    @Test
    void withActiveChangesWhatActiveReturns() {
        ClassLoadouts g = ClassLoadouts.initial()
                .withPick(2, LoadoutSlot.PRIMARY, "tacz:m24")
                .withActive(2);
        assertEquals(2, g.activeIndex());
        assertEquals("tacz:m24", g.active().pick(LoadoutSlot.PRIMARY));
    }

    @Test
    void renameMarksPresetNonEmptyAndRoundTrips() {
        ClassLoadouts g = ClassLoadouts.initial().withName(0, "正面突破");
        assertEquals("正面突破", g.preset(0).name());
        assertFalse(g.isEmpty(), "命名过的组需要持久化");
        assertTrue(g.withName(0, "").isEmpty(), "改回空名应恢复初始态");
    }

    @Test
    void outOfRangeIndicesAreClamped() {
        ClassLoadouts g = ClassLoadouts.initial();
        assertEquals(ClassLoadouts.PRESET_COUNT - 1, g.withActive(99).activeIndex());
        // 负数钳到 0（下限），与当前激活 0 相同则原样返回。
        assertEquals(0, g.withActive(-5).activeIndex());
        // 越界读写不抛异常、不产生新套。
        assertTrue(g.preset(99).isEmpty());
        assertEquals(ClassLoadouts.PRESET_COUNT, g.presets().size());
    }

    @Test
    void activeIndexBeyondDefaultsStopsBeingEmpty() {
        // 只切激活套、没配任何东西：也是玩家做出的选择，应当持久化。
        assertFalse(ClassLoadouts.initial().withActive(1).isEmpty());
    }
}
