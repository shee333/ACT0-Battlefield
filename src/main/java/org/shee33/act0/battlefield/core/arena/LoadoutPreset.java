package org.shee33.act0.battlefield.core.arena;

import java.util.Objects;

/**
 * 一套具名配装：玩家自定义的名字 + 槽位选择。
 *
 * <p>名字为空表示"尚未命名"，展示层回退显示默认名「配装 N」（N 为该套在兵种组里的序号+1）。
 * 持久化时空名不写盘，避免给 16 个默认格子各存一份空字符串。
 *
 * @param name    玩家自定义名；空串 = 未命名
 * @param loadout 该套的槽位选择（主武器/副武器/近战/道具）
 */
public record LoadoutPreset(String name, PlayerArenaLoadout loadout) {

    /** 什么都没配的空套。 */
    public static final LoadoutPreset EMPTY = new LoadoutPreset("", PlayerArenaLoadout.EMPTY);

    public LoadoutPreset {
        Objects.requireNonNull(loadout, "loadout must not be null");
        name = name == null ? "" : name.trim();
    }

    /** 玩家自定义名；空串 = 未命名。 */
    public String name() {
        return name;
    }

    /** 是否处于"从未被碰过"的初始状态（无名且无选择）。 */
    public boolean isEmpty() {
        return name.isEmpty() && loadout.isEmpty();
    }

    /** 返回改了名字的新实例；空串或原名不变时返回自身。 */
    public LoadoutPreset withName(String newName) {
        String n = newName == null ? "" : newName.trim();
        return n.equals(name) ? this : new LoadoutPreset(n, loadout);
    }

    /** 返回改动了某个槽位选择的新实例。 */
    public LoadoutPreset withPick(LoadoutSlot slot, String id) {
        return new LoadoutPreset(name, loadout.with(slot, id));
    }
}
