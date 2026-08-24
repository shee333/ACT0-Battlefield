package org.shee33.act0.battlefield.core.arena;

import org.shee33.act0.battlefield.core.SoldierClass;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一名玩家在某一张地图上的全部配装状态：当前选中的兵种，以及<b>每个兵种一组配装</b>
 * （每组固定 {@link ClassLoadouts#PRESET_COUNT} 套可命名配装，见 {@link ClassLoadouts}）。
 *
 * <p>四个兵种共用地图的武器池。部署/出生时生效的是"当前兵种当前激活的那套"——
 * {@link #loadout(SoldierClass)} 返回激活套的槽位选择，部署侧无需感知配装网格的存在。
 *
 * @param selected 当前兵种
 * @param byClass  兵种 → 该兵种的配装组；从未被碰过的兵种不出现（读取时按初始组回落）
 */
public record PlayerMapLoadout(SoldierClass selected, Map<SoldierClass, ClassLoadouts> byClass) {

    /** 什么都没选过的初始状态。 */
    public static final PlayerMapLoadout EMPTY =
            new PlayerMapLoadout(SoldierClass.DEFAULT, Map.of());

    public PlayerMapLoadout {
        Objects.requireNonNull(selected, "selected must not be null");
        Objects.requireNonNull(byClass, "byClass must not be null");
        Map<SoldierClass, ClassLoadouts> copy = new EnumMap<>(SoldierClass.class);
        for (Map.Entry<SoldierClass, ClassLoadouts> e : byClass.entrySet()) {
            Objects.requireNonNull(e.getKey(), "class must not be null");
            ClassLoadouts group = e.getValue();
            if (group != null && !group.isEmpty()) {
                copy.put(e.getKey(), group);
            }
        }
        byClass = Collections.unmodifiableMap(copy);
    }

    /** 指定兵种的配装组；从未被碰过返回 {@link ClassLoadouts#initial()}，永不为 {@code null}。 */
    public ClassLoadouts classLoadouts(SoldierClass soldierClass) {
        Objects.requireNonNull(soldierClass, "soldierClass must not be null");
        ClassLoadouts group = byClass.get(soldierClass);
        return group != null ? group : ClassLoadouts.initial();
    }

    /**
     * 指定兵种当前激活套的槽位选择（部署侧语义）；该兵种未配置时返回空。
     */
    public PlayerArenaLoadout loadout(SoldierClass soldierClass) {
        return classLoadouts(soldierClass).active();
    }

    /** 当前兵种激活套的槽位选择。 */
    public PlayerArenaLoadout current() {
        return loadout(selected);
    }

    /** 指定兵种当前激活的配装序号。 */
    public int activePresetIndex(SoldierClass soldierClass) {
        return classLoadouts(soldierClass).activeIndex();
    }

    /** 指定兵种第 {@code index} 套配装。 */
    public LoadoutPreset preset(SoldierClass soldierClass, int index) {
        return classLoadouts(soldierClass).preset(index);
    }

    /** 切换当前兵种，各兵种已存的配装组原样保留。 */
    public PlayerMapLoadout withSelected(SoldierClass soldierClass) {
        Objects.requireNonNull(soldierClass, "soldierClass must not be null");
        return soldierClass == selected ? this : new PlayerMapLoadout(soldierClass, byClass);
    }

    /** 把指定兵种的激活配装切到第 {@code index} 套。 */
    public PlayerMapLoadout withActivePreset(SoldierClass soldierClass, int index) {
        return withClass(soldierClass, classLoadouts(soldierClass).withActive(index));
    }

    /** 改动指定兵种第 {@code index} 套配装某个槽位的选择；传 null/空 ID 等同于清除该槽位。 */
    public PlayerMapLoadout withPresetPick(SoldierClass soldierClass, int index, LoadoutSlot slot, String id) {
        return withClass(soldierClass, classLoadouts(soldierClass).withPick(index, slot, id));
    }

    /** 给指定兵种第 {@code index} 套配装命名；空串 = 恢复默认名。 */
    public PlayerMapLoadout withPresetName(SoldierClass soldierClass, int index, String name) {
        return withClass(soldierClass, classLoadouts(soldierClass).withName(index, name));
    }

    /**
     * 改动指定兵种<b>激活套</b>某个槽位的选择（部署界面换装走的路径，兼容旧语义）。
     */
    public PlayerMapLoadout withPick(SoldierClass soldierClass, LoadoutSlot slot, String id) {
        return withPresetPick(soldierClass, classLoadouts(soldierClass).activeIndex(), slot, id);
    }

    /**
     * 是否完全没有需要持久化的状态。
     *
     * <p>当前兵种不是默认值时<b>不算</b>——那也是玩家做过的一次选择，丢掉会让他每次开局都被
     * 打回突击兵。
     */
    public boolean isEmpty() {
        return selected == SoldierClass.DEFAULT && byClass.isEmpty();
    }

    /** 更新某兵种的配装组；变回初始态（空组）时从 map 移除，避免存档堆空壳。 */
    private PlayerMapLoadout withClass(SoldierClass soldierClass, ClassLoadouts group) {
        Map<SoldierClass, ClassLoadouts> next = new EnumMap<>(SoldierClass.class);
        next.putAll(byClass);
        if (group.isEmpty()) {
            next.remove(soldierClass);
        } else {
            next.put(soldierClass, group);
        }
        return new PlayerMapLoadout(selected, next);
    }
}
