package org.shee33.act0.battlefield.core.arena;

import org.shee33.act0.battlefield.core.SoldierClass;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一名玩家在某一张地图上的全部配装状态：当前选中的兵种，以及<b>每个兵种各自一套</b>槽位选择。
 *
 * <p>四个兵种共用地图的武器池，但各记一套选择——玩家在同一张图上切到支援兵不该被迫重选主武器。
 *
 * @param selected 当前兵种
 * @param byClass  兵种 → 该兵种的槽位选择；没选过的兵种不出现在 map 里
 */
public record PlayerMapLoadout(SoldierClass selected, Map<SoldierClass, PlayerArenaLoadout> byClass) {

    /** 什么都没选过的初始状态。 */
    public static final PlayerMapLoadout EMPTY =
            new PlayerMapLoadout(SoldierClass.DEFAULT, Map.of());

    public PlayerMapLoadout {
        Objects.requireNonNull(selected, "selected must not be null");
        Objects.requireNonNull(byClass, "byClass must not be null");
        Map<SoldierClass, PlayerArenaLoadout> copy = new EnumMap<>(SoldierClass.class);
        for (Map.Entry<SoldierClass, PlayerArenaLoadout> e : byClass.entrySet()) {
            Objects.requireNonNull(e.getKey(), "class must not be null");
            PlayerArenaLoadout picks = e.getValue();
            if (picks != null && !picks.isEmpty()) {
                copy.put(e.getKey(), picks);
            }
        }
        byClass = Collections.unmodifiableMap(copy);
    }

    /** 指定兵种的槽位选择；没选过返回 {@link PlayerArenaLoadout#EMPTY}，永不为 {@code null}。 */
    public PlayerArenaLoadout loadout(SoldierClass soldierClass) {
        Objects.requireNonNull(soldierClass, "soldierClass must not be null");
        PlayerArenaLoadout picks = byClass.get(soldierClass);
        return picks != null ? picks : PlayerArenaLoadout.EMPTY;
    }

    /** 当前兵种的槽位选择。 */
    public PlayerArenaLoadout current() {
        return loadout(selected);
    }

    /** 切换当前兵种，各兵种已存的选择原样保留。 */
    public PlayerMapLoadout withSelected(SoldierClass soldierClass) {
        Objects.requireNonNull(soldierClass, "soldierClass must not be null");
        return soldierClass == selected ? this : new PlayerMapLoadout(soldierClass, byClass);
    }

    /** 改动指定兵种某个槽位的选择；传空 ID 等同于清除该槽位。 */
    public PlayerMapLoadout withPick(SoldierClass soldierClass, LoadoutSlot slot, String id) {
        Objects.requireNonNull(soldierClass, "soldierClass must not be null");
        Map<SoldierClass, PlayerArenaLoadout> next = new EnumMap<>(SoldierClass.class);
        next.putAll(byClass);
        next.put(soldierClass, loadout(soldierClass).with(slot, id));
        return new PlayerMapLoadout(selected, next);
    }

    /**
     * 是否完全没有需要持久化的状态。
     *
     * <p>当前兵种不是默认值时<b>不算空</b>——那也是玩家做过的一次选择，丢掉会让他每次开局都被
     * 打回突击兵。
     */
    public boolean isEmpty() {
        return selected == SoldierClass.DEFAULT && byClass.isEmpty();
    }
}
