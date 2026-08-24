package org.shee33.act0.battlefield.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog;
import org.shee33.act0.battlefield.core.arena.ArenaItemEntry;
import org.shee33.act0.battlefield.core.arena.ArenaWeaponEntry;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.ClassLoadouts;
import org.shee33.act0.battlefield.core.arena.LoadoutPreset;
import org.shee33.act0.battlefield.core.arena.PlayerArenaLoadout;
import org.shee33.act0.battlefield.core.arena.PlayerMapLoadout;
import org.shee33.act0.battlefield.core.arena.WeaponCategory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
/**
 * 地图目录与玩家配装选择的 NBT 编解码。
 *
 * <p><b>读取一律容错，绝不抛异常</b>：这些数据在世界加载时反序列化，一条脏记录若抛出异常会连带
 * 整个存档读不出来。因此未知的类别/槽位字面量、越界的备弹数、空白 ID 全部<b>跳过该条</b>继续读下一条。
 * 代价是管理员改名枚举后旧条目会静默消失——但这比开不了服好得多，且命令里 {@code list} 能立刻看出少了东西。
 *
 * <p>用类别/槽位的<b>字面量 ID</b>而非枚举序号做 NBT 键：序号会随枚举插入顺序变化而整体错位，
 * 把"步枪"悄悄读成"机枪"；字面量最多是读不出来。
 */
public final class ArenaCatalogCodec {

    private static final String KEY_WEAPONS = "weapons";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_AMMO = "ammo";
    private static final String KEY_COUNT = "count";
    private static final String KEY_CLASS = "class";
    private static final String KEY_BY_CLASS = "byClass";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_PRESETS = "presets";

    private ArenaCatalogCodec() {
    }

    /** 把一张地图的目录写成 NBT。空桶不落键，保持存档干净。 */
    public static CompoundTag save(ArenaCatalog catalog) {
        CompoundTag out = new CompoundTag();
        CompoundTag weapons = new CompoundTag();
        for (WeaponCategory category : WeaponCategory.values()) {
            var entries = catalog.weapons(category);
            if (entries.isEmpty()) {
                continue;
            }
            ListTag list = new ListTag();
            for (ArenaWeaponEntry e : entries) {
                CompoundTag t = new CompoundTag();
                t.putString(KEY_ID, e.gunId());
                t.putString(KEY_NAME, e.displayName());
                t.putInt(KEY_AMMO, e.dummyAmmo());
                list.add(t);
            }
            weapons.put(category.id(), list);
        }
        if (!weapons.isEmpty()) {
            out.put(KEY_WEAPONS, weapons);
        }
        CompoundTag items = new CompoundTag();
        for (LoadoutSlot slot : LoadoutSlot.gadgetSlots()) {
            var entries = catalog.items(slot);
            if (entries.isEmpty()) {
                continue;
            }
            ListTag list = new ListTag();
            for (ArenaItemEntry e : entries) {
                CompoundTag t = new CompoundTag();
                t.putString(KEY_ID, e.itemId());
                t.putString(KEY_NAME, e.displayName());
                t.putInt(KEY_COUNT, e.count());
                list.add(t);
            }
            items.put(slot.id(), list);
        }
        if (!items.isEmpty()) {
            out.put(KEY_ITEMS, items);
        }
        return out;
    }

    /** 从 NBT 读出一张地图的目录；任何读不懂的条目都跳过。 */
    public static ArenaCatalog load(CompoundTag tag) {
        ArenaCatalog catalog = new ArenaCatalog();
        CompoundTag weapons = tag.getCompound(KEY_WEAPONS);
        for (String key : weapons.getAllKeys()) {
            WeaponCategory category = WeaponCategory.byId(key);
            if (category == null) {
                continue;
            }
            ListTag list = weapons.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                try {
                    catalog.addWeapon(category, new ArenaWeaponEntry(
                            t.getString(KEY_ID), t.getString(KEY_NAME), t.getInt(KEY_AMMO)));
                } catch (RuntimeException ignored) {
                    // 脏条目跳过，不能因为一条坏数据让整个存档读不出来
                }
            }
        }
        CompoundTag items = tag.getCompound(KEY_ITEMS);
        for (String key : items.getAllKeys()) {
            LoadoutSlot slot = LoadoutSlot.byId(key);
            if (slot == null || !slot.isGadget()) {
                continue;
            }
            ListTag list = items.getList(key, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag t = list.getCompound(i);
                try {
                    catalog.addItem(slot, new ArenaItemEntry(
                            t.getString(KEY_ID), t.getString(KEY_NAME), t.getInt(KEY_COUNT)));
                } catch (RuntimeException ignored) {
                    // 同上
                }
            }
        }
        return catalog;
    }

    /** 把玩家在某张图上的选择写成"槽位字面量 → 选中 ID"的扁平 NBT。 */
    public static CompoundTag saveLoadout(PlayerArenaLoadout loadout) {
        CompoundTag out = new CompoundTag();
        for (Map.Entry<LoadoutSlot, String> e : loadout.picks().entrySet()) {
            out.putString(e.getKey().id(), e.getValue());
        }
        return out;
    }

    /** 读出玩家在某张图上的选择；未知槽位字面量跳过。 */
    public static PlayerArenaLoadout loadLoadout(CompoundTag tag) {
        Map<LoadoutSlot, String> picks = new EnumMap<>(LoadoutSlot.class);
        for (String key : tag.getAllKeys()) {
            LoadoutSlot slot = LoadoutSlot.byId(key);
            if (slot != null) {
                picks.put(slot, tag.getString(key));
            }
        }
        return picks.isEmpty() ? PlayerArenaLoadout.EMPTY : new PlayerArenaLoadout(picks);
    }

    /** 把一张图上的整套兵种配装写成 NBT。空兵种组不落键。 */
    public static CompoundTag saveMapLoadout(PlayerMapLoadout loadout) {
        CompoundTag out = new CompoundTag();
        out.putString(KEY_CLASS, loadout.selected().id());
        CompoundTag buckets = new CompoundTag();
        for (Map.Entry<SoldierClass, ClassLoadouts> e : loadout.byClass().entrySet()) {
            buckets.put(e.getKey().id(), saveClassLoadouts(e.getValue()));
        }
        out.put(KEY_BY_CLASS, buckets);
        return out;
    }

    /** 一个兵种的配装组 → NBT：{@code {active?, presets:[{name?, …槽位选择} × 4]}}。默认值不落键。 */
    private static CompoundTag saveClassLoadouts(ClassLoadouts group) {
        CompoundTag out = new CompoundTag();
        if (group.activeIndex() != 0) {
            out.putInt(KEY_ACTIVE, group.activeIndex());
        }
        ListTag presets = new ListTag();
        for (LoadoutPreset p : group.presets()) {
            CompoundTag t = saveLoadout(p.loadout());
            if (!p.name().isEmpty()) {
                t.putString(KEY_NAME, p.name());
            }
            presets.add(t);
        }
        out.put(KEY_PRESETS, presets);
        return out;
    }

    /**
     * 读出一张图上的整套兵种配装，兼容三代格式：
     * <ol>
     *   <li>0.2.9 及更早：整张图平铺一套选择（无 byClass）→ DEFAULT 兵种第 0 套；</li>
     *   <li>0.2.17 起：按兵种各平铺一套选择（byClass 下无 presets）→ 各兵种第 0 套；</li>
     *   <li>0.2.18 起：按兵种各存配装组（{@code active + presets}）。</li>
     * </ol>
     */
    public static PlayerMapLoadout loadMapLoadout(CompoundTag tag) {
        if (!tag.contains(KEY_BY_CLASS, Tag.TAG_COMPOUND)) {
            PlayerArenaLoadout legacy = loadLoadout(tag);
            return legacy.isEmpty()
                    ? PlayerMapLoadout.EMPTY
                    : new PlayerMapLoadout(SoldierClass.DEFAULT,
                            Map.of(SoldierClass.DEFAULT, legacyGroup(legacy)));
        }
        CompoundTag buckets = tag.getCompound(KEY_BY_CLASS);
        Map<SoldierClass, ClassLoadouts> byClass = new EnumMap<>(SoldierClass.class);
        for (String key : buckets.getAllKeys()) {
            SoldierClass soldierClass = SoldierClass.byId(key);
            if (soldierClass == null) {
                continue;
            }
            CompoundTag bucket = buckets.getCompound(key);
            ClassLoadouts group = bucket.contains(KEY_PRESETS, Tag.TAG_LIST)
                    ? loadClassLoadouts(bucket)
                    : legacyGroup(loadLoadout(bucket));
            if (!group.isEmpty()) {
                byClass.put(soldierClass, group);
            }
        }
        return new PlayerMapLoadout(SoldierClass.byIdOrDefault(tag.getString(KEY_CLASS)), byClass);
    }

    private static ClassLoadouts loadClassLoadouts(CompoundTag tag) {
        int active = tag.getInt(KEY_ACTIVE);
        ListTag presets = tag.getList(KEY_PRESETS, Tag.TAG_COMPOUND);
        List<LoadoutPreset> list = new ArrayList<>();
        for (int i = 0; i < presets.size(); i++) {
            CompoundTag t = presets.getCompound(i);
            String name = t.getString(KEY_NAME);
            CompoundTag picksTag = t.copy();
            picksTag.remove(KEY_NAME);
            list.add(new LoadoutPreset(name, loadLoadout(picksTag)));
        }
        return new ClassLoadouts(active, list);
    }

    /** 旧版"一个兵种一套选择"→ 迁移为第 0 套（名字留空 = 默认名）。 */
    private static ClassLoadouts legacyGroup(PlayerArenaLoadout legacy) {
        return legacy.isEmpty()
                ? ClassLoadouts.initial()
                : ClassLoadouts.initial().withPreset(0, new LoadoutPreset("", legacy));
    }
}
