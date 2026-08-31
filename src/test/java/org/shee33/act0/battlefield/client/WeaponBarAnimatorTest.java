package org.shee33.act0.battlefield.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 弹药滚轮状态机：弹匣数字变化才滚动，备弹数字静止静默更新。 */
class WeaponBarAnimatorTest {

    private static final long T0 = 1_000_000L;

    @BeforeEach
    void reset() {
        WeaponBarAnimator.clear();
    }

    @Test
    void selectSplitsMagAndReserve() {
        WeaponBarAnimator.select(0, "M4A1", "30 / 90", T0);
        assertEquals("30", WeaponBarAnimator.ammoText());
        assertEquals(" / 90", WeaponBarAnimator.reserveText());
    }

    @Test
    void firingRollsOnlyMagazine() {
        WeaponBarAnimator.select(0, "M4A1", "30 / 90", T0);
        WeaponBarAnimator.updateAmmo("29 / 90", T0);
        assertEquals("29", WeaponBarAnimator.ammoText(), "弹匣数字更新为 29");
        assertEquals("30", WeaponBarAnimator.oldAmmoText(), "旧弹匣数字参与滚出");
        assertEquals(" / 90", WeaponBarAnimator.reserveText(), "备弹数字不变");
        assertEquals(-1, WeaponBarAnimator.ammoDir(), "弹匣减少应向下滚");
    }

    @Test
    void ammoPickupUpdatesReserveSilently() {
        WeaponBarAnimator.select(0, "M4A1", "30 / 90", T0);
        WeaponBarAnimator.updateAmmo("30 / 120", T0 + 1L);
        assertEquals("30", WeaponBarAnimator.ammoText(), "弹匣数字不变");
        assertEquals(" / 120", WeaponBarAnimator.reserveText(), "备弹数字原地刷新");
        assertTrue(WeaponBarAnimator.oldAmmoText().isEmpty(), "备弹变化不触发滚动");
    }

    @Test
    void reloadRollsMagUp() {
        WeaponBarAnimator.select(0, "M4A1", "0 / 90", T0);
        WeaponBarAnimator.updateAmmo("30 / 60", T0 + 1L);
        assertEquals("30", WeaponBarAnimator.ammoText());
        assertEquals("0", WeaponBarAnimator.oldAmmoText());
        assertEquals(" / 60", WeaponBarAnimator.reserveText());
        assertEquals(1, WeaponBarAnimator.ammoDir(), "换弹后弹匣增加应向上滚");
    }

    @Test
    void nonGunTextRollsWhole() {
        // 无 " / " 分隔符的文本（如 "×64"）整串视为弹匣部分，行为与旧版一致。
        WeaponBarAnimator.select(1, "石剑", "×64", T0);
        assertEquals("×64", WeaponBarAnimator.ammoText());
        assertTrue(WeaponBarAnimator.reserveText().isEmpty());
        WeaponBarAnimator.updateAmmo("×63", T0 + 1L);
        assertEquals("×63", WeaponBarAnimator.ammoText());
        assertEquals("×64", WeaponBarAnimator.oldAmmoText());
    }

    @Test
    void identicalAmmoDoesNothing() {
        WeaponBarAnimator.select(0, "M4A1", "30 / 90", T0);
        WeaponBarAnimator.updateAmmo("30 / 90", T0 + 1L);
        assertEquals("30", WeaponBarAnimator.ammoText());
        assertTrue(WeaponBarAnimator.oldAmmoText().isEmpty(), "相同弹药文本不应触发滚动");
    }
}
