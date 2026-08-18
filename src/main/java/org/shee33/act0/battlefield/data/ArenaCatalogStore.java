package org.shee33.act0.battlefield.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog.EditResult;
import org.shee33.act0.battlefield.core.arena.ArenaItemEntry;
import org.shee33.act0.battlefield.core.arena.ArenaWeaponEntry;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.WeaponCategory;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 全服的地图武器/道具目录，按地图名索引，落在<b>主世界</b>的 {@link SavedData} 里。
 *
 * <p><b>为什么是服务器级而不是每维度</b>：管理员要能站在 A 图配置 B 图（{@code /aew1 arena <map> ...}
 * 带地图名参数就是这个意思）。而且对局可能在由地图模板临时创建的独立维度里跑，那个维度的
 * 每维度存档是空的——目录若跟着维度走，一进对局就查不到自己的武器池。挂在主世界一份，
 * 用地图名当主键，两个问题一起消掉。
 *
 * <p><b>为什么与 {@link PlayerLoadoutStore} 分成两个存档文件</b>：{@link SavedData#setDirty()} 的
 * 粒度是整个文件。管理员改一次目录就重写一遍全服玩家的配装选择（可能上千条）是纯浪费，
 * 反之亦然。两者变更频率与体积都不同，分开存。
 *
     * <p>对外只暴露"读视图 + 四个写操作"，不把可变的 {@link ArenaCatalog} 交出去改，
     * 免得调用方改完忘了 {@code setDirty()} 导致改动不落盘。
     *
     * <p><b>读写必须走同一套键解析</b>（{@link #resolveName}，忽略大小写）。曾经 {@code view}
     * 用精确匹配、写操作用忽略大小写，结果是管理员对 {@code dust2} 执行 add 时数据被写进既有的
     * {@code Dust2} 桶、回显却说成功，紧接着的 list 又读不到——同一条命令链里自相矛盾，
     * 且玩家会因为读不到目录而裸着出生。
     */
public final class ArenaCatalogStore extends SavedData {

    public static final String NAME = "act0_aew1_arenas";

    private static final String KEY_ARENAS = "arenas";

    /** 地图名 → 目录。 */
    private final Map<String, ArenaCatalog> byMap = new LinkedHashMap<>();

    public static ArenaCatalogStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ArenaCatalogStore::load, ArenaCatalogStore::new, NAME);
    }

    // ---- 读 ----

    /**
     * 该地图的目录；没配置过返回一个<b>不入档</b>的空目录。
     *
     * <p>返回空目录而不是 {@code null}，让"没配过"和"配了但清空了"在调用方看来一样，
     * 出生发装与部署界面都不必写空判断。
     */
    public ArenaCatalog view(@Nullable String mapName) {
        String key = resolveName(mapName);
        ArenaCatalog catalog = key == null ? null : byMap.get(key);
        return catalog != null ? catalog : new ArenaCatalog();
    }

    /** 是否已为该地图配置过目录。 */
    public boolean has(@Nullable String mapName) {
        return resolveName(mapName) != null;
    }

    /** 已配置目录的全部地图名，按录入顺序。 */
    public List<String> mapNames() {
        return new ArrayList<>(byMap.keySet());
    }

    /**
     * 把用户输入的地图名解析成存档里的规范键（忽略大小写）；没有匹配返回 {@code null}。
     *
     * <p>存在的意义：地图名由管理员手打，{@code Dust2} 与 {@code dust2} 应指同一张图，
     * 否则会静默产生两套互不相干的武器池。
     */
    @Nullable
    public String resolveName(@Nullable String input) {
        String key = normalize(input);
        if (key == null) {
            return null;
        }
        if (byMap.containsKey(key)) {
            return key;
        }
        for (String existing : byMap.keySet()) {
            if (existing.equalsIgnoreCase(key)) {
                return existing;
            }
        }
        return null;
    }

    // ---- 写 ----

    /** 往该地图的指定类别录入一把武器。 */
    public EditResult addWeapon(String mapName, WeaponCategory category, ArenaWeaponEntry entry) {
        return mutate(mapName, catalog -> catalog.addWeapon(category, entry));
    }

    /** 从该地图的指定类别移除一把武器。 */
    public EditResult removeWeapon(String mapName, WeaponCategory category, String gunId) {
        return mutateExisting(mapName, catalog -> catalog.removeWeapon(category, gunId));
    }

    /** 往该地图的指定道具槽录入一件道具。 */
    public EditResult addItem(String mapName, LoadoutSlot slot, ArenaItemEntry entry) {
        return mutate(mapName, catalog -> catalog.addItem(slot, entry));
    }

    /** 从该地图的指定道具槽移除一件道具。 */
    public EditResult removeItem(String mapName, LoadoutSlot slot, String itemId) {
        return mutateExisting(mapName, catalog -> catalog.removeItem(slot, itemId));
    }

    /** 需要新建目录的写操作（add）。 */
    private EditResult mutate(String mapName, Function<ArenaCatalog, EditResult> op) {
        String key = normalize(mapName);
        if (key == null) {
            return EditResult.NOT_FOUND;
        }
        String canonical = resolveName(key);
        ArenaCatalog catalog = canonical != null
                ? byMap.get(canonical)
                : byMap.computeIfAbsent(key, k -> new ArenaCatalog());
        EditResult result = op.apply(catalog);
        if (result == EditResult.OK) {
            setDirty();
        } else if (canonical == null && catalog.isEmpty()) {
            byMap.remove(key);
        }
        return result;
    }

    /** 只作用于已有目录的写操作（remove）。 */
    private EditResult mutateExisting(String mapName, Function<ArenaCatalog, EditResult> op) {
        String canonical = resolveName(mapName);
        if (canonical == null) {
            return EditResult.NOT_FOUND;
        }
        ArenaCatalog catalog = byMap.get(canonical);
        EditResult result = op.apply(catalog);
        if (result == EditResult.OK) {
            if (catalog.isEmpty()) {
                byMap.remove(canonical);
            }
            setDirty();
        }
        return result;
    }

    /** 地图名归一化：去空白；空白视为无效键。 */
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
        CompoundTag arenas = new CompoundTag();
        for (Map.Entry<String, ArenaCatalog> e : byMap.entrySet()) {
            if (!e.getValue().isEmpty()) {
                arenas.put(e.getKey(), ArenaCatalogCodec.save(e.getValue()));
            }
        }
        tag.put(KEY_ARENAS, arenas);
        return tag;
    }

    public static ArenaCatalogStore load(CompoundTag tag) {
        ArenaCatalogStore store = new ArenaCatalogStore();
        CompoundTag arenas = tag.getCompound(KEY_ARENAS);
        for (String key : arenas.getAllKeys()) {
            ArenaCatalog catalog = ArenaCatalogCodec.load(arenas.getCompound(key));
            if (!catalog.isEmpty()) {
                store.byMap.put(key, catalog);
            }
        }
        return store;
    }
}
