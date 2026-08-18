package org.shee33.act0.battlefield.data;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.PlayerArenaLoadout;
import org.shee33.act0.battlefield.core.arena.PlayerMapLoadout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住兵种配装的 NBT 编解码，重点是<b>旧存档迁移</b>。
 *
 * <p>0.2.10 之前每张图只存一套选择，格式是把「槽位 → ID」平铺在地图节点上。识别逻辑一旦判错，
 * 后果是所有老玩家的配装被静默清空——没有报错、没有日志，玩家只会发现"我的枪没了"。
 * 因此新旧两种格式都必须钉住。
 */
class PlayerMapLoadoutCodecTest {

    /** 构造一个 0.2.10 之前写出来的地图节点：槽位键直接平铺，没有 byClass 子节点。 */
    private static CompoundTag legacyTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(LoadoutSlot.PRIMARY.id(), "tacz:ak47");
        tag.putString(LoadoutSlot.GADGET_1.id(), "act0_battlefield:medic_syringe");
        return tag;
    }

    @Test
    void legacySaveMigratesIntoDefaultClassBucket() {
        PlayerMapLoadout migrated = ArenaCatalogCodec.loadMapLoadout(legacyTag());

        assertEquals(SoldierClass.DEFAULT, migrated.selected());
        PlayerArenaLoadout picks = migrated.loadout(SoldierClass.DEFAULT);
        assertEquals("tacz:ak47", picks.pick(LoadoutSlot.PRIMARY));
        assertEquals("act0_battlefield:medic_syringe", picks.pick(LoadoutSlot.GADGET_1));
    }

    /** 迁移只填默认兵种那一桶，其余三个兵种应当是干净的，而不是被复制四份。 */
    @Test
    void legacyMigrationLeavesOtherClassesEmpty() {
        PlayerMapLoadout migrated = ArenaCatalogCodec.loadMapLoadout(legacyTag());
        for (SoldierClass c : SoldierClass.values()) {
            if (c != SoldierClass.DEFAULT) {
                assertSame(PlayerArenaLoadout.EMPTY, migrated.loadout(c), c + " 桶应为空");
            }
        }
    }

    @Test
    void emptyLegacyTagYieldsEmpty() {
        assertTrue(ArenaCatalogCodec.loadMapLoadout(new CompoundTag()).isEmpty());
    }

    @Test
    void roundTripsAllFourClassesAndSelection() {
        PlayerMapLoadout before = PlayerMapLoadout.EMPTY
                .withPick(SoldierClass.ASSAULT, LoadoutSlot.PRIMARY, "tacz:ak47")
                .withPick(SoldierClass.MEDIC, LoadoutSlot.PRIMARY, "tacz:mp5")
                .withPick(SoldierClass.MEDIC, LoadoutSlot.GADGET_1, "act0_battlefield:medic_syringe")
                .withPick(SoldierClass.ENGINEER, LoadoutSlot.GADGET_2, "act0_battlefield:ammo_box")
                .withPick(SoldierClass.RECON, LoadoutSlot.PRIMARY, "tacz:m24")
                .withSelected(SoldierClass.RECON);

        PlayerMapLoadout after =
                ArenaCatalogCodec.loadMapLoadout(ArenaCatalogCodec.saveMapLoadout(before));

        assertEquals(SoldierClass.RECON, after.selected());
        assertEquals("tacz:ak47", after.loadout(SoldierClass.ASSAULT).pick(LoadoutSlot.PRIMARY));
        assertEquals("tacz:mp5", after.loadout(SoldierClass.MEDIC).pick(LoadoutSlot.PRIMARY));
        assertEquals("act0_battlefield:medic_syringe",
                after.loadout(SoldierClass.MEDIC).pick(LoadoutSlot.GADGET_1));
        assertEquals("act0_battlefield:ammo_box",
                after.loadout(SoldierClass.ENGINEER).pick(LoadoutSlot.GADGET_2));
        assertEquals("tacz:m24", after.loadout(SoldierClass.RECON).pick(LoadoutSlot.PRIMARY));
    }

    /** 只切过兵种、没选过任何枪，也必须能存下来——否则玩家每局都被打回突击兵。 */
    @Test
    void roundTripsSelectionWithoutAnyPick() {
        PlayerMapLoadout before = PlayerMapLoadout.EMPTY.withSelected(SoldierClass.ENGINEER);
        PlayerMapLoadout after =
                ArenaCatalogCodec.loadMapLoadout(ArenaCatalogCodec.saveMapLoadout(before));
        assertEquals(SoldierClass.ENGINEER, after.selected());
    }

    /** 存档里出现已被删掉的兵种 ID（例如未来改名）时跳过该桶，不能连带整条记录读不出来。 */
    @Test
    void unknownClassBucketIsSkipped() {
        CompoundTag tag = ArenaCatalogCodec.saveMapLoadout(PlayerMapLoadout.EMPTY
                .withPick(SoldierClass.MEDIC, LoadoutSlot.PRIMARY, "tacz:mp5"));
        CompoundTag stray = new CompoundTag();
        stray.putString(LoadoutSlot.PRIMARY.id(), "tacz:ghost");
        tag.getCompound("byClass").put("sniper", stray);

        PlayerMapLoadout after = ArenaCatalogCodec.loadMapLoadout(tag);
        assertEquals("tacz:mp5", after.loadout(SoldierClass.MEDIC).pick(LoadoutSlot.PRIMARY));
        assertEquals(1, after.byClass().size());
    }

    /** 当前兵种字段脏了就回落默认，不抛异常。 */
    @Test
    void unknownSelectedClassFallsBackToDefault() {
        CompoundTag tag = ArenaCatalogCodec.saveMapLoadout(
                PlayerMapLoadout.EMPTY.withSelected(SoldierClass.RECON));
        tag.putString("class", "commando");
        assertEquals(SoldierClass.DEFAULT, ArenaCatalogCodec.loadMapLoadout(tag).selected());
    }

    @Test
    void legacyTagWithOnlyUnknownKeysIsEmpty() {
        CompoundTag tag = new CompoundTag();
        tag.putString("armor", "act0:vest");
        PlayerMapLoadout after = ArenaCatalogCodec.loadMapLoadout(tag);
        assertTrue(after.isEmpty());
        assertNull(after.loadout(SoldierClass.DEFAULT).pick(LoadoutSlot.PRIMARY));
    }
}
