package org.shee33.act0.battlefield.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;

/**
 * 一个据点的布场定义：标记方块坐标 + 占领判定区域（水平半径 + 上下高度）+ 编号与名称。
 *
 * <p>占领区域为以标记方块为中心、水平 ±{@code radius}、竖直 ±{@code height} 的长方体，
 * 玩家身体进入该区域即计入对该据点的争夺。
 */
public final class ControlPointDef {

    private final int pointId;
    private final BlockPos pos;
    private int radius;
    private int height;
    private String name;
    private double markerOffsetX;
    private double markerOffsetY;
    private double markerOffsetZ;
    private double markerScale;
    private int markerDistance;

    public ControlPointDef(int pointId, BlockPos pos, int radius, int height, String name) {
        this.pointId = pointId;
        this.pos = pos.immutable();
        this.radius = Math.max(1, radius);
        this.height = Math.max(1, height);
        this.name = name != null && !name.isBlank() ? name : pointName(pointId);
        this.markerOffsetX = 0.0;
        this.markerOffsetY = 2.75;
        this.markerOffsetZ = 0.0;
        this.markerScale = 1.25;
        this.markerDistance = 320;
    }

    /** 默认布场：水平半径 8、上下各 4 格。 */
    public static ControlPointDef placed(int pointId, BlockPos pos) {
        return new ControlPointDef(pointId, pos, 8, 4, pointName(pointId));
    }

    private static String pointName(int id) {
        // 0→A、1→B…（超过 26 退回数字）
        if (id >= 0 && id < 26) {
            return String.valueOf((char) ('A' + id));
        }
        return "据点 " + id;
    }

    public int pointId() {
        return pointId;
    }

    public BlockPos pos() {
        return pos;
    }

    public int radius() {
        return radius;
    }

    public int height() {
        return height;
    }

    public String name() {
        return name;
    }

    public double markerOffsetX() {
        return markerOffsetX;
    }

    public double markerOffsetY() {
        return markerOffsetY;
    }

    public double markerOffsetZ() {
        return markerOffsetZ;
    }

    public double markerScale() {
        return markerScale;
    }

    public int markerDistance() {
        return markerDistance;
    }

    public void setRadius(int radius) {
        this.radius = Math.max(1, radius);
    }

    public void setHeight(int height) {
        this.height = Math.max(1, height);
    }

    public void setName(String name) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
    }

    public void setMarkerOffset(double x, double y, double z) {
        this.markerOffsetX = clamp(x, -64.0, 64.0);
        this.markerOffsetY = clamp(y, -64.0, 64.0);
        this.markerOffsetZ = clamp(z, -64.0, 64.0);
    }

    public void setMarkerScale(double scale) {
        this.markerScale = clamp(scale, 0.4, 5.0);
    }

    public void setMarkerDistance(int distance) {
        this.markerDistance = Math.max(32, Math.min(1000, distance));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 占领判定区域。 */
    public AABB zone() {
        return new AABB(
                pos.getX() - radius, pos.getY() - height, pos.getZ() - radius,
                pos.getX() + radius + 1, pos.getY() + height + 1, pos.getZ() + radius + 1);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("id", pointId);
        t.putLong("pos", pos.asLong());
        t.putInt("radius", radius);
        t.putInt("height", height);
        t.putString("name", name);
        t.putDouble("markerOffsetX", markerOffsetX);
        t.putDouble("markerOffsetY", markerOffsetY);
        t.putDouble("markerOffsetZ", markerOffsetZ);
        t.putDouble("markerScale", markerScale);
        t.putInt("markerDistance", markerDistance);
        return t;
    }

    public static ControlPointDef load(CompoundTag t) {
        ControlPointDef def = new ControlPointDef(
                t.getInt("id"),
                BlockPos.of(t.getLong("pos")),
                t.getInt("radius"),
                t.getInt("height"),
                t.getString("name"));
        if (t.contains("markerOffsetX")) {
            def.setMarkerOffset(t.getDouble("markerOffsetX"), t.getDouble("markerOffsetY"), t.getDouble("markerOffsetZ"));
        }
        if (t.contains("markerScale")) {
            def.setMarkerScale(t.getDouble("markerScale"));
        }
        if (t.contains("markerDistance")) {
            def.setMarkerDistance(t.getInt("markerDistance"));
        }
        return def;
    }
}
