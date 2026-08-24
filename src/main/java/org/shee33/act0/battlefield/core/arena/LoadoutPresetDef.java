package org.shee33.act0.battlefield.core.arena;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一套<b>管理员预设</b>的配装定义（配装即目录：物品定义直接内嵌在配装里，不另设物品池）。
 *
 * <p>作用域：地图 × 阵营 × 兵种。一套配装 = 4 个装备槽位（主武器/副武器/道具/投掷物）+ 枪械的
 * 虚拟弹药 + 服装（头盔/胸甲/护腿/靴子，取自管理员上架时身穿的装备）。
 *
 * <p>玩家<b>不能编辑</b>配装内容，只能选择（每兵种选一套）与预览。
 *
 * @param id          稳定标识（创建时生成，之后不改）；改名只动 {@link #displayName}
 * @param displayName 管理员可改的显示名
 * @param slots       槽位 → 物品注册 ID（武器槽必须是 TaCZ 枪械）
 * @param ammo        枪械槽的虚拟弹药数；只对武器槽有意义
 * @param armor       服装（可空）
 */
public record LoadoutPresetDef(String id, String displayName,
                               Map<LoadoutSlot, String> slots,
                               Map<LoadoutSlot, Integer> ammo,
                               ArmorSet armor) {

    /** 配装包含的四个槽位（主武器/副武器/道具/投掷物；近战不在配装范畴）。 */
    public static final List<LoadoutSlot> PRESET_SLOTS = List.of(
            LoadoutSlot.PRIMARY, LoadoutSlot.SECONDARY,
            LoadoutSlot.GADGET_1, LoadoutSlot.GADGET_2);

    /** 服装的四件套（对应原版盔甲栏）。 */
    public record ArmorSet(@Nullable String helmet, @Nullable String chest,
                           @Nullable String legs, @Nullable String boots) {

        public static final ArmorSet EMPTY = new ArmorSet(null, null, null, null);

        public ArmorSet {
            // 保留原样即可，调用方负责规范化（空串→null 在构造处做）。
        }

        public static ArmorSet of(@Nullable String helmet, @Nullable String chest,
                                  @Nullable String legs, @Nullable String boots) {
            return new ArmorSet(blankToNull(helmet), blankToNull(chest),
                    blankToNull(legs), blankToNull(boots));
        }

        public boolean isEmpty() {
            return helmet == null && chest == null && legs == null && boots == null;
        }

        private static @Nullable String blankToNull(@Nullable String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    public LoadoutPresetDef {
        Objects.requireNonNull(id, "id must not be null");
        displayName = displayName == null ? "" : displayName.trim();
        Objects.requireNonNull(slots, "slots must not be null");
        Objects.requireNonNull(ammo, "ammo must not be null");
        armor = armor == null ? ArmorSet.EMPTY : armor;
        slots = copyEnum(slots);
        ammo = copyEnum(ammo);
    }

    /** {@code new EnumMap<>(空map)} 会抛 IAE，空 map 直接原样返回。 */
    private static <K extends Enum<K>, V> Map<K, V> copyEnum(Map<K, V> src) {
        return src.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(src));
    }

    /** 某个槽位的物品 ID；未配置返回 {@code null}。 */
    @Nullable
    public String slot(LoadoutSlot slot) {
        return slots.get(slot);
    }

    /** 某个槽位的虚拟弹药；非枪械槽或未配置返回 {@code 0}。 */
    public int ammoOf(LoadoutSlot slot) {
        Integer a = ammo.get(slot);
        return a == null ? 0 : a;
    }

    /** 是否为枪械槽（配装里的主/副武器槽）。 */
    public static boolean isWeaponSlot(LoadoutSlot slot) {
        return slot == LoadoutSlot.PRIMARY || slot == LoadoutSlot.SECONDARY;
    }

    /** 返回把某个槽位设为指定物品的新实例；{@code null} 清除该槽位（弹药一并清）。 */
    public LoadoutPresetDef withSlot(LoadoutSlot slot, @Nullable String itemId) {
        Map<LoadoutSlot, String> nextSlots = new EnumMap<>(LoadoutSlot.class);
        nextSlots.putAll(slots);
        Map<LoadoutSlot, Integer> nextAmmo = new EnumMap<>(LoadoutSlot.class);
        nextAmmo.putAll(ammo);
        if (itemId == null || itemId.isBlank()) {
            nextSlots.remove(slot);
            nextAmmo.remove(slot);
        } else {
            nextSlots.put(slot, itemId.trim());
        }
        return new LoadoutPresetDef(id, displayName, nextSlots, nextAmmo, armor);
    }

    /** 返回设置某枪械槽虚拟弹药的新实例；非枪械槽忽略。 */
    public LoadoutPresetDef withAmmo(LoadoutSlot slot, int count) {
        if (!isWeaponSlot(slot)) {
            return this;
        }
        Map<LoadoutSlot, Integer> nextAmmo = new EnumMap<>(LoadoutSlot.class);
        nextAmmo.putAll(ammo);
        if (count <= 0) {
            nextAmmo.remove(slot);
        } else {
            nextAmmo.put(slot, count);
        }
        return new LoadoutPresetDef(id, displayName, slots, nextAmmo, armor);
    }

    /** 返回替换服装的新实例。 */
    public LoadoutPresetDef withArmor(ArmorSet newArmor) {
        return new LoadoutPresetDef(id, displayName, slots, ammo,
                newArmor == null ? ArmorSet.EMPTY : newArmor);
    }

    /** 返回改了显示名的新实例。 */
    public LoadoutPresetDef withDisplayName(String newName) {
        String n = newName == null ? "" : newName.trim();
        return n.equals(displayName) ? this : new LoadoutPresetDef(id, n, slots, ammo, armor);
    }

    /** 是否完全没有配置任何内容（四个槽位全空且无服装）。 */
    public boolean isEmpty() {
        return slots.isEmpty() && armor.isEmpty();
    }
}
