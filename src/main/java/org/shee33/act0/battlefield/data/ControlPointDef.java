package org.shee33.act0.battlefield.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import org.shee33.act0.battlefield.core.Polygon2D;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 不规则占领区域的多边形顶点（世界方块坐标，按环绕顺序）；空列表 = 沿用 {@code radius} 方形。
     * 顶点语义为方块中心（+0.5），由世界内圈画工具逐个点击方块采集。
     */
    private List<BlockPos> boundary = List.of();

    /** 多边形判定缓存，{@code boundary} 变更时置空重建（每 tick 每玩家调用，不宜每次现算）。 */
    @Nullable
    private Polygon2D polygonCache;

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

    /** 是否配置了不规则多边形边界（≥3 个顶点）。 */
    public boolean hasBoundary() {
        return boundary.size() >= 3;
    }

    /** 多边形顶点（世界方块坐标）；空表示未配置、沿用方形。 */
    public List<BlockPos> boundary() {
        return boundary;
    }

    /** 设置多边形边界（≥3 个顶点生效；否则清空回退方形）。 */
    public void setBoundary(@Nullable List<BlockPos> vertices) {
        this.boundary = vertices == null ? List.of() : List.copyOf(vertices);
        this.polygonCache = null;
    }

    public void clearBoundary() {
        setBoundary(null);
    }

    /** 多边形判定的缓存视图；未配置边界返回 {@code null}。 */
    @Nullable
    private Polygon2D polygon() {
        if (!hasBoundary()) {
            return null;
        }
        if (polygonCache == null) {
            List<double[]> verts = new ArrayList<>(boundary.size());
            for (BlockPos p : boundary) {
                verts.add(new double[]{p.getX() + 0.5, p.getZ() + 0.5});
            }
            polygonCache = Polygon2D.of(verts);
        }
        return polygonCache;
    }

    /**
     * 玩家位置是否在占领区域内。垂直范围与方形一致（{@code pos.y ± height}）；
     * 水平范围：配置了多边形则按多边形判定，否则用 {@code radius} 方形。
     */
    public boolean contains(double x, double y, double z) {
        if (y < pos.getY() - height || y >= pos.getY() + height + 1) {
            return false;
        }
        Polygon2D poly = polygon();
        if (poly != null) {
            return poly.contains(x, z);
        }
        return x >= pos.getX() - radius && x < pos.getX() + radius + 1
                && z >= pos.getZ() - radius && z < pos.getZ() + radius + 1;
    }

    /**
     * 占领区域的包围盒。配置了多边形时返回多边形的最小/最大 XZ + 同样的垂直范围；
     * 未配置时即方形 AABB。
     *
     * <p>注意：包围盒 ≠ 多边形本身，占领判定必须用 {@link #contains}，
     * 不能拿 {@code zone().contains(...)} 代替（多边形外的包围盒角落会误判）。
     */
    public AABB zone() {
        Polygon2D poly = polygon();
        if (poly != null) {
            return new AABB(poly.minX(), pos.getY() - height, poly.minZ(),
                    poly.maxX(), pos.getY() + height + 1, poly.maxZ());
        }
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
        if (!boundary.isEmpty()) {
            long[] arr = new long[boundary.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = boundary.get(i).asLong();
            }
            t.putLongArray("boundary", arr);
        }
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
        if (t.contains("boundary")) {
            long[] arr = t.getLongArray("boundary");
            List<BlockPos> list = new ArrayList<>(arr.length);
            for (long packed : arr) {
                list.add(BlockPos.of(packed));
            }
            def.setBoundary(list);
        }
return def;
    }
}
