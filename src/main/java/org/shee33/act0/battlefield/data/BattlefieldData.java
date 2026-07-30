package org.shee33.act0.battlefield.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.shee33.act0.battlefield.core.BattleArea;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.Sector;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    public BattleArea effectiveArea() {
        if (area.isSet()) {
            return area;
        }
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

    /** 清空所有据点、两个阵营基地与显式战斗区域，供 {@code /battlefield preset load} 使用。 */
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

    // ---- 持久化 ----

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
