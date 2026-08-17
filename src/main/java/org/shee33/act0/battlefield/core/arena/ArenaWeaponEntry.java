package org.shee33.act0.battlefield.core.arena;

import java.util.Objects;

/**
 * 地图武器池里的一条武器。
 *
 * <p><b>dummyAmmo 是什么</b>：TaCZ 的"虚拟备弹"——直接写在枪械物品上的备用弹药数，不占背包格子。
 * 大战场用它替代真实弹药箱物品，玩家换弹时消耗这个数字。取 {@code 0} 表示不启用虚拟备弹、
 * 回退到 TaCZ 的背包弹药模式；这样"没填"与"显式跟随背包"天然同义，省掉一个哨兵值
 * （与 {@code MatchCapacity} 的 0 语义一致）。
 *
 * @param gunId       TaCZ 枪械 ID（形如 {@code tacz:ak47}），录入时从管理员主手的枪上读取
 * @param displayName 命令回显与部署面板展示用的名字，录入时取物品的显示名
 * @param dummyAmmo   虚拟备弹数；{@code 0} 表示不启用，走 TaCZ 背包弹药
 */
public record ArenaWeaponEntry(String gunId, String displayName, int dummyAmmo) {

    /** 单条武器允许的最大虚拟备弹数。上限存在的意义是拦住手滑多打几个零导致的换弹无限。 */
    public static final int MAX_DUMMY_AMMO = 9999;

    public ArenaWeaponEntry {
        Objects.requireNonNull(gunId, "gunId must not be null");
        if (gunId.isBlank()) throw new IllegalArgumentException("gunId must not be blank");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (displayName.isBlank()) throw new IllegalArgumentException("displayName must not be blank");
        if (dummyAmmo < 0) throw new IllegalArgumentException("dummyAmmo must be >= 0: " + dummyAmmo);
        if (dummyAmmo > MAX_DUMMY_AMMO) {
            throw new IllegalArgumentException("dummyAmmo must be <= " + MAX_DUMMY_AMMO + ": " + dummyAmmo);
        }
    }

    /** 是否启用虚拟备弹。 */
    public boolean usesDummyAmmo() {
        return dummyAmmo > 0;
    }
}
