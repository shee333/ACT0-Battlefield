package org.shee33.act0.battlefield.core.arena;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog.EditResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaCatalogTest {

    private static ArenaWeaponEntry gun(String id) {
        return new ArenaWeaponEntry(id, id, 120);
    }

    private static ArenaItemEntry item(String id) {
        return new ArenaItemEntry(id, id, 1);
    }

    @Test
    void newCatalogIsEmpty() {
        ArenaCatalog c = new ArenaCatalog();
        assertTrue(c.isEmpty());
        assertEquals(0, c.totalEntries());
        assertNull(c.defaultOptionForSlot(LoadoutSlot.PRIMARY), "空目录不应给出默认项");
        assertTrue(c.optionIdsForSlot(LoadoutSlot.PRIMARY).isEmpty());
    }

    @Test
    void addWeaponThenFindIt() {
        ArenaCatalog c = new ArenaCatalog();
        assertSame(EditResult.OK, c.addWeapon(WeaponCategory.RIFLE, gun("tacz:ak47")));
        assertSame(WeaponCategory.RIFLE, c.categoryOf("tacz:ak47"));
        ArenaWeaponEntry found = c.findWeapon("tacz:ak47");
        assertNotNull(found);
        assertEquals(120, found.dummyAmmo());
        assertEquals(1, c.totalEntries());
        assertFalse(c.isEmpty());
    }

    /** 同一把枪出现在两个类别里会在同槽位列表中重复，且 ID 做选择键时无法区分，必须录入时挡住。 */
    @Test
    void duplicateWeaponIsRejectedAcrossCategories() {
        ArenaCatalog c = new ArenaCatalog();
        assertSame(EditResult.OK, c.addWeapon(WeaponCategory.RIFLE, gun("tacz:ak47")));
        assertSame(EditResult.DUPLICATE, c.addWeapon(WeaponCategory.RIFLE, gun("tacz:ak47")),
                "同类别重复应被拒");
        assertSame(EditResult.DUPLICATE, c.addWeapon(WeaponCategory.SNIPER, gun("tacz:ak47")),
                "跨类别重复同样应被拒");
        assertEquals(1, c.totalEntries());
    }

    @Test
    void removeWeaponReportsNotFound() {
        ArenaCatalog c = new ArenaCatalog();
        assertSame(EditResult.NOT_FOUND, c.removeWeapon(WeaponCategory.RIFLE, "tacz:ak47"));
        c.addWeapon(WeaponCategory.RIFLE, gun("tacz:ak47"));
        assertSame(EditResult.NOT_FOUND, c.removeWeapon(WeaponCategory.SNIPER, "tacz:ak47"),
                "类别不匹配时不应误删");
        assertSame(EditResult.OK, c.removeWeapon(WeaponCategory.RIFLE, "tacz:ak47"));
        assertSame(EditResult.NOT_FOUND, c.removeWeapon(WeaponCategory.RIFLE, "tacz:ak47"));
        assertTrue(c.isEmpty(), "移除最后一条后类别桶应被清掉");
    }

    /** 槽位列表顺序 = 类别声明顺序拼接，玩家面板顺序依赖它。 */
    @Test
    void weaponsForSlotConcatenatesInCategoryOrder() {
        ArenaCatalog c = new ArenaCatalog();
        c.addWeapon(WeaponCategory.SNIPER, gun("tacz:m24"));
        c.addWeapon(WeaponCategory.SMG, gun("tacz:mp5"));
        c.addWeapon(WeaponCategory.RIFLE, gun("tacz:ak47"));
        c.addWeapon(WeaponCategory.PISTOL, gun("tacz:glock"));
        assertEquals(List.of("tacz:mp5", "tacz:ak47", "tacz:m24"),
                c.optionIdsForSlot(LoadoutSlot.PRIMARY));
        assertEquals(List.of("tacz:glock"), c.optionIdsForSlot(LoadoutSlot.SECONDARY));
        assertEquals("tacz:mp5", c.defaultOptionForSlot(LoadoutSlot.PRIMARY),
                "默认项应是槽位列表首项");
    }

    /** 上限按槽位算，因为槽位列表才是要走网络的那个列表。 */
    @Test
    void slotCapacityIsEnforcedAcrossCategories() {
        ArenaCatalog c = new ArenaCatalog();
        for (int i = 0; i < ArenaCatalog.MAX_PER_SLOT; i++) {
            WeaponCategory cat = (i % 2 == 0) ? WeaponCategory.RIFLE : WeaponCategory.SMG;
            assertSame(EditResult.OK, c.addWeapon(cat, gun("tacz:gun" + i)), "第 " + i + " 条应录入成功");
        }
        assertSame(EditResult.FULL, c.addWeapon(WeaponCategory.SNIPER, gun("tacz:overflow")),
                "主武器槽满员后其他类别也不该再录进来");
        assertSame(EditResult.OK, c.addWeapon(WeaponCategory.PISTOL, gun("tacz:glock")),
                "副武器槽独立计数，不受主武器槽满员影响");
        assertEquals(ArenaCatalog.MAX_PER_SLOT, c.optionIdsForSlot(LoadoutSlot.PRIMARY).size());
    }

    @Test
    void itemsOnlyGoIntoGadgetSlots() {
        ArenaCatalog c = new ArenaCatalog();
        assertSame(EditResult.WRONG_SLOT, c.addItem(LoadoutSlot.PRIMARY, item("act0:ammo_box")),
                "武器槽不接受道具");
        assertSame(EditResult.WRONG_SLOT, c.removeItem(LoadoutSlot.MELEE, "act0:ammo_box"));
        assertSame(EditResult.OK, c.addItem(LoadoutSlot.GADGET_1, item("act0:ammo_box")));
        assertSame(EditResult.DUPLICATE, c.addItem(LoadoutSlot.GADGET_1, item("act0:ammo_box")));
        assertSame(EditResult.OK, c.addItem(LoadoutSlot.GADGET_2, item("act0:ammo_box")),
                "不同道具槽可以放同一件道具");
        assertEquals(List.of("act0:ammo_box"), c.optionIdsForSlot(LoadoutSlot.GADGET_1));
        assertNotNull(c.findItem(LoadoutSlot.GADGET_1, "act0:ammo_box"));
        assertNull(c.findItem(LoadoutSlot.GADGET_1, "act0:medic_box"));
    }

    @Test
    void hasOptionSeparatesWeaponAndItemPools() {
        ArenaCatalog c = new ArenaCatalog();
        c.addWeapon(WeaponCategory.RIFLE, gun("tacz:ak47"));
        c.addItem(LoadoutSlot.GADGET_1, item("act0:medic_box"));
        assertTrue(c.hasOption(LoadoutSlot.PRIMARY, "tacz:ak47"));
        assertFalse(c.hasOption(LoadoutSlot.GADGET_1, "tacz:ak47"), "枪不该出现在道具槽的可选项里");
        assertTrue(c.hasOption(LoadoutSlot.GADGET_1, "act0:medic_box"));
        assertFalse(c.hasOption(LoadoutSlot.PRIMARY, "act0:medic_box"));
        assertFalse(c.hasOption(LoadoutSlot.PRIMARY, null));
        assertEquals(2, c.totalEntries());
    }

    @Test
    void entryValidationRejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> new ArenaWeaponEntry("", "AK", 0));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWeaponEntry("tacz:ak47", " ", 0));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWeaponEntry("tacz:ak47", "AK", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ArenaWeaponEntry("tacz:ak47", "AK", ArenaWeaponEntry.MAX_DUMMY_AMMO + 1));
        assertThrows(NullPointerException.class, () -> new ArenaWeaponEntry(null, "AK", 0));
        assertFalse(new ArenaWeaponEntry("tacz:ak47", "AK", 0).usesDummyAmmo(),
                "0 表示不启用虚拟备弹、走背包弹药");
        assertTrue(new ArenaWeaponEntry("tacz:ak47", "AK", 1).usesDummyAmmo());

        assertThrows(IllegalArgumentException.class, () -> new ArenaItemEntry("act0:x", "X", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ArenaItemEntry("act0:x", "X", ArenaItemEntry.MAX_COUNT + 1));
        assertThrows(IllegalArgumentException.class, () -> new ArenaItemEntry(" ", "X", 1));
    }

    @Test
    void returnedListsAreImmutable() {
        ArenaCatalog c = new ArenaCatalog();
        c.addWeapon(WeaponCategory.RIFLE, gun("tacz:ak47"));
        assertThrows(UnsupportedOperationException.class,
                () -> c.weapons(WeaponCategory.RIFLE).add(gun("tacz:m4")));
        assertThrows(UnsupportedOperationException.class,
                () -> c.optionIdsForSlot(LoadoutSlot.PRIMARY).add("tacz:m4"));
    }
}
