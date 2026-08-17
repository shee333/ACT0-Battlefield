package org.shee33.act0.battlefield.core.arena;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一张地图的武器与道具目录。MC-free，可直接单测。
 *
 * <p><b>为什么容量上限按槽位而不是按类别</b>：部署界面某个槽位的可选列表 = 该槽位下所有类别拼接，
 * 而这个列表要走网络（{@code DeploySlotOptionsDto} 线上限 128 条）。若按类别各限 64，主武器槽
 * 6 个类别就能凑出 384 条，超出部分会在编码时被静默截断——玩家看到的池子与管理员配的不一致，
 * 且没有任何报错。按槽位限 64 则天然远低于线上限，录满时命令会明确返回 {@code FULL}。
 *
 * <p><b>为什么武器查重跨类别</b>：同一把枪出现在两个类别里，在同槽位的可选列表中就会重复两次，
 * 而 {@code DeployLoadoutDto} 用 ID 字符串做选择键，重复项无法区分。跨类别查重把这种配置事故
 * 挡在录入时刻。
 */
public final class ArenaCatalog {

    /** 单个槽位可选项的数量上限。 */
    public static final int MAX_PER_SLOT = 64;

    /** 目录编辑操作的结果。 */
    public enum EditResult {
        /** 操作成功。 */
        OK,
        /** 该 ID 已存在（武器为跨类别查重）。 */
        DUPLICATE,
        /** 目标槽位可选项已达 {@link #MAX_PER_SLOT}。 */
        FULL,
        /** 槽位类型不符（例如往武器槽录道具）。 */
        WRONG_SLOT,
        /** 要移除的条目不存在。 */
        NOT_FOUND
    }

    private final Map<WeaponCategory, List<ArenaWeaponEntry>> weapons = new EnumMap<>(WeaponCategory.class);
    private final Map<LoadoutSlot, List<ArenaItemEntry>> items = new EnumMap<>(LoadoutSlot.class);

    // ---- 武器 ----

    /**
     * 往指定类别录入一把武器。
     *
     * @return {@link EditResult#DUPLICATE} 表示该枪已在某个类别中；{@link EditResult#FULL} 表示
     *         该类别对应槽位的可选项已满
     */
    public EditResult addWeapon(WeaponCategory category, ArenaWeaponEntry entry) {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (categoryOf(entry.gunId()) != null) {
            return EditResult.DUPLICATE;
        }
        if (weaponsForSlot(category.slot()).size() >= MAX_PER_SLOT) {
            return EditResult.FULL;
        }
        weapons.computeIfAbsent(category, k -> new ArrayList<>()).add(entry);
        return EditResult.OK;
    }

    /** 从指定类别移除一把武器。 */
    public EditResult removeWeapon(WeaponCategory category, String gunId) {
        Objects.requireNonNull(category, "category must not be null");
        List<ArenaWeaponEntry> list = weapons.get(category);
        if (list == null || gunId == null) {
            return EditResult.NOT_FOUND;
        }
        boolean removed = list.removeIf(e -> e.gunId().equals(gunId));
        if (list.isEmpty()) {
            weapons.remove(category);
        }
        return removed ? EditResult.OK : EditResult.NOT_FOUND;
    }

    /** 指定类别的武器列表，按录入顺序。 */
    public List<ArenaWeaponEntry> weapons(WeaponCategory category) {
        List<ArenaWeaponEntry> list = weapons.get(category);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 某把枪当前所属类别；未录入返回 {@code null}。 */
    @Nullable
    public WeaponCategory categoryOf(@Nullable String gunId) {
        if (gunId == null) {
            return null;
        }
        for (Map.Entry<WeaponCategory, List<ArenaWeaponEntry>> e : weapons.entrySet()) {
            for (ArenaWeaponEntry entry : e.getValue()) {
                if (entry.gunId().equals(gunId)) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /** 会进入指定槽位的全部武器，按 {@link WeaponCategory#forSlot} 的类别顺序拼接。 */
    public List<ArenaWeaponEntry> weaponsForSlot(LoadoutSlot slot) {
        List<ArenaWeaponEntry> out = new ArrayList<>();
        for (WeaponCategory c : WeaponCategory.forSlot(slot)) {
            List<ArenaWeaponEntry> list = weapons.get(c);
            if (list != null) {
                out.addAll(list);
            }
        }
        return List.copyOf(out);
    }

    /** 按枪械 ID 查找武器条目；未录入返回 {@code null}。 */
    @Nullable
    public ArenaWeaponEntry findWeapon(@Nullable String gunId) {
        WeaponCategory c = categoryOf(gunId);
        if (c == null) {
            return null;
        }
        for (ArenaWeaponEntry entry : weapons.get(c)) {
            if (entry.gunId().equals(gunId)) {
                return entry;
            }
        }
        return null;
    }

    // ---- 道具 ----

    /**
     * 往指定道具槽录入一件道具。
     *
     * @return {@link EditResult#WRONG_SLOT} 表示传入的不是道具槽
     */
    public EditResult addItem(LoadoutSlot slot, ArenaItemEntry entry) {
        Objects.requireNonNull(slot, "slot must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (!slot.isGadget()) {
            return EditResult.WRONG_SLOT;
        }
        List<ArenaItemEntry> list = items.computeIfAbsent(slot, k -> new ArrayList<>());
        for (ArenaItemEntry e : list) {
            if (e.itemId().equals(entry.itemId())) {
                return EditResult.DUPLICATE;
            }
        }
        if (list.size() >= MAX_PER_SLOT) {
            return EditResult.FULL;
        }
        list.add(entry);
        return EditResult.OK;
    }

    /** 从指定道具槽移除一件道具。 */
    public EditResult removeItem(LoadoutSlot slot, String itemId) {
        Objects.requireNonNull(slot, "slot must not be null");
        if (!slot.isGadget()) {
            return EditResult.WRONG_SLOT;
        }
        List<ArenaItemEntry> list = items.get(slot);
        if (list == null || itemId == null) {
            return EditResult.NOT_FOUND;
        }
        boolean removed = list.removeIf(e -> e.itemId().equals(itemId));
        if (list.isEmpty()) {
            items.remove(slot);
        }
        return removed ? EditResult.OK : EditResult.NOT_FOUND;
    }

    /** 指定道具槽的道具列表，按录入顺序。 */
    public List<ArenaItemEntry> items(LoadoutSlot slot) {
        List<ArenaItemEntry> list = items.get(slot);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 按物品 ID 在指定道具槽查找条目；未录入返回 {@code null}。 */
    @Nullable
    public ArenaItemEntry findItem(LoadoutSlot slot, @Nullable String itemId) {
        if (itemId == null) {
            return null;
        }
        for (ArenaItemEntry e : items(slot)) {
            if (e.itemId().equals(itemId)) {
                return e;
            }
        }
        return null;
    }

    // ---- 槽位视图（部署界面与出生发装共用） ----

    /** 指定槽位的全部可选 ID，武器槽给枪械 ID，道具槽给物品 ID。 */
    public List<String> optionIdsForSlot(LoadoutSlot slot) {
        List<String> out = new ArrayList<>();
        if (slot.isGadget()) {
            for (ArenaItemEntry e : items(slot)) {
                out.add(e.itemId());
            }
        } else {
            for (ArenaWeaponEntry e : weaponsForSlot(slot)) {
                out.add(e.gunId());
            }
        }
        return List.copyOf(out);
    }

    /** 指定槽位的默认可选项（列表首项）；该槽位没配任何东西时返回 {@code null}。 */
    @Nullable
    public String defaultOptionForSlot(LoadoutSlot slot) {
        List<String> ids = optionIdsForSlot(slot);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 指定 ID 是否是该槽位的合法可选项。 */
    public boolean hasOption(LoadoutSlot slot, @Nullable String id) {
        return id != null && optionIdsForSlot(slot).contains(id);
    }

    /** 目录是否完全为空（一把枪一件道具都没配）。 */
    public boolean isEmpty() {
        return weapons.isEmpty() && items.isEmpty();
    }

    /** 已录入的条目总数，供命令回显概览。 */
    public int totalEntries() {
        int n = 0;
        for (List<ArenaWeaponEntry> list : weapons.values()) {
            n += list.size();
        }
        for (List<ArenaItemEntry> list : items.values()) {
            n += list.size();
        }
        return n;
    }
}
