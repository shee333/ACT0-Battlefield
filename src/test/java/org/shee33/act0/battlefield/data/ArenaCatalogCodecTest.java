package org.shee33.act0.battlefield.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog;
import org.shee33.act0.battlefield.core.arena.ArenaItemEntry;
import org.shee33.act0.battlefield.core.arena.ArenaWeaponEntry;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.PlayerArenaLoadout;
import org.shee33.act0.battlefield.core.arena.WeaponCategory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaCatalogCodecTest {

    private static ArenaCatalog sample() {
        ArenaCatalog c = new ArenaCatalog();
        c.addWeapon(WeaponCategory.RIFLE, new ArenaWeaponEntry("tacz:ak47", "AK-47", 120));
        c.addWeapon(WeaponCategory.RIFLE, new ArenaWeaponEntry("tacz:m4", "M4A1", 150));
        c.addWeapon(WeaponCategory.SNIPER, new ArenaWeaponEntry("tacz:m24", "M24", 40));
        c.addWeapon(WeaponCategory.PISTOL, new ArenaWeaponEntry("tacz:glock", "Glock 17", 0));
        c.addItem(LoadoutSlot.GADGET_1, new ArenaItemEntry("act0_battlefield:ammo_box", "弹药箱", 1));
        c.addItem(LoadoutSlot.GADGET_2, new ArenaItemEntry("act0_battlefield:medic_syringe", "医疗针", 3));
        return c;
    }

    @Test
    void catalogRoundTripsThroughNbt() {
        ArenaCatalog before = sample();
        ArenaCatalog after = ArenaCatalogCodec.load(ArenaCatalogCodec.save(before));

        assertEquals(before.totalEntries(), after.totalEntries());
        assertEquals(before.optionIdsForSlot(LoadoutSlot.PRIMARY), after.optionIdsForSlot(LoadoutSlot.PRIMARY));
        assertEquals(before.optionIdsForSlot(LoadoutSlot.SECONDARY), after.optionIdsForSlot(LoadoutSlot.SECONDARY));
        assertEquals(before.optionIdsForSlot(LoadoutSlot.GADGET_1), after.optionIdsForSlot(LoadoutSlot.GADGET_1));
        assertEquals(before.optionIdsForSlot(LoadoutSlot.GADGET_2), after.optionIdsForSlot(LoadoutSlot.GADGET_2));

        ArenaWeaponEntry ak = after.findWeapon("tacz:ak47");
        assertNotNull(ak);
        assertEquals("AK-47", ak.displayName());
        assertEquals(120, ak.dummyAmmo());
        assertSame(WeaponCategory.SNIPER, after.categoryOf("tacz:m24"), "类别归属必须原样带回");

        ArenaItemEntry syringe = after.findItem(LoadoutSlot.GADGET_2, "act0_battlefield:medic_syringe");
        assertNotNull(syringe);
        assertEquals(3, syringe.count());
    }

    /** 录入顺序决定部署面板顺序，往返后必须一模一样。 */
    @Test
    void entryOrderSurvivesRoundTrip() {
        ArenaCatalog after = ArenaCatalogCodec.load(ArenaCatalogCodec.save(sample()));
        assertEquals(List.of("tacz:ak47", "tacz:m4", "tacz:m24"),
                after.optionIdsForSlot(LoadoutSlot.PRIMARY));
    }

    @Test
    void emptyCatalogProducesEmptyTag() {
        CompoundTag tag = ArenaCatalogCodec.save(new ArenaCatalog());
        assertTrue(tag.isEmpty(), "空目录不应往存档里写任何键");
        assertTrue(ArenaCatalogCodec.load(tag).isEmpty());
        assertTrue(ArenaCatalogCodec.load(new CompoundTag()).isEmpty(), "读空标签不应抛异常");
    }

    /** 枚举改名后旧存档里会留下读不懂的类别，必须跳过而不是让整个世界加载失败。 */
    @Test
    void unknownCategoryIsSkipped() {
        CompoundTag tag = ArenaCatalogCodec.save(sample());
        ListTag bogus = new ListTag();
        CompoundTag e = new CompoundTag();
        e.putString("id", "tacz:mystery");
        e.putString("name", "Mystery");
        e.putInt("ammo", 10);
        bogus.add(e);
        tag.getCompound("weapons").put("plasmacannon", bogus);

        ArenaCatalog after = ArenaCatalogCodec.load(tag);
        assertEquals(4, after.optionIdsForSlot(LoadoutSlot.PRIMARY).size() + after.optionIdsForSlot(LoadoutSlot.SECONDARY).size(),
                "未知类别应被跳过，其余武器照常读出");
        assertNull(after.findWeapon("tacz:mystery"));
    }

    /** 一条脏记录不能连累同一个桶里的其他记录。 */
    @Test
    void corruptEntryIsSkippedButSiblingsSurvive() {
        CompoundTag tag = ArenaCatalogCodec.save(sample());
        ListTag rifles = tag.getCompound("weapons").getList("rifle", 10);
        CompoundTag broken = new CompoundTag();
        broken.putString("id", "");
        broken.putString("name", "坏条目");
        broken.putInt("ammo", -5);
        rifles.add(broken);

        ArenaCatalog after = ArenaCatalogCodec.load(tag);
        assertEquals(List.of("tacz:ak47", "tacz:m4", "tacz:m24"),
                after.optionIdsForSlot(LoadoutSlot.PRIMARY),
                "坏条目跳过，同桶其余条目必须完好");
    }

    /** 道具槽字面量若指向武器槽，说明存档被改坏了，不能把枪塞进道具池。 */
    @Test
    void itemsUnderNonGadgetSlotAreSkipped() {
        CompoundTag tag = new CompoundTag();
        CompoundTag items = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag e = new CompoundTag();
        e.putString("id", "act0_battlefield:ammo_box");
        e.putString("name", "弹药箱");
        e.putInt("count", 1);
        list.add(e);
        items.put("primary", list);
        tag.put("items", items);

        assertTrue(ArenaCatalogCodec.load(tag).isEmpty());
    }

    @Test
    void playerLoadoutRoundTrips() {
        PlayerArenaLoadout before = PlayerArenaLoadout.EMPTY
                .with(LoadoutSlot.PRIMARY, "tacz:m4")
                .with(LoadoutSlot.GADGET_1, "act0_battlefield:ammo_box");
        PlayerArenaLoadout after = ArenaCatalogCodec.loadLoadout(ArenaCatalogCodec.saveLoadout(before));
        assertEquals("tacz:m4", after.pick(LoadoutSlot.PRIMARY));
        assertEquals("act0_battlefield:ammo_box", after.pick(LoadoutSlot.GADGET_1));
        assertNull(after.pick(LoadoutSlot.SECONDARY));
        assertTrue(ArenaCatalogCodec.saveLoadout(PlayerArenaLoadout.EMPTY).isEmpty());
        assertSame(PlayerArenaLoadout.EMPTY, ArenaCatalogCodec.loadLoadout(new CompoundTag()));
    }

    @Test
    void unknownSlotInPlayerLoadoutIsSkipped() {
        CompoundTag tag = new CompoundTag();
        tag.putString("primary", "tacz:m4");
        tag.putString("tertiary", "tacz:ghost");
        PlayerArenaLoadout l = ArenaCatalogCodec.loadLoadout(tag);
        assertEquals(1, l.picks().size());
        assertEquals("tacz:m4", l.pick(LoadoutSlot.PRIMARY));
        assertFalse(l.isEmpty());
    }
}
