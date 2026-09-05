package org.shee33.act0.battlefield.data;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.LoadoutPresetDef;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁住"管理员预设配装"的数据模型与存档编解码。 */
class BattlefieldDataPresetTest {



    @Test
    void createPresetGeneratesStableIdAndStores() {
        BattlefieldData data = new BattlefieldData();
        LoadoutPresetDef def = data.createPreset(Faction.ALPHA, SoldierClass.ASSAULT, "正面突破");
        assertTrue(def.id().startsWith("lp_"), "配装 id 应为生成式稳定标识");
        assertEquals(1, data.presetsFor(Faction.ALPHA, SoldierClass.ASSAULT).size());
        assertTrue(data.presetsFor(Faction.BRAVO, SoldierClass.ASSAULT).isEmpty(), "阵营隔离");
    }

    @Test
    void slotAmmoAndArmorRoundTripThroughNbt() {
        BattlefieldData data = new BattlefieldData();
        LoadoutPresetDef def = data.createPreset(Faction.BRAVO, SoldierClass.RECON, "狙击套")
                .withSlot(LoadoutSlot.PRIMARY, "tacz:m24")
                .withAmmo(LoadoutSlot.PRIMARY, 40)
                .withGunNbt(LoadoutSlot.PRIMARY, "{GunId:\"tacz:m24\",AttachmentSCOPE:{}}")
                .withSlot(LoadoutSlot.MELEE, "minecraft:iron_sword")
                .withSlot(LoadoutSlot.GADGET_1, "act0_battlefield:medic_syringe")
                .withArmor(new LoadoutPresetDef.ArmorSet(
                        "minecraft:iron_helmet", "minecraft:iron_chestplate", null, "minecraft:iron_boots"));
        data.savePresetDef(Faction.BRAVO, SoldierClass.RECON, def);

        BattlefieldData loaded = BattlefieldData.load(data.save(new CompoundTag()));
        LoadoutPresetDef after = loaded.preset(Faction.BRAVO, SoldierClass.RECON, def.id());
        assertEquals("狙击套", after.displayName());
        assertEquals("tacz:m24", after.slot(LoadoutSlot.PRIMARY));
        assertEquals(40, after.ammoOf(LoadoutSlot.PRIMARY));
        assertEquals("{GunId:\"tacz:m24\",AttachmentSCOPE:{}}", after.gunNbtOf(LoadoutSlot.PRIMARY),
                "枪械配件快照应随 NBT 往返");
        assertEquals("minecraft:iron_sword", after.slot(LoadoutSlot.MELEE), "近战槽应随 NBT 往返");
        assertEquals("minecraft:iron_helmet", after.armor().helmet());
        assertNull(after.armor().legs(), "未穿的护腿应为 null");
    }

    @Test
    void presetSlotsIncludeMelee() {
        // 近战槽在配装数据模型内：PRESET_SLOTS 驱动发装/预览/界面/命令回显，删掉它会静默丢近战。
        assertTrue(LoadoutPresetDef.PRESET_SLOTS.contains(LoadoutSlot.MELEE), "PRESET_SLOTS 必须包含近战槽");
        assertEquals(5, LoadoutPresetDef.PRESET_SLOTS.size());
        // 近战是普通物品槽（非枪械槽）：withAmmo 对近战应无效果。
        LoadoutPresetDef def = new BattlefieldData().createPreset(Faction.ALPHA, SoldierClass.ASSAULT, "测试")
                .withSlot(LoadoutSlot.MELEE, "minecraft:iron_sword");
        assertEquals(0, def.withAmmo(LoadoutSlot.MELEE, 99).ammoOf(LoadoutSlot.MELEE), "近战不是枪械槽，不应有虚拟弹药");
    }

    @Test
    void deletePresetRemovesEmptyGroup() {
        BattlefieldData data = new BattlefieldData();
        LoadoutPresetDef def = data.createPreset(Faction.ALPHA, SoldierClass.MEDIC, "医疗");
        assertTrue(data.deletePresetDef(Faction.ALPHA, SoldierClass.MEDIC, def.id()));
        assertTrue(data.presetsFor(Faction.ALPHA, SoldierClass.MEDIC).isEmpty());
        assertFalse(data.deletePresetDef(Faction.ALPHA, SoldierClass.MEDIC, "nope"), "删不存在应返回 false");
    }
    @Test
    void ticketsRequiredForReadinessAndRoundTrip() {
        BattlefieldData data = new BattlefieldData();
        assertFalse(data.hasTickets());
        assertFalse(data.isConquestReady(), "未设置票数不应就绪");
        data.setTickets(500);
        assertTrue(data.hasTickets());

        BattlefieldData loaded = BattlefieldData.load(data.save(new CompoundTag()));
        assertEquals(500, loaded.ticketsRaw());
    }

    @Test
    void returnPointRoundTrips() {
        BattlefieldData data = new BattlefieldData();
        data.setReturnPoint(new BattlefieldData.ReturnPoint(
                "minecraft:overworld",
                100.5, 64.0, -200.25, 90.0f, 0.0f));
        BattlefieldData loaded = BattlefieldData.load(data.save(new CompoundTag()));
        assertEquals(100.5, loaded.returnPoint().x(), 0.001);
        assertEquals("minecraft:overworld", loaded.returnPoint().dimension());
        data.setReturnPoint(null);
        assertNull(BattlefieldData.load(data.save(new CompoundTag())).returnPoint());
        data.setReturnPoint(null);
        assertNull(BattlefieldData.load(data.save(new CompoundTag())).returnPoint());
    }

    @Test
    void vanillaHudModeRoundTrips() {
        BattlefieldData data = new BattlefieldData();
        assertFalse(data.vanillaHudMode(), "默认应为自绘武器栏（非原版快捷栏）");
        data.setVanillaHudMode(true);
        assertTrue(BattlefieldData.load(data.save(new CompoundTag())).vanillaHudMode(),
                "原版快捷栏开关应随 NBT 往返");
data.setVanillaHudMode(false);
assertFalse(BattlefieldData.load(data.save(new CompoundTag())).vanillaHudMode());
}
}