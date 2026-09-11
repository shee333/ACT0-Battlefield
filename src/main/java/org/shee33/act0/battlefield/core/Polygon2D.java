package org.shee33.act0.battlefield.core;

import java.util.ArrayList;
import java.util.List;

/**
 * MC-free 的二维简单多边形工具：射线法「点在多边形内」判定 + 耳切三角化。
 *
 * <p>用于据点的不规则占领区域：顶点是世界 XZ 平面上的坐标。支持凸/凹简单多边形；对自交或
 * 退化输入，三角化会尽力切出能切的部分（不抛异常），判定仍按射线法给出结果。
 *
 * <p>不依赖任何 Minecraft 类，纯 JUnit 可直接覆盖（与 {@code BattleArea} 同层）。
 */
public final class Polygon2D {

    private final double[] xs;
    private final double[] zs;
    private final double minX;
    private final double maxX;
    private final double minZ;
    private final double maxZ;

    private Polygon2D(double[] xs, double[] zs) {
        this.xs = xs;
        this.zs = zs;
        double mnx = Double.MAX_VALUE;
        double mxx = -Double.MAX_VALUE;
        double mnz = Double.MAX_VALUE;
        double mxz = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            mnx = Math.min(mnx, xs[i]);
            mxx = Math.max(mxx, xs[i]);
            mnz = Math.min(mnz, zs[i]);
            mxz = Math.max(mxz, zs[i]);
        }
        this.minX = mnx;
        this.maxX = mxx;
        this.minZ = mnz;
        this.maxZ = mxz;
    }

    /**
     * 从顶点列表构造（每个顶点为 {@code {x, z}}，按顺序环绕）。少于 3 个顶点返回 {@code null}
     * ——不构成多边形，调用方应回退到方形半径逻辑。
     */
    public static Polygon2D of(List<double[]> vertices) {
        if (vertices == null || vertices.size() < 3) {
            return null;
        }
        double[] xs = new double[vertices.size()];
        double[] zs = new double[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            double[] v = vertices.get(i);
            xs[i] = v[0];
            zs[i] = v[1];
        }
        return new Polygon2D(xs, zs);
    }

    public int size() {
        return xs.length;
    }

    public double x(int i) {
        return xs[i];
    }

    public double z(int i) {
        return zs[i];
    }

    public double minX() {
        return minX;
    }

    public double maxX() {
        return maxX;
    }

    public double minZ() {
        return minZ;
    }

    public double maxZ() {
        return maxZ;
    }

    /**
     * 射线法：点 {@code (px, pz)} 是否在多边形内部。
     *
     * <p>先用包围盒快速排除，再向 +X 方向投射水平射线统计与各边的交叉次数（奇数为内）。
     * 边上的点结果未定义（射线法固有），但相邻两帧抖动最多 1 格，对占领判定无实际影响。
     */
    public boolean contains(double px, double pz) {
        if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            double xi = xs[i];
            double zi = zs[i];
            double xj = xs[j];
            double zj = zs[j];
            boolean straddle = (zi > pz) != (zj > pz);
            if (straddle && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 耳切三角化，凸凹多边形都支持。返回三角形顶点索引三元组（长度 = 3 × 三角形数）。
     *
     * <p>顶点少于 3 个返回空数组；自交/退化输入下若找不到可切的耳则停止，返回已切出的部分
     * （绝不抛异常，保证渲染与判定路径不会因坏数据崩溃）。
     */
    public int[] triangulate() {
        int n = xs.length;
        if (n < 3) {
            return new int[0];
        }
        if (n == 3) {
            return new int[]{0, 1, 2};
        }

        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        // 统一成逆时针，让「凸顶点 = cross > 0」的判定成立。
        if (signedArea2x() < 0) {
            for (int i = 0; i < n / 2; i++) {
                int t = idx[i];
                idx[i] = idx[n - 1 - i];
                idx[n - 1 - i] = t;
            }
        }

        List<Integer> out = new ArrayList<>(n * 3);
        int count = n;
        int guard = 0;
        while (count > 3 && guard++ <= n * n) {
            boolean clipped = false;
            for (int i = 0; i < count; i++) {
                int ip = idx[(i - 1 + count) % count];
                int ic = idx[i];
                int in = idx[(i + 1) % count];
                if (isEar(ip, ic, in, idx, count)) {
                    out.add(ip);
                    out.add(ic);
                    out.add(in);
                    System.arraycopy(idx, i + 1, idx, i, count - i - 1);
                    count--;
                    clipped = true;
                    break;
                }
            }
            if (!clipped) {
                break;
            }
        }
        if (count == 3) {
            out.add(idx[0]);
            out.add(idx[1]);
            out.add(idx[2]);
        }
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /** 有符号面积的两倍；逆时针为正。 */
    private double signedArea2x() {
        double sum = 0.0;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            sum += (xs[j] * zs[i]) - (xs[i] * zs[j]);
        }
        return sum;
    }

    private boolean isEar(int ip, int ic, int in, int[] idx, int count) {
        double ax = xs[ip];
        double az = zs[ip];
        double bx = xs[ic];
        double bz = zs[ic];
        double cx = xs[in];
        double cz = zs[in];
        // 逆时针环绕下，凸顶点 cross(ab, bc) > 0；<= 0 表示凹顶点或共线，不是耳。
        double cross = (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
        if (cross <= 0.0) {
            return false;
        }
        for (int k = 0; k < count; k++) {
            int iv = idx[k];
            if (iv == ip || iv == ic || iv == in) {
                continue;
            }
            if (pointInTriangle(xs[iv], zs[iv], ax, az, bx, bz, cx, cz)) {
                return false;
            }
        }
        return true;
    }

    private static boolean pointInTriangle(double px, double pz,
                                           double ax, double az,
                                           double bx, double bz,
                                           double cx, double cz) {
        double d1 = sign(px, pz, ax, az, bx, bz);
        double d2 = sign(px, pz, bx, bz, cx, cz);
        double d3 = sign(px, pz, cx, cz, ax, az);
        boolean hasNeg = d1 < 0.0 || d2 < 0.0 || d3 < 0.0;
        boolean hasPos = d1 > 0.0 || d2 > 0.0 || d3 > 0.0;
        return !(hasNeg && hasPos);
    }

    private static double sign(double px, double pz, double ax, double az, double bx, double bz) {
        return (px - bx) * (az - bz) - (ax - bx) * (pz - bz);
    }
}
