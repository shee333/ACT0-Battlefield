package org.shee33.act0.battlefield.core.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一个兵种下的配装组：当前激活的配装索引 + 固定 {@link #PRESET_COUNT} 套具名配装。
 *
 * <p>配装网格（配装界面 4 列 × 4 行）的一行：玩家为每个兵种准备 4 套可命名配装，其中
 * 当前激活的那套决定部署/出生时发什么装。激活索引与各套名字/选择一起持久化。
 */
public final class ClassLoadouts {

    /** 每个兵种下配装套数（配装网格的列数，4 兵种 × 4 套 = 16 格）。 */
    public static final int PRESET_COUNT = 4;

    private final int activeIndex;
    private final List<LoadoutPreset> presets;

    public ClassLoadouts(int activeIndex, List<LoadoutPreset> presets) {
        Objects.requireNonNull(presets, "presets must not be null");
        List<LoadoutPreset> copy = new ArrayList<>(PRESET_COUNT);
        for (int i = 0; i < PRESET_COUNT; i++) {
            LoadoutPreset p = i < presets.size() ? presets.get(i) : null;
            copy.add(p != null ? p : LoadoutPreset.EMPTY);
        }
        this.presets = Collections.unmodifiableList(copy);
        this.activeIndex = clampIndex(activeIndex);
    }

    /** 从未被碰过的初始组：激活第 0 套，全部空套。 */
    public static ClassLoadouts initial() {
        return new ClassLoadouts(0, List.of());
    }

    /** 当前激活的配装序号 [0,{@link #PRESET_COUNT})。 */
    public int activeIndex() {
        return activeIndex;
    }

    /** 全部配装，恒为 {@link #PRESET_COUNT} 套（缺位补空套）。 */
    public List<LoadoutPreset> presets() {
        return presets;
    }

    /** 第 {@code index} 套配装；越界钳制到边界。 */
    public LoadoutPreset preset(int index) {
        return presets.get(clampIndex(index));
    }

    /** 当前激活套的槽位选择（部署侧语义：该兵种现在生效的配装）。 */
    public PlayerArenaLoadout active() {
        return preset(activeIndex).loadout();
    }

    /** 返回把激活索引改到 {@code index} 的新实例；相同则返回自身。 */
    public ClassLoadouts withActive(int index) {
        int i = clampIndex(index);
        return i == activeIndex ? this : new ClassLoadouts(i, presets);
    }

    /** 返回把第 {@code index} 套替换为 {@code preset} 的新实例。 */
    public ClassLoadouts withPreset(int index, LoadoutPreset preset) {
        List<LoadoutPreset> next = new ArrayList<>(presets);
        next.set(clampIndex(index), preset != null ? preset : LoadoutPreset.EMPTY);
        return new ClassLoadouts(activeIndex, next);
    }

    /** 返回改动第 {@code index} 套某个槽位选择的新实例。 */
    public ClassLoadouts withPick(int index, LoadoutSlot slot, String id) {
        return withPreset(index, preset(index).withPick(slot, id));
    }

    /** 返回给第 {@code index} 套改名的新实例。 */
    public ClassLoadouts withName(int index, String name) {
        return withPreset(index, preset(index).withName(name));
    }

    /** 是否完全没有需要持久化的状态（未激活别的套、全部空套且未命名）。 */
    public boolean isEmpty() {
        if (activeIndex != 0) {
            return false;
        }
        for (LoadoutPreset p : presets) {
            if (!p.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static int clampIndex(int index) {
        return Math.max(0, Math.min(index, PRESET_COUNT - 1));
    }
}
