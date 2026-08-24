package org.shee33.act0.battlefield.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.ClassLoadouts;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.PlayerArenaLoadout;
import org.shee33.act0.battlefield.core.arena.PlayerMapLoadout;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家在每张地图上的配装选择，按 {@code 玩家 UUID × 地图名} 持久化在<b>主世界</b>的 {@link SavedData} 里。
 *
 * <p>每张图下再按兵种分四套（见 {@link PlayerMapLoadout}）：四个兵种共用地图的武器池，但各记一套
 * 选择，切兵种不该逼玩家重选主武器。
 *
 * <p><b>为什么按玩家×地图而不是按玩家全局</b>：每张图的武器池是独立配置的，全局记一套的话换图之后
 * 大概率整套失效、玩家每局都要重新选。按图记住则"我在这张图惯用的枪"能一直保留。
 *
 * <p><b>为什么不存快照只存选择</b>：见 {@link PlayerArenaLoadout} 的说明——目录是唯一真相源，
 * 管理员下架一把枪后玩家读取时自动回落，不需要遍历改写任何玩家存档。
 */
public final class PlayerLoadoutStore extends SavedData {

    public static final String NAME = "act0_aew1_player_loadouts";

    private static final String KEY_PLAYERS = "players";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_MAPS = "maps";

    /** 玩家 UUID → (地图名 → 该图的整套兵种配装)。 */
    private final Map<UUID, Map<String, PlayerMapLoadout>> byPlayer = new LinkedHashMap<>();

    public static PlayerLoadoutStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayerLoadoutStore::load, PlayerLoadoutStore::new, NAME);
    }

    /** 该玩家在该图上的整套兵种配装；没记录返回 {@link PlayerMapLoadout#EMPTY}。 */
    public PlayerMapLoadout mapLoadout(UUID playerId, @Nullable String mapName) {
        String key = normalize(mapName);
        if (playerId == null || key == null) {
            return PlayerMapLoadout.EMPTY;
        }
        Map<String, PlayerMapLoadout> maps = byPlayer.get(playerId);
        if (maps == null) {
            return PlayerMapLoadout.EMPTY;
        }
        PlayerMapLoadout l = maps.get(key);
        return l != null ? l : PlayerMapLoadout.EMPTY;
    }

    public SoldierClass selectedClass(UUID playerId, @Nullable String mapName) {
        return mapLoadout(playerId, mapName).selected();
    }

    /** 该玩家在该图、该兵种下的槽位选择；没选过返回 {@link PlayerArenaLoadout#EMPTY}。 */
    public PlayerArenaLoadout loadout(UUID playerId, @Nullable String mapName, SoldierClass soldierClass) {
        return soldierClass == null
                ? PlayerArenaLoadout.EMPTY
                : mapLoadout(playerId, mapName).loadout(soldierClass);
    }

    /** 切换该玩家在该图上的兵种，各兵种已存的槽位选择保持不动。 */
    public PlayerMapLoadout setSelectedClass(UUID playerId, String mapName, SoldierClass soldierClass) {
        String key = normalize(mapName);
        if (playerId == null || key == null || soldierClass == null) {
            return PlayerMapLoadout.EMPTY;
        }
        PlayerMapLoadout next = mapLoadout(playerId, key).withSelected(soldierClass);
        put(playerId, key, next);
        return next;
    }

    /**
     * 记下该玩家在该图上某个槽位的选择。
     *
     * <p><b>只动被点的那个槽位</b>，不对整套选择做 {@code sanitize}。曾经这里顺手清理了当前
     * 目录里已失效的其余槽位，后果是：管理员临时下架一把枪、玩家在此期间改了任意<b>别的</b>槽位，
     * 那把枪的选择就被永久抹掉，管理员再上架回来也回不来了——这与 {@link PlayerArenaLoadout}
     * 承诺的"误删再加回，玩家原选择还在"直接矛盾。失效选择在读取时由
     * {@link PlayerArenaLoadout#resolve} 自动回落，本来就不需要写时清理。
     *
     * @param id 必须已由调用方对着目录校验过；这里不再重复校验，也就不会把非法提交
     *           误当成"清除该槽位"
     */
    public PlayerArenaLoadout setPick(UUID playerId, String mapName, SoldierClass soldierClass,
                                      LoadoutSlot slot, String id) {
        String key = normalize(mapName);
        if (playerId == null || key == null || soldierClass == null || slot == null) {
            return PlayerArenaLoadout.EMPTY;
        }
        PlayerMapLoadout next = mapLoadout(playerId, key).withPick(soldierClass, slot, id);
        put(playerId, key, next);
        return next.loadout(soldierClass);
    }

    /** 该玩家在该图、该兵种下的配装组；未配置返回 {@link ClassLoadouts#initial()}。 */
    public ClassLoadouts classLoadouts(UUID playerId, @Nullable String mapName, SoldierClass soldierClass) {
        return soldierClass == null
                ? ClassLoadouts.initial()
                : mapLoadout(playerId, mapName).classLoadouts(soldierClass);
    }

    /** 改动该玩家在该图、该兵种、第 {@code presetIndex} 套配装的某个槽位选择。 */
    public PlayerMapLoadout setPresetPick(UUID playerId, String mapName, SoldierClass soldierClass,
                                          int presetIndex, LoadoutSlot slot, String id) {
        String key = normalize(mapName);
        if (playerId == null || key == null || soldierClass == null || slot == null) {
            return PlayerMapLoadout.EMPTY;
        }
        PlayerMapLoadout next = mapLoadout(playerId, key)
                .withPresetPick(soldierClass, presetIndex, slot, id);
        put(playerId, key, next);
        return next;
    }

    /** 给该玩家在该图、该兵种、第 {@code presetIndex} 套配装命名；空串恢复默认名。 */
    public PlayerMapLoadout setPresetName(UUID playerId, String mapName, SoldierClass soldierClass,
                                          int presetIndex, String name) {
        String key = normalize(mapName);
        if (playerId == null || key == null || soldierClass == null) {
            return PlayerMapLoadout.EMPTY;
        }
        PlayerMapLoadout next = mapLoadout(playerId, key)
                .withPresetName(soldierClass, presetIndex, name);
        put(playerId, key, next);
        return next;
    }

    /** 把该玩家在该图、该兵种下的激活配装切到第 {@code presetIndex} 套。 */
    public PlayerMapLoadout setActivePreset(UUID playerId, String mapName, SoldierClass soldierClass,
                                            int presetIndex) {
        String key = normalize(mapName);
        if (playerId == null || key == null || soldierClass == null) {
            return PlayerMapLoadout.EMPTY;
        }
        PlayerMapLoadout next = mapLoadout(playerId, key)
                .withActivePreset(soldierClass, presetIndex);
        put(playerId, key, next);
        return next;
    }

    /** 覆盖该玩家在该图上的整套兵种配装；空记录等同于删除，避免存档里堆空壳。 */
    public void put(UUID playerId, String mapName, PlayerMapLoadout loadout) {
        String key = normalize(mapName);
        if (playerId == null || key == null || loadout == null) {
            return;
        }
        if (loadout.isEmpty()) {
            Map<String, PlayerMapLoadout> maps = byPlayer.get(playerId);
            if (maps != null && maps.remove(key) != null) {
                if (maps.isEmpty()) {
                    byPlayer.remove(playerId);
                }
                setDirty();
            }
            return;
        }
        byPlayer.computeIfAbsent(playerId, k -> new LinkedHashMap<>()).put(key, loadout);
        setDirty();
    }

    @Nullable
    private static String normalize(@Nullable String mapName) {
        if (mapName == null) {
            return null;
        }
        String trimmed = mapName.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- 持久化 ----

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Map<String, PlayerMapLoadout>> e : byPlayer.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(KEY_UUID, e.getKey());
            CompoundTag maps = new CompoundTag();
            for (Map.Entry<String, PlayerMapLoadout> m : e.getValue().entrySet()) {
                if (!m.getValue().isEmpty()) {
                    maps.put(m.getKey(), ArenaCatalogCodec.saveMapLoadout(m.getValue()));
                }
            }
            if (!maps.isEmpty()) {
                entry.put(KEY_MAPS, maps);
                players.add(entry);
            }
        }
        tag.put(KEY_PLAYERS, players);
        return tag;
    }

    public static PlayerLoadoutStore load(CompoundTag tag) {
        PlayerLoadoutStore store = new PlayerLoadoutStore();
        ListTag players = tag.getList(KEY_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            if (!entry.hasUUID(KEY_UUID)) {
                continue;
            }
            UUID id = entry.getUUID(KEY_UUID);
            CompoundTag maps = entry.getCompound(KEY_MAPS);
            Map<String, PlayerMapLoadout> byName = new LinkedHashMap<>();
            for (String name : maps.getAllKeys()) {
                PlayerMapLoadout l = ArenaCatalogCodec.loadMapLoadout(maps.getCompound(name));
                if (!l.isEmpty()) {
                    byName.put(name, l);
                }
            }
            if (!byName.isEmpty()) {
                store.byPlayer.put(id, byName);
            }
        }
        return store;
    }
}
