package org.shee33.act0.battlefield.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.SoldierClass;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家的配装<b>选择</b>，按 {@code 玩家 UUID × 地图名} 持久化在<b>主世界</b>的 {@link SavedData} 里。
 *
 * <p><b>只存选择，不存内容</b>：配装内容（槽位/弹药/服装）是管理员在 {@link BattlefieldData} 里
 * 预设的，玩家在这份存档里只记"我在这张图上，阵营 X 的兵种 Y 选的是哪套配装"。管理员改预设内容，
 * 玩家这边无需任何迁移——下次部署读到的就是新内容。
 *
 * <p>每玩家每图：当前兵种 + （阵营,兵种）→ 选中配装 id（可为空 = 未选，部署时用该兵种第一套）。
 */
public final class PlayerLoadoutStore extends SavedData {

    public static final String NAME = "act0_aew1_player_loadouts";

    private static final String KEY_PLAYERS = "players";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_MAPS = "maps";
    private static final String KEY_CLASS = "class";
    private static final String KEY_PICKS = "picks";

    /** 玩家 UUID → (地图名 → 该图选择)。 */
    private final Map<UUID, Map<String, PlayerMapSelection>> byPlayer = new LinkedHashMap<>();

    public static PlayerLoadoutStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                PlayerLoadoutStore::load, PlayerLoadoutStore::new, NAME);
    }

    /** 该玩家在该图上的选择；没记录返回 {@link PlayerMapSelection#EMPTY}。 */
    public PlayerMapSelection selection(UUID playerId, @Nullable String mapName) {
        String key = normalize(mapName);
        if (playerId == null || key == null) {
            return PlayerMapSelection.EMPTY;
        }
        Map<String, PlayerMapSelection> maps = byPlayer.get(playerId);
        if (maps == null) {
            return PlayerMapSelection.EMPTY;
        }
        PlayerMapSelection s = maps.get(key);
        return s != null ? s : PlayerMapSelection.EMPTY;
    }

    public SoldierClass selectedClass(UUID playerId, @Nullable String mapName) {
        return selection(playerId, mapName).selected();
    }

    /** 该玩家在该图、该阵营该兵种选中的配装 id；未选返回 {@code null}。 */
    @Nullable
    public String selectedPresetId(UUID playerId, @Nullable String mapName, Faction faction,
                                   SoldierClass soldierClass) {
        return faction == null || soldierClass == null ? null
                : selection(playerId, mapName).presetId(faction, soldierClass);
    }

    /** 切换该玩家在该图上的兵种。 */
    public PlayerMapSelection setSelectedClass(UUID playerId, String mapName, SoldierClass soldierClass) {
        String key = normalize(mapName);
        if (playerId == null || key == null || soldierClass == null) {
            return PlayerMapSelection.EMPTY;
        }
        PlayerMapSelection next = selection(playerId, key).withSelected(soldierClass);
        put(playerId, key, next);
        return next;
    }

    /** 记下该玩家在该图、该阵营该兵种选中的配装 id；传 {@code null} 表示清除选择。 */
    public PlayerMapSelection setPresetSelection(UUID playerId, String mapName, Faction faction,
                                                 SoldierClass soldierClass, @Nullable String presetId) {
        String key = normalize(mapName);
        if (playerId == null || key == null || faction == null || soldierClass == null) {
            return PlayerMapSelection.EMPTY;
        }
        PlayerMapSelection next = selection(playerId, key).withPreset(faction, soldierClass, presetId);
        put(playerId, key, next);
        return next;
    }

    /** 覆盖该玩家在该图上的整套选择；空记录等同于删除，避免存档里堆空壳。 */
    public void put(UUID playerId, String mapName, PlayerMapSelection selection) {
        String key = normalize(mapName);
        if (playerId == null || key == null || selection == null) {
            return;
        }
        if (selection.isEmpty()) {
            Map<String, PlayerMapSelection> maps = byPlayer.get(playerId);
            if (maps != null && maps.remove(key) != null) {
                if (maps.isEmpty()) {
                    byPlayer.remove(playerId);
                }
                setDirty();
            }
            return;
        }
        byPlayer.computeIfAbsent(playerId, k -> new LinkedHashMap<>()).put(key, selection);
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
        for (Map.Entry<UUID, Map<String, PlayerMapSelection>> e : byPlayer.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(KEY_UUID, e.getKey());
            CompoundTag maps = new CompoundTag();
            for (Map.Entry<String, PlayerMapSelection> m : e.getValue().entrySet()) {
                if (!m.getValue().isEmpty()) {
                    maps.put(m.getKey(), saveSelection(m.getValue()));
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
            Map<String, PlayerMapSelection> byName = new LinkedHashMap<>();
            for (String name : maps.getAllKeys()) {
                PlayerMapSelection s = loadSelection(maps.getCompound(name));
                if (!s.isEmpty()) {
                    byName.put(name, s);
                }
            }
            if (!byName.isEmpty()) {
                store.byPlayer.put(id, byName);
            }
        }
        return store;
    }

    private static CompoundTag saveSelection(PlayerMapSelection s) {
        CompoundTag t = new CompoundTag();
        t.putString(KEY_CLASS, s.selected().id());
        CompoundTag picks = new CompoundTag();
        for (Map.Entry<String, String> e : s.presets().entrySet()) {
            if (e.getValue() != null) {
                picks.putString(e.getKey(), e.getValue());
            }
        }
        t.put(KEY_PICKS, picks);
        return t;
    }

    private static PlayerMapSelection loadSelection(CompoundTag t) {
        SoldierClass selected = SoldierClass.byIdOrDefault(t.getString(KEY_CLASS));
        Map<String, String> picks = new LinkedHashMap<>();
        CompoundTag picksTag = t.getCompound(KEY_PICKS);
        for (String key : picksTag.getAllKeys()) {
            String presetId = picksTag.getString(key);
            if (validPickKey(key) && !presetId.isBlank()) {
                picks.put(key, presetId);
            }
        }
        return new PlayerMapSelection(selected, picks);
    }

    /** 选择键格式 {@code 阵营#兵种id}；脏数据跳过。 */
    private static boolean validPickKey(String key) {
        int i = key.indexOf('#');
        if (i <= 0 || i == key.length() - 1) {
            return false;
        }
        try {
            Faction.valueOf(key.substring(0, i).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return SoldierClass.byId(key.substring(i + 1)) != null;
    }

    /** 一名玩家在某张图上的选择：当前兵种 + （阵营,兵种）→ 选中配装 id。 */
    public record PlayerMapSelection(SoldierClass selected, Map<String, String> presets) {

        public static final PlayerMapSelection EMPTY = new PlayerMapSelection(SoldierClass.DEFAULT, Map.of());

        public PlayerMapSelection {
            Map<String, String> copy = new LinkedHashMap<>();
            if (presets != null) {
                for (Map.Entry<String, String> e : presets.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null && !e.getValue().isBlank()) {
                        copy.put(e.getKey(), e.getValue());
                    }
                }
            }
            presets = Collections.unmodifiableMap(copy);
        }

        private static String pickKey(Faction faction, SoldierClass soldierClass) {
            return faction.name() + "#" + soldierClass.id();
        }

        @Nullable
        public String presetId(Faction faction, SoldierClass soldierClass) {
            return faction == null || soldierClass == null ? null : presets.get(pickKey(faction, soldierClass));
        }

        public PlayerMapSelection withSelected(SoldierClass soldierClass) {
            return soldierClass == selected ? this : new PlayerMapSelection(soldierClass, presets);
        }

        public PlayerMapSelection withPreset(Faction faction, SoldierClass soldierClass, @Nullable String presetId) {
            Map<String, String> next = new LinkedHashMap<>(presets);
            String key = pickKey(faction, soldierClass);
            if (presetId == null || presetId.isBlank()) {
                next.remove(key);
            } else {
                next.put(key, presetId);
            }
            return new PlayerMapSelection(selected, next);
        }

        public boolean isEmpty() {
            return selected == SoldierClass.DEFAULT && presets.isEmpty();
        }
    }
}
