package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointSpawnRingTest {

    private static final double CX = 100.5;
    private static final double CZ = -40.5;

    private static double distanceFromCenter(PointSpawnRing.Offset o) {
        return Math.sqrt(o.dx() * o.dx() + o.dz() * o.dz());
    }

    @Test
    void generatesOneRingPerGap() {
        List<PointSpawnRing.Offset> all = PointSpawnRing.candidates(8);
        int gaps = PointSpawnRing.MAX_GAP - PointSpawnRing.MIN_GAP + 1;
        assertEquals(gaps * PointSpawnRing.SAMPLES_PER_RING, all.size());
    }

    /** 这是整条特性的核心约束：落点必须在占领区之外，且贴着边不能远。 */
    @Test
    void everyCandidateSitsJustOutsideTheZone() {
        int radius = 8;
        for (PointSpawnRing.Offset o : PointSpawnRing.candidates(radius)) {
            double d = distanceFromCenter(o);
            assertTrue(d >= radius + PointSpawnRing.MIN_GAP - 1e-6,
                    "候选点落进了占领区内部：距中心 " + d + "，区半径 " + radius);
            assertTrue(d <= radius + PointSpawnRing.MAX_GAP + 1e-6,
                    "候选点离据点太远：距中心 " + d);
        }
    }

    @Test
    void zeroRadiusStillProducesRingsAroundTheMarker() {
        for (PointSpawnRing.Offset o : PointSpawnRing.candidates(0)) {
            double d = distanceFromCenter(o);
            assertTrue(d >= PointSpawnRing.MIN_GAP - 1e-6 && d <= PointSpawnRing.MAX_GAP + 1e-6);
        }
    }

    @Test
    void negativeRadiusIsTreatedAsZero() {
        assertEquals(PointSpawnRing.candidates(0).size(), PointSpawnRing.candidates(-5).size());
    }

    /** 敌人堵在东侧时，排第一的落点必须在另一边。 */
    @Test
    void ranksAwayFromTheEnemySide() {
        int radius = 8;
        List<double[]> enemies = List.of(new double[]{CX + 30.0, CZ});
        PointSpawnRing.Offset best = PointSpawnRing.rankByEnemyDistance(
                PointSpawnRing.candidates(radius), CX, CZ, enemies).get(0);
        assertTrue(best.dx() < 0, "敌人在东，落点却选在东侧：dx=" + best.dx());
    }

    /** 取的是"到最近敌人"的距离而非总和：夹在两人中间的点不能因为总距离大而胜出。 */
    @Test
    void ranksByNearestEnemyNotBySum() {
        List<double[]> enemies = List.of(
                new double[]{CX + 30.0, CZ},
                new double[]{CX - 30.0, CZ});
        List<PointSpawnRing.Offset> ranked = PointSpawnRing.rankByEnemyDistance(
                PointSpawnRing.candidates(8), CX, CZ, enemies);
        PointSpawnRing.Offset best = ranked.get(0);
        assertTrue(Math.abs(best.dz()) > Math.abs(best.dx()),
                "两名敌人分列东西，落点应偏向南北：" + best);
    }

    @Test
    void noEnemiesReturnsInputUntouched() {
        List<PointSpawnRing.Offset> input = PointSpawnRing.candidates(8);
        assertSame(input, PointSpawnRing.rankByEnemyDistance(input, CX, CZ, List.of()));
        assertSame(input, PointSpawnRing.rankByEnemyDistance(input, CX, CZ, null));
    }

    @Test
    void rankingKeepsEveryCandidate() {
        List<PointSpawnRing.Offset> input = PointSpawnRing.candidates(8);
        List<PointSpawnRing.Offset> ranked = PointSpawnRing.rankByEnemyDistance(
                input, CX, CZ, List.of(new double[]{CX + 12.0, CZ + 3.0}));
        assertEquals(input.size(), ranked.size());
        assertTrue(ranked.containsAll(input));
    }

    @Test
    void rankingIsMonotonicInNearestEnemyDistance() {
        List<double[]> enemies = List.of(new double[]{CX + 20.0, CZ + 5.0});
        List<PointSpawnRing.Offset> ranked = PointSpawnRing.rankByEnemyDistance(
                PointSpawnRing.candidates(8), CX, CZ, enemies);
        for (int i = 1; i < ranked.size(); i++) {
            double prev = PointSpawnRing.nearestEnemyDistanceSqr(ranked.get(i - 1), CX, CZ, enemies);
            double cur = PointSpawnRing.nearestEnemyDistanceSqr(ranked.get(i), CX, CZ, enemies);
            assertTrue(prev >= cur - 1e-9, "第 " + i + " 项比前一项更靠近敌人");
        }
    }

    @Test
    void malformedEnemyEntriesAreIgnored() {
        List<double[]> enemies = new java.util.ArrayList<>();
        enemies.add(null);
        enemies.add(new double[]{CX});
        double d = PointSpawnRing.nearestEnemyDistanceSqr(
                new PointSpawnRing.Offset(9.0, 0.0), CX, CZ, enemies);
        assertEquals(Double.MAX_VALUE, d);
    }

    @Test
    void candidatesAreDistinct() {
        List<PointSpawnRing.Offset> all = PointSpawnRing.candidates(8);
        assertFalse(all.stream().distinct().count() < all.size(), "候选点有重复");
    }
}
