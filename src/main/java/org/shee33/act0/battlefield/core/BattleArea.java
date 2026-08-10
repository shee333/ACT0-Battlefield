package org.shee33.act0.battlefield.core;

import java.util.List;

/**
 * 战斗区域：一个 AABB（轴对齐包围盒），描述一张大战场地图的有效作战范围。
 *
 * <p>可用于：
 * <ul>
 *   <li>部署阶段叠加边界框可视化，让玩家直观看到"地图边在哪"。</li>
 *   <li>未来做越界惩罚（逃兵倒计时 / 自动击杀）。</li>
 * </ul>
 *
 * <p>{@link #isEmpty()} 表示"未设置"，此时 {@link #derive(List, List, double)} 会从基地与据点推导一个隐式区域。
 *
 * <p>MC-free 纯逻辑，可单测。
 */
public record BattleArea(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {

    public static final BattleArea EMPTY = new BattleArea(0, 0, 0, 0, 0, 0);

    public BattleArea {
        if (Double.isNaN(minX) || Double.isNaN(minY) || Double.isNaN(minZ)
                || Double.isNaN(maxX) || Double.isNaN(maxY) || Double.isNaN(maxZ)) {
            throw new IllegalArgumentException("BattleArea bounds must be finite");
        }
    }

    /** 该区域是否已显式设置（管理员录入或推导得到）。 */
    public boolean isSet() {
        return maxX > minX && maxY > minY && maxZ > minZ;
    }

    public boolean isEmpty() {
        return !isSet();
    }

    public double centerX() {
        return (minX + maxX) * 0.5;
    }

    public double centerY() {
        return (minY + maxY) * 0.5;
    }

    public double centerZ() {
        return (minZ + maxZ) * 0.5;
    }

    public double sizeX() {
        return maxX - minX;
    }

    public double sizeY() {
        return maxY - minY;
    }

    public double sizeZ() {
        return maxZ - minZ;
    }

    /**
     * 与另一个区域取并集（各轴取更宽的一侧）。任一方为空则返回另一方。
     *
     * <p>供"地图视图区域"使用：显式设定的战斗区域不保证包含所有据点（管理员完全可能先划区域
     * 再挪据点），而缩略地图必须把要画的东西全框进来，否则标记会被投影到区域外、看起来位置全乱。
     */
    public BattleArea union(BattleArea other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return new BattleArea(
                Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * 由若干关键点推导区域：取所有点的最小/最大 XYZ，外加水平 {@code padding}。
     * 高度若无高点则取 {@code levelMinY..levelMinY+256} 默认高度。
     *
     * @param points 三维点列表（基地、据点等）。允许为空。
     * @param padding 水平方向外扩（格）。用于给玩家留边界感。
     */
    public static BattleArea derive(List<double[]> points, double padding) {
        return derive(points, padding, -64, 320);
    }

    public static BattleArea derive(List<double[]> points, double padding, double fallbackMinY, double fallbackMaxY) {
        if (points == null || points.isEmpty()) {
            return EMPTY;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            if (p == null || p.length < 3) {
                continue;
            }
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
            maxZ = Math.max(maxZ, p[2]);
        }
        if (minX == Double.POSITIVE_INFINITY) {
            return EMPTY;
        }
        double pad = Math.max(0, padding);
        return new BattleArea(
                minX - pad, minY - pad, minZ - pad,
                maxX + pad, maxY + pad, maxZ + pad);
    }
}