package org.shee33.act0.battlefield.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 据点外围重生点的候选几何。MC-free 纯计算，不碰任何方块。
 *
 * <p>在据点<b>占领区之外</b>贴边一圈取候选点，而不是直接落在据点中心：落在区里意味着增援一出生
 * 就参与占领判定，据点争夺变成"谁重生得快"；贴边落地则要走两步才进区，交火才是决定因素。
 *
 * <p>候选点按"离最近的敌人最远"排序交给调用方，由调用方逐个做地形可站立探测——地形探测需要
 * 世界数据，因此刻意留在这个类之外。
 */
public final class PointSpawnRing {

    /** 距占领区边界的最小外扩格数。 */
    public static final int MIN_GAP = 1;

    /** 距占领区边界的最大外扩格数。贴边就好，远了就不像"在据点重生"了。 */
    public static final int MAX_GAP = 3;

    /** 每一圈采样的方位数；12 个方位 = 每 30°一个，足够绕开一侧的敌人又不至于候选过多。 */
    public static final int SAMPLES_PER_RING = 12;

    private PointSpawnRing() {
    }

    /** 一个候选点相对据点中心的水平偏移。 */
    public record Offset(double dx, double dz) {

        /** 到给定水平坐标的平方距离。用平方值比较，省掉每次比较的开方。 */
        public double distanceSqrTo(double centerX, double centerZ, double x, double z) {
            double ox = centerX + dx - x;
            double oz = centerZ + dz - z;
            return ox * ox + oz * oz;
        }
    }

    /**
     * 生成据点外围的候选偏移，按外扩距离由近到远、每圈按方位排列。
     *
     * @param zoneRadius 占领区半径；小于 0 视作 0
     */
    public static List<Offset> candidates(int zoneRadius) {
        int radius = Math.max(0, zoneRadius);
        List<Offset> out = new ArrayList<>(SAMPLES_PER_RING * (MAX_GAP - MIN_GAP + 1));
        for (int gap = MIN_GAP; gap <= MAX_GAP; gap++) {
            double r = radius + gap;
            for (int i = 0; i < SAMPLES_PER_RING; i++) {
                double angle = 2.0 * Math.PI * i / SAMPLES_PER_RING;
                out.add(new Offset(Math.cos(angle) * r, Math.sin(angle) * r));
            }
        }
        return out;
    }

    /**
     * 把候选点按"离最近敌人的距离"从远到近重排。
     *
     * <p>比较的是<b>每个候选点到最近敌人的距离</b>而不是到所有敌人的距离之和：玩家在意的是
     * 落地那一刻会不会被人当场打死，那取决于最近的那一个敌人，而不是平均分布。
     *
     * <p>没有敌人时原样返回，此时外扩距离近的候选排在前面，落点自然贴着据点边缘。
     *
     * @param enemyXz 敌人水平坐标，每项 {@code [x, z]}
     */
    public static List<Offset> rankByEnemyDistance(List<Offset> candidates, double centerX, double centerZ,
                                                   List<double[]> enemyXz) {
        if (candidates.isEmpty() || enemyXz == null || enemyXz.isEmpty()) {
            return candidates;
        }
        List<Offset> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Double.compare(
                nearestEnemyDistanceSqr(b, centerX, centerZ, enemyXz),
                nearestEnemyDistanceSqr(a, centerX, centerZ, enemyXz)));
        return sorted;
    }

    /** 候选点到最近敌人的平方距离；无敌人时返回 {@link Double#MAX_VALUE}。 */
    public static double nearestEnemyDistanceSqr(Offset offset, double centerX, double centerZ,
                                                 List<double[]> enemyXz) {
        double best = Double.MAX_VALUE;
        for (double[] enemy : enemyXz) {
            if (enemy != null && enemy.length >= 2) {
                best = Math.min(best, offset.distanceSqrTo(centerX, centerZ, enemy[0], enemy[1]));
            }
        }
        return best;
    }
}
