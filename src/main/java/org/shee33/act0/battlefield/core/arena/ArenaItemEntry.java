package org.shee33.act0.battlefield.core.arena;

import java.util.Objects;

/**
 * 地图道具池里的一条道具（弹药箱/医疗箱/医疗针等普通物品，非 TaCZ 枪械）。
 *
 * <p>与 {@link ArenaWeaponEntry} 刻意分成两个类型而不是共用一个"条目"抽象：武器要记虚拟备弹、
 * 录入时必须是 TaCZ 枪；道具要记发放数量、录入时是任意物品。两者的校验规则和录入路径都不同，
 * 合并成一个类型只会得到一半字段永远为空的记录。
 *
 * @param itemId      物品注册 ID（形如 {@code act0_battlefield:medic_syringe}）
 * @param displayName 命令回显与部署面板展示用的名字，录入时取物品的显示名
 * @param count       出生时发放的数量
 */
public record ArenaItemEntry(String itemId, String displayName, int count) {

    /** 单条道具允许发放的最大数量，与原版一组上限对齐。 */
    public static final int MAX_COUNT = 64;

    public ArenaItemEntry {
        Objects.requireNonNull(itemId, "itemId must not be null");
        if (itemId.isBlank()) throw new IllegalArgumentException("itemId must not be blank");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (displayName.isBlank()) throw new IllegalArgumentException("displayName must not be blank");
        if (count < 1) throw new IllegalArgumentException("count must be >= 1: " + count);
        if (count > MAX_COUNT) throw new IllegalArgumentException("count must be <= " + MAX_COUNT + ": " + count);
    }
}
