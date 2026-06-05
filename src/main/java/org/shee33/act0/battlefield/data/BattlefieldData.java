package org.shee33.act0.battlefield.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
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
