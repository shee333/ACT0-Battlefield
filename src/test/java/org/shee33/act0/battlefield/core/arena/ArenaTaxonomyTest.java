package org.shee33.act0.battlefield.core.arena;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 槽位与武器类别这两张固定表的约束：ID 唯一、槽位索引唯一、类别到槽位的映射自洽。 */
class ArenaTaxonomyTest {

    /** 槽位索引直接当快捷栏格子用，重复就会出现两件装备抢同一格。 */
    @Test
    void hotbarIndicesAreUniqueAndContiguousFromZero() {
        Set<Integer> seen = new HashSet<>();
        for (LoadoutSlot slot : LoadoutSlot.values()) {
            assertTrue(seen.add(slot.hotbarIndex()), "快捷栏索引重复: " + slot);
            assertTrue(slot.hotbarIndex() >= 0 && slot.hotbarIndex() < 9,
                    "快捷栏索引必须落在 0-8: " + slot);
        }
        for (int i = 0; i < LoadoutSlot.values().length; i++) {
            assertNotNull(LoadoutSlot.byHotbarIndex(i), "索引 " + i + " 应有对应槽位");
        }
        assertNull(LoadoutSlot.byHotbarIndex(8), "未使用的快捷栏格子不应反查到槽位");
    }

    @Test
    void slotIdsRoundTripCaseInsensitively() {
        for (LoadoutSlot slot : LoadoutSlot.values()) {
            assertSame(slot, LoadoutSlot.byId(slot.id()));
            assertSame(slot, LoadoutSlot.byId(slot.id().toUpperCase()));
        }
        assertNull(LoadoutSlot.byId("tertiary"), "未知槽位名应返回 null 而非抛异常");
        assertNull(LoadoutSlot.byId(null));
    }

    @Test
    void gadgetAndWeaponSlotsArePartitioned() {
        assertEquals(List.of(LoadoutSlot.GADGET_1, LoadoutSlot.GADGET_2), LoadoutSlot.gadgetSlots());
        for (LoadoutSlot slot : LoadoutSlot.values()) {
            assertTrue(slot.isGadget() ^ slot.isWeapon(), slot + " 必须恰好属于武器槽或道具槽之一");
        }
    }

    @Test
    void categoryIdsRoundTripCaseInsensitively() {
        for (WeaponCategory c : WeaponCategory.values()) {
            assertSame(c, WeaponCategory.byId(c.id()));
            assertSame(c, WeaponCategory.byId(c.id().toUpperCase()));
        }
        assertSame(WeaponCategory.MACHINEGUN, WeaponCategory.byId("machinegun"));
        assertNull(WeaponCategory.byId("snipper"), "拼错的类别名必须被拒绝而不是静默新建");
    }

    /** 武器绝不能被映射进道具槽，否则出生发装会把枪塞进道具格。 */
    @Test
    void everyCategoryMapsToAWeaponSlot() {
        for (WeaponCategory c : WeaponCategory.values()) {
            assertTrue(c.slot().isWeapon(), c + " 映射到了道具槽: " + c.slot());
        }
        assertSame(LoadoutSlot.SECONDARY, WeaponCategory.PISTOL.slot());
        assertSame(LoadoutSlot.MELEE, WeaponCategory.MELEE.slot());
    }

    /** forSlot 的顺序就是玩家在部署面板里看到的顺序，属于设计决定，锁住防止改枚举顺序时漂移。 */
    @Test
    void primarySlotCategoryOrderIsStable() {
        assertEquals(
                List.of(WeaponCategory.SMG, WeaponCategory.RIFLE, WeaponCategory.MACHINEGUN,
                        WeaponCategory.SNIPER, WeaponCategory.SHOTGUN, WeaponCategory.LAUNCHER),
                WeaponCategory.forSlot(LoadoutSlot.PRIMARY));
        assertEquals(List.of(WeaponCategory.PISTOL), WeaponCategory.forSlot(LoadoutSlot.SECONDARY));
        assertTrue(WeaponCategory.forSlot(LoadoutSlot.GADGET_1).isEmpty(), "道具槽不应有武器类别");
    }

    @Test
    void categoryListIsImmutable() {
        List<WeaponCategory> list = WeaponCategory.forSlot(LoadoutSlot.PRIMARY);
        assertFalse(list.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> list.add(WeaponCategory.PISTOL),
                "forSlot 返回的列表必须不可变");
    }
}
