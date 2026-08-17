package org.shee33.act0.battlefield.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.PlayerArenaLoadout;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家在每张地图上的配装选择，按 {@code 玩家 UUID × 地图名} 持久化在<b>主世界</b>的 {@link SavedData} 里。
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

    /** 玩家 UUID → (地图名 → 选择)。 */
    private final Map<UUID, Map<String, PlayerArenaLoadout>> byPlayer = new LinkedHashMap<>();

    public static PlayerLoadoutStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayerLoadoutStore::load, PlayerLoadoutStore::new, NAME);
    }

    /** 该玩家在该图上的选择；没选过返回 {@link PlayerArenaLoadout#EMPTY}。 */
    public PlayerArenaLoadout loadout(UUID playerId, @Nullable String mapName) {
        String key = normalize(mapName);
        if (playerId == null || key == null) {
            return PlayerArenaLoadout.EMPTY;
        }
        Map<String, PlayerArenaLoadout> maps = byPlayer.get(playerId);
        if (maps == null) {
            return PlayerArenaLoadout.EMPTY;
        }
        PlayerArenaLoadout l = maps.get(key);
        return l != null ? l : PlayerArenaLoadout.EMPTY;
    }

    /**
     * 记下该玩家在该图上某个槽位的选择，并按当前目录清理掉已失效的旧选择。
     *
     * <p>写入时顺手 {@code sanitize}：这是玩家存档唯一会被写的时机，在这里收敛就不必再单独跑
     * 一遍全服清理任务。传入的 ID 若不在目录里则视为清除该槽位——校验由调用方在收包时先做过一次，
     * 这里再兜一次，避免有人绕过界面直接发包塞进一把没上架的枪。
     */
    public PlayerArenaLoadout setPick(UUID playerId, String mapName, LoadoutSlot slot,
                                      @Nullable String id, ArenaCatalog catalog) {
        String key = normalize(mapName);
        if (playerId == null || key == null || slot == null) {
            return PlayerArenaLoadout.EMPTY;
        }
        String accepted = catalog.hasOption(slot, id) ? id : null;
        PlayerArenaLoadout next = loadout(playerId, key).with(slot, accepted).sanitize(catalog);
        put(playerId, key, next);
        return next;
    }

    /** 覆盖该玩家在该图上的整套选择；空选择等同于删除这条记录，避免存档里堆空壳。 */
    public void put(UUID playerId, String mapName, PlayerArenaLoadout loadout) {
        String key = normalize(mapName);
        if (playerId == null || key == null || loadout == null) {
            return;
        }
        if (loadout.isEmpty()) {
            Map<String, PlayerArenaLoadout> maps = byPlayer.get(playerId);
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
        for (Map.Entry<UUID, Map<String, PlayerArenaLoadout>> e : byPlayer.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(KEY_UUID, e.getKey());
            CompoundTag maps = new CompoundTag();
            for (Map.Entry<String, PlayerArenaLoadout> m : e.getValue().entrySet()) {
                if (!m.getValue().isEmpty()) {
                    maps.put(m.getKey(), ArenaCatalogCodec.saveLoadout(m.getValue()));
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
            Map<String, PlayerArenaLoadout> byName = new LinkedHashMap<>();
            for (String name : maps.getAllKeys()) {
                PlayerArenaLoadout l = ArenaCatalogCodec.loadLoadout(maps.getCompound(name));
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
