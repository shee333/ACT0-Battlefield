package org.shee33.act0.battlefield.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.shee33.act0.battlefield.core.BattleArea;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.FactionNames;
import org.shee33.act0.battlefield.core.MatchCapacity;
import org.shee33.act0.battlefield.core.Sector;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.LoadoutPresetDef;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
/**
 * 大战场布场数据（每维度 {@link SavedData} 落盘）：登记的据点、两阵营的基地出生点。
 *
 * <p>据点随标记方块放置/破坏自动增删；基地出生点由命令录入。对局运行时从这里读取布场。
 */
public final class BattlefieldData extends SavedData {

    public static final String NAME = "act0_battlefield";

    /** posLong → 据点定义。 */
    private final Map<Long, ControlPointDef> points = new LinkedHashMap<>();

    /** id → 突破模式区域。 */
    private final Map<Integer, Sector> sectors = new LinkedHashMap<>();

    @Nullable
    private BaseSpawn alphaBase;
    @Nullable
    private BaseSpawn bravoBase;

    /** 显式录入的战斗区域边界；为空时按基地+据点推导。 */
    private BattleArea area = BattleArea.EMPTY;

    /** 命名预设：当前布场（据点+基地+区域）的快照，存于主 NBT 的 {@code presets} 子节点下。 */
    private final Map<String, CompoundTag> presets = new LinkedHashMap<>();

    /** 管理员为当前世界命名的地图名，供对局浏览器展示；空字符串表示未命名。 */
    private String mapName = "";

    /** 配装预设：{@code 阵营id#兵种id} → 该组合下的有序配装列表（管理员预设，玩家只选）。 */
    private final Map<String, List<LoadoutPresetDef>> loadoutPresets = new LinkedHashMap<>();

    /** 该地图每方起始票数；{@code 0} = 未设置（建图时强制要求，未设置无法开局）。 */
    private int tickets;

    /** 对局结束/中途退出的返回点（管理员站定后设置）；{@code null} = 回主世界出生点。 */
    @Nullable
    private ReturnPoint returnPoint;
    /** 该地图的两个阵营名称；建图时必填，0.2.7 及更早的存档读档时回落 {@link FactionNames#LEGACY}。 */
    private FactionNames factionNames = FactionNames.LEGACY;

    /** 该地图的自动开始人数；{@code 0} 表示跟随全局配置（见 {@link MatchCapacity#resolve}）。 */
    private int minPlayersToStart;

    /** 该地图的对局人数上限；{@code 0} 表示跟随全局配置。 */
    /** 该地图的对局人数上限；{@code 0} 表示跟随全局配置。 */
    private int maxPlayers;

    /**
     * 该地图对局中的 HUD 是否用原版快捷栏（放右下角）替代自绘武器栏。
     *
     * <p>管理员用 {@code /aew1 hud vanilla} 切换。原版快捷栏需要玩家手持物品（含其他模组物品）
     * 都按原版方式渲染，某些模组物品在我们的自绘武器栏里会显示为紫黑——此开关为这类环境提供逃生门。
     */
    private boolean vanillaHudMode;

    public static BattlefieldData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                BattlefieldData::load, BattlefieldData::new, NAME);
    }

    // ---- 据点 ----

    /** 放置标记方块时登记一个据点，自动分配最小可用编号。 */
    public ControlPointDef addPoint(BlockPos pos) {
        long key = pos.asLong();
        ControlPointDef existing = points.get(key);
        if (existing != null) {
            return existing;
        }
        int id = nextPointId();
        ControlPointDef def = ControlPointDef.placed(id, pos);
        points.put(key, def);
        setDirty();
        return def;
    }

    /** 破坏标记方块时移除该坐标的据点。 */
    public void removePoint(BlockPos pos) {
        if (points.remove(pos.asLong()) != null) {
            setDirty();
        }
    }

    @Nullable
    public ControlPointDef pointAt(BlockPos pos) {
        return points.get(pos.asLong());
    }

    @Nullable
    public ControlPointDef pointById(int id) {
        for (ControlPointDef def : points.values()) {
            if (def.pointId() == id) {
                return def;
            }
        }
        return null;
    }

    public List<ControlPointDef> points() {
        return new ArrayList<>(points.values());
    }

    private int nextPointId() {
        int id = 0;
        while (pointById(id) != null) {
            id++;
        }
        return id;
    }

    // ---- 区域 ----

    /** 注册一个突破模式区域，按 id 索引。 */
    public void addSector(Sector sector) {
        sectors.put(sector.id(), sector);
        setDirty();
    }

    /** 移除指定 id 的区域。 */
    public void removeSector(int id) {
        if (sectors.remove(id) != null) {
            setDirty();
        }
    }

    /** 按 id 升序返回所有已注册区域。 */
    public List<Sector> sectors() {
        return sectors.values().stream()
                .sorted(Comparator.comparingInt(Sector::id))
                .toList();
    }

    @Nullable
    public Sector sectorById(int id) {
        return sectors.get(id);
    }

    // ---- 基地 ----

    public void setBase(Faction faction, BaseSpawn spawn) {
        if (faction == Faction.ALPHA) {
            alphaBase = spawn;
        } else {
            bravoBase = spawn;
        }
        setDirty();
    }

    @Nullable
    public BaseSpawn base(Faction faction) {
        return faction == Faction.ALPHA ? alphaBase : bravoBase;
    }

    // ---- 地图命名 ----

    public void setMapName(String name) {
        this.mapName = name != null ? name : "";
        setDirty();
    }

    /** 当前世界的地图名；未命名返回空字符串（由调用方决定占位显示）。 */
    public String mapName() {
        return mapName;
    }

    public void setFactionNames(FactionNames names) {
        this.factionNames = names != null ? names : FactionNames.LEGACY;
        setDirty();
    }

    /** 该地图的阵营名称；永不为 {@code null}。 */
    public FactionNames factionNames() {
        return factionNames;
    }

    // ---- 按地图自定义的人数规则 ----

    /**
     * 设置该地图的自动开始人数。
     *
     * @param value 人数；{@code <= 0} 表示清除自定义、跟随全局配置
     */
    public void setMinPlayersToStart(int value) {
        this.minPlayersToStart = Math.max(0, value);
        setDirty();
    }

    /** 原始设置值；{@code 0} 表示未设置。生效值请用 {@link #effectiveMinPlayers(int)}。 */
    public int minPlayersToStartRaw() {
        return minPlayersToStart;
    }

    /**
     * 设置该地图的对局人数上限。
     *
     * @param value 人数；{@code <= 0} 表示清除自定义、跟随全局配置
     */
    public void setMaxPlayers(int value) {
        this.maxPlayers = Math.max(0, value);
        setDirty();
    }

    /** 原始设置值；{@code 0} 表示未设置。生效值请用 {@link #effectiveMaxPlayers(int)}。 */
    public int maxPlayersRaw() {
        return maxPlayers;
    }

    /** 对局 HUD 是否用原版快捷栏（放右下角）替代自绘武器栏。 */
    public boolean vanillaHudMode() {
        return vanillaHudMode;
    }

    public void setVanillaHudMode(boolean vanilla) {
        this.vanillaHudMode = vanilla;
        setDirty();
    }

    /** 该地图实际生效的自动开始人数：设过就用地图的，否则用传入的全局默认。 */
    public int effectiveMinPlayers(int globalDefault) {
        return MatchCapacity.resolve(minPlayersToStart, globalDefault);
    }

    /** 该地图实际生效的人数上限：设过就用地图的，否则用传入的全局默认。 */
    public int effectiveMaxPlayers(int globalDefault) {
        return MatchCapacity.resolve(maxPlayers, globalDefault);
    }

    // ---- 每图票数（建图与开局强制要求） ----

    /** 设置该地图每方起始票数；{@code <= 0} 表示未设置。 */
    public void setTickets(int value) {
        this.tickets = Math.max(0, value);
        setDirty();
    }

    /** 原始设置值；{@code 0} 表示未设置。 */
    public int ticketsRaw() {
        return tickets;
    }

    /** 是否已设置票数（建图强制要求，未设置无法开局）。 */
    public boolean hasTickets() {
        return tickets > 0;
    }

    // ---- 对局结束返回点 ----

    /** 设置对局结束/中途退出的返回点；传 {@code null} 清除（恢复主世界出生点）。 */
    public void setReturnPoint(@Nullable ReturnPoint point) {
        this.returnPoint = point;
        setDirty();
    }

    /** 当前返回点；{@code null} = 回主世界出生点。 */
    @Nullable
    public ReturnPoint returnPoint() {
        return returnPoint;
    }

    /** 一个返回点：维度（location 字符串）+ 坐标 + 朝向。维度在传送时才解析成 {@code ResourceKey}，
     * 数据层保持纯 NBT、可单测。 */
    public record ReturnPoint(String dimension, double x, double y, double z,
                              float yaw, float pitch) {

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("dimension", dimension);
            t.putDouble("x", x);
            t.putDouble("y", y);
            t.putDouble("z", z);
            t.putFloat("yaw", yaw);
            t.putFloat("pitch", pitch);
            return t;
        }

        public static ReturnPoint load(CompoundTag t) {
            return new ReturnPoint(t.getString("dimension"),
                    t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                    t.getFloat("yaw"), t.getFloat("pitch"));
        }
    }

    // ---- 就绪判定（对局浏览器"等待中"房间与 start() 前置校验共用） ----

    /** 征服模式所需的最小布场：至少 1 个据点 + 两阵营基地 + 票数已设置（建图强制）。 */
    public boolean isConquestReady() {
        return !points.isEmpty() && alphaBase != null && bravoBase != null && tickets > 0;
    }

    /** 突破模式在征服模式的基础上还要求至少登记 1 个 sector。 */
    public boolean isBreakthroughReady() {
        return isConquestReady() && !sectors.isEmpty();
    }

    // ---- 战斗区域 ----

    /** 设置显式战斗区域（管理员录入）；传 {@code null} 或空区域则清除显式值，恢复为推导。 */
    public void setArea(@Nullable BattleArea area) {
        this.area = (area == null || area.isEmpty()) ? BattleArea.EMPTY : area;
        setDirty();
    }

    /** 获取显式录入的战斗区域；若未录入返回 {@link BattleArea#EMPTY}。 */
    public BattleArea areaOverride() {
        return area;
    }

    /**
     * 取得当前生效的战斗区域：优先使用显式录入，否则从基地+据点推导（外扩 16 格水平 padding）。
     * 若完全没有基地和据点，则返回 {@link BattleArea#EMPTY}。
     */
    /**
     * <b>地图视图</b>专用区域：{@link #effectiveArea()} 与"所有据点/基地包围盒"的并集。
     *
     * <p>与 {@link #effectiveArea()} 刻意分开：后者是<b>玩法边界</b>（越界惩罚以它为准），
     * 必须严格等于管理员划定的范围，不能因为要画图就悄悄放大。而显式区域并不保证包含所有
     * 据点——先划区域再挪据点、或沿用别的布局留下的区域，都会让据点落在区域外，缩略地图
     * 投影后就表现为"据点位置乱、和实际地图对不上"。视图取并集即可保证要画的东西全在框内。
     */
    public BattleArea mapViewArea() {
        return effectiveArea().union(derivedPointArea());
    }

    /** 由所有据点与两阵营基地推导的包围盒（含 16 格外扩）。 */
    private BattleArea derivedPointArea() {
        List<double[]> pts = new ArrayList<>();
        if (alphaBase != null) {
            pts.add(new double[]{alphaBase.x(), alphaBase.y(), alphaBase.z()});
        }
        if (bravoBase != null) {
            pts.add(new double[]{bravoBase.x(), bravoBase.y(), bravoBase.z()});
        }
        for (ControlPointDef def : points.values()) {
            pts.add(new double[]{def.pos().getX() + 0.5, def.pos().getY() + 0.5, def.pos().getZ() + 0.5});
        }
        return BattleArea.derive(pts, 16.0);
    }

    public BattleArea effectiveArea() {
        if (area.isSet()) {
            return area;
        }
        return derivedPointArea();
    }

    // ---- 预设 ----

    /**
     * 把当前布场（据点+基地+区域）的快照以给定的 NBT 存入命名预设；同名覆盖。
     * <p>调用方负责按 {@code points}/{@code alphaBase}/{@code bravoBase}/{@code area} 键结构组装 tag。
     */
    public void savePreset(String name, CompoundTag tag) {
        if (name == null || name.isBlank() || tag == null) {
            return;
        }
        presets.put(name, tag.copy());
        setDirty();
    }

    /** 取出命名预设的副本；不存在返回 {@code null}，由调用方自行解析与应用。 */
    @Nullable
    public CompoundTag loadPreset(String name) {
        CompoundTag t = presets.get(name);
        return t == null ? null : t.copy();
    }

    /** 列出所有已保存预设的名字。 */
    public List<String> listPresets() {
        return new ArrayList<>(presets.keySet());
    }

    /** 删除一个预设；不存在则 no-op。 */
    public void deletePreset(String name) {
        if (presets.remove(name) != null) {
            setDirty();
        }
    }

    /** 清空所有据点、两个阵营基地与显式战斗区域，供 {@code /aew1 preset load} 使用。 */
    public void clearAll() {
        points.clear();
        alphaBase = null;
        bravoBase = null;
        area = BattleArea.EMPTY;
        setDirty();
    }

    /** 用给定的完整定义登记一个据点（覆盖同位置）；用于预设加载以保留原配置（半径/高度/浮标等）。 */
    public void importPoint(ControlPointDef def) {
        points.put(def.pos().asLong(), def);
        setDirty();
    }

    // ---- 配装预设（管理员配置，玩家只选） ----

    private static String presetKey(Faction faction, SoldierClass soldierClass) {
        return faction.name() + "#" + soldierClass.id();
    }

    /** 该阵营该兵种下的全部配装预设，按创建顺序。 */
    public List<LoadoutPresetDef> presetsFor(Faction faction, SoldierClass soldierClass) {
        List<LoadoutPresetDef> list = loadoutPresets.get(presetKey(faction, soldierClass));
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 按 id 查某配装预设；不存在返回 {@code null}。 */
    @Nullable
    public LoadoutPresetDef preset(Faction faction, SoldierClass soldierClass, String id) {
        for (LoadoutPresetDef p : presetsFor(faction, soldierClass)) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 创建一套配装预设（空内容），生成稳定 id 并返回。显示名默认取创建时传入的名字。 */
    public LoadoutPresetDef createPreset(Faction faction, SoldierClass soldierClass, String displayName) {
        String id = "lp_" + UUID.randomUUID().toString().substring(0, 8);
        LoadoutPresetDef def = new LoadoutPresetDef(id, displayName, Map.of(), Map.of(), Map.of(),
LoadoutPresetDef.ArmorSet.EMPTY);
        String key = presetKey(faction, soldierClass);
        loadoutPresets.computeIfAbsent(key, k -> new ArrayList<>()).add(def);
        setDirty();
        return def;
    }

    /** 覆盖保存某套配装预设（同 id 替换）。用于管理员编辑槽位/弹药/服装后落盘。 */
    public void savePresetDef(Faction faction, SoldierClass soldierClass, LoadoutPresetDef def) {
        String key = presetKey(faction, soldierClass);
        List<LoadoutPresetDef> list = loadoutPresets.computeIfAbsent(key, k -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(def.id())) {
                list.set(i, def);
                setDirty();
                return;
            }
        }
        list.add(def);
        setDirty();
    }

    /** 删除一套配装预设。 */
    public boolean deletePresetDef(Faction faction, SoldierClass soldierClass, String id) {
        String key = presetKey(faction, soldierClass);
        List<LoadoutPresetDef> list = loadoutPresets.get(key);
        if (list == null) {
            return false;
        }
        boolean removed = list.removeIf(p -> p.id().equals(id));
        if (removed) {
            if (list.isEmpty()) {
                loadoutPresets.remove(key);
            }
            setDirty();
        }
        return removed;
    }

    // ---- 配装预设编解码 ----

    private static final String KEY_LOADOUT_PRESETS = "loadoutPresets";
    private static final String KEY_PRESET_ID = "id";
    private static final String KEY_PRESET_NAME = "name";
private static final String KEY_PRESET_SLOTS = "slots";
    private static final String KEY_PRESET_AMMO = "ammo";
    private static final String KEY_PRESET_GUN_NBT = "gunNbt";
private static final String KEY_PRESET_ARMOR = "armor";

private static CompoundTag savePresetDef(LoadoutPresetDef def) {
CompoundTag t = new CompoundTag();
t.putString(KEY_PRESET_ID, def.id());
t.putString(KEY_PRESET_NAME, def.displayName());
CompoundTag slots = new CompoundTag();
for (Map.Entry<LoadoutSlot, String> e : def.slots().entrySet()) {
slots.putString(e.getKey().id(), e.getValue());
}
t.put(KEY_PRESET_SLOTS, slots);
CompoundTag ammo = new CompoundTag();
for (Map.Entry<LoadoutSlot, Integer> e : def.ammo().entrySet()) {
ammo.putInt(e.getKey().id(), e.getValue());
}
t.put(KEY_PRESET_AMMO, ammo);
CompoundTag gunNbt = new CompoundTag();
for (Map.Entry<LoadoutSlot, String> e : def.gunNbt().entrySet()) {
gunNbt.putString(e.getKey().id(), e.getValue());
}
        t.put(KEY_PRESET_GUN_NBT, gunNbt);
        CompoundTag armor = new CompoundTag();
LoadoutPresetDef.ArmorSet a = def.armor();
if (a.helmet() != null) {
armor.putString("helmet", a.helmet());
}
if (a.chest() != null) {
armor.putString("chest", a.chest());
}
if (a.legs() != null) {
armor.putString("legs", a.legs());
}
if (a.boots() != null) {
armor.putString("boots", a.boots());
}
t.put(KEY_PRESET_ARMOR, armor);
return t;
}

    private static LoadoutPresetDef loadPresetDef(CompoundTag t) {
        Map<LoadoutSlot, String> slots = new EnumMap<>(LoadoutSlot.class);
        CompoundTag slotsTag = t.getCompound(KEY_PRESET_SLOTS);
        for (String key : slotsTag.getAllKeys()) {
            LoadoutSlot slot = LoadoutSlot.byId(key);
            if (slot != null && !slotsTag.getString(key).isBlank()) {
                slots.put(slot, slotsTag.getString(key));
            }
        }
        Map<LoadoutSlot, Integer> ammo = new EnumMap<>(LoadoutSlot.class);
        CompoundTag ammoTag = t.getCompound(KEY_PRESET_AMMO);
        for (String key : ammoTag.getAllKeys()) {
            LoadoutSlot slot = LoadoutSlot.byId(key);
            if (slot != null && ammoTag.getInt(key) > 0) {
                ammo.put(slot, ammoTag.getInt(key));
            }
        }
        CompoundTag gunNbtTag = t.getCompound(KEY_PRESET_GUN_NBT);
        Map<LoadoutSlot, String> gunNbt = new EnumMap<>(LoadoutSlot.class);
        for (String key : gunNbtTag.getAllKeys()) {
            LoadoutSlot slot = LoadoutSlot.byId(key);
            if (slot != null && !gunNbtTag.getString(key).isBlank()) {
                gunNbt.put(slot, gunNbtTag.getString(key));
            }
        }
        CompoundTag armorTag = t.getCompound(KEY_PRESET_ARMOR);
        LoadoutPresetDef.ArmorSet armor = LoadoutPresetDef.ArmorSet.of(
                armorTag.getString("helmet"), armorTag.getString("chest"),
                armorTag.getString("legs"), armorTag.getString("boots"));
        return new LoadoutPresetDef(
                t.getString(KEY_PRESET_ID), t.getString(KEY_PRESET_NAME),
                slots, ammo, gunNbt, armor);
    }
    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ControlPointDef def : points.values()) {
            list.add(def.save());
        }
        tag.put("points", list);
        ListTag sectorList = new ListTag();
        for (Sector s : sectors.values()) {
            CompoundTag st = new CompoundTag();
            st.putInt("id", s.id());
            st.putString("name", s.displayName());
            st.putIntArray("pointIds", s.pointIds().stream().mapToInt(Integer::intValue).toArray());
            sectorList.add(st);
        }
        tag.put("sectors", sectorList);
        if (alphaBase != null) {
            tag.put("alphaBase", alphaBase.save());
        }
        if (bravoBase != null) {
            tag.put("bravoBase", bravoBase.save());
        }
        if (area.isSet()) {
            CompoundTag a = new CompoundTag();
            a.putDouble("minX", area.minX());
            a.putDouble("minY", area.minY());
            a.putDouble("minZ", area.minZ());
            a.putDouble("maxX", area.maxX());
            a.putDouble("maxY", area.maxY());
            a.putDouble("maxZ", area.maxZ());
            tag.put("area", a);
        }
        if (!presets.isEmpty()) {
            CompoundTag pt = new CompoundTag();
            for (Map.Entry<String, CompoundTag> e : presets.entrySet()) {
                pt.put(e.getKey(), e.getValue().copy());
            }
            tag.put("presets", pt);
        }
        if (!loadoutPresets.isEmpty()) {
            CompoundTag lp = new CompoundTag();
            for (Map.Entry<String, List<LoadoutPresetDef>> e : loadoutPresets.entrySet()) {
                ListTag presetList = new ListTag();
                for (LoadoutPresetDef def : e.getValue()) {
                    presetList.add(savePresetDef(def));
                }
                lp.put(e.getKey(), presetList);
            }
            tag.put(KEY_LOADOUT_PRESETS, lp);
        }
        if (minPlayersToStart > 0) {
            tag.putInt("minPlayersToStart", minPlayersToStart);
        }
        if (maxPlayers > 0) {
            tag.putInt("maxPlayers", maxPlayers);
        }
        if (vanillaHudMode) {
            tag.putBoolean("vanillaHudMode", true);
        }
        if (tickets > 0) {
            tag.putInt("tickets", tickets);
        }
        if (returnPoint != null) {
            tag.put("returnPoint", returnPoint.save());
        }
        if (!mapName.isEmpty()) {
            tag.putString("mapName", mapName);
        }
        tag.putString("factionAlpha", factionNames.alpha());
        tag.putString("factionBravo", factionNames.bravo());
        return tag;
    }

    public static BattlefieldData load(CompoundTag tag) {
        BattlefieldData data = new BattlefieldData();
        ListTag list = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ControlPointDef def = ControlPointDef.load(list.getCompound(i));
            data.points.put(def.pos().asLong(), def);
        }
        ListTag sectorList = tag.getList("sectors", Tag.TAG_COMPOUND);
        for (int i = 0; i < sectorList.size(); i++) {
            CompoundTag st = sectorList.getCompound(i);
            int[] arr = st.getIntArray("pointIds");
            Sector sector = new Sector(
                    st.getInt("id"),
                    Arrays.stream(arr).boxed().toList(),
                    st.getString("name"));
            data.sectors.put(sector.id(), sector);
        }
        if (tag.contains("alphaBase")) {
            data.alphaBase = BaseSpawn.load(tag.getCompound("alphaBase"));
        }
        if (tag.contains("bravoBase")) {
            data.bravoBase = BaseSpawn.load(tag.getCompound("bravoBase"));
        }
        if (tag.contains("area")) {
            CompoundTag a = tag.getCompound("area");
            data.area = new BattleArea(
                    a.getDouble("minX"), a.getDouble("minY"), a.getDouble("minZ"),
                    a.getDouble("maxX"), a.getDouble("maxY"), a.getDouble("maxZ"));
        }
        if (tag.contains("presets")) {
            CompoundTag pt = tag.getCompound("presets");
            for (String key : pt.getAllKeys()) {
                data.presets.put(key, pt.getCompound(key).copy());
            }
        }
        if (tag.contains(KEY_LOADOUT_PRESETS)) {
            CompoundTag lp = tag.getCompound(KEY_LOADOUT_PRESETS);
            for (String key : lp.getAllKeys()) {
                ListTag presetList = lp.getList(key, Tag.TAG_COMPOUND);
                List<LoadoutPresetDef> defs = new ArrayList<>();
                for (int i = 0; i < presetList.size(); i++) {
                    defs.add(loadPresetDef(presetList.getCompound(i)));
                }
                data.loadoutPresets.put(key, defs);
            }
        }
        data.minPlayersToStart = Math.max(0, tag.getInt("minPlayersToStart"));
        data.maxPlayers = Math.max(0, tag.getInt("maxPlayers"));
        data.vanillaHudMode = tag.getBoolean("vanillaHudMode");
        data.tickets = Math.max(0, tag.getInt("tickets"));
        if (tag.contains("returnPoint")) {
            data.returnPoint = ReturnPoint.load(tag.getCompound("returnPoint"));
        }
        if (tag.contains("mapName")) {
            data.mapName = tag.getString("mapName");
        }
        data.factionNames = FactionNames.sanitize(
                tag.getString("factionAlpha"), tag.getString("factionBravo"));
        return data;
    }

    /** 一个阵营基地出生点：坐标 + 朝向。 */
    public record BaseSpawn(double x, double y, double z, float yaw, float pitch) {

        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", x);
            t.putDouble("y", y);
            t.putDouble("z", z);
            t.putFloat("yaw", yaw);
            t.putFloat("pitch", pitch);
            return t;
        }

        public static BaseSpawn load(CompoundTag t) {
            return new BaseSpawn(
                    t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                    t.getFloat("yaw"), t.getFloat("pitch"));
        }
    }
}
