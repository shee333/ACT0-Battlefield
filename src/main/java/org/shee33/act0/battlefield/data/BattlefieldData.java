package org.shee33.act0.battlefield.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.shee33.act0.battlefield.core.BattleArea;
import org.shee33.act0.battlefield.core.Faction;

import javax.annotation.Nullable;
import java.util.ArrayList;
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

    @Nullable
    private BaseSpawn alphaBase;
    @Nullable
    private BaseSpawn bravoBase;

    /** 显式录入的战斗区域边界；为空时按基地+据点推导。 */
    private BattleArea area = BattleArea.EMPTY;

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

    // ---- 持久化 ----

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ControlPointDef def : points.values()) {
            list.add(def.save());
        }
        tag.put("points", list);
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
        return tag;
    }

    public static BattlefieldData load(CompoundTag tag) {
        BattlefieldData data = new BattlefieldData();
        ListTag list = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ControlPointDef def = ControlPointDef.load(list.getCompound(i));
            data.points.put(def.pos().asLong(), def);
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
