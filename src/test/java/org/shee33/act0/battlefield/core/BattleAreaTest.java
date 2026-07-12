package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleAreaTest {

    @Test
    void emptyByDefault() {
        assertTrue(BattleArea.EMPTY.isEmpty());
        assertFalse(BattleArea.EMPTY.isSet());
    }

    @Test
    void requiresMaxGreaterThanMin() {
        BattleArea a = new BattleArea(0, 0, 0, 10, 10, 10);
        assertTrue(a.isSet());
        assertTrue(a.contains(5, 5, 5));
        assertFalse(a.contains(-1, 5, 5));
    }

    @Test
    void containsIncludesBoundaries() {
        BattleArea a = new BattleArea(0, 0, 0, 10, 10, 10);
        assertTrue(a.contains(0, 0, 0));
        assertTrue(a.contains(10, 10, 10));
    }

    @Test
    void deriveExpandsByPadding() {
        List<double[]> pts = List.of(
                new double[]{0, 64, 0},
                new double[]{100, 70, 50});
        BattleArea a = BattleArea.derive(pts, 16.0);
        assertEquals(-16.0, a.minX(), 1e-9);
        assertEquals(-16.0, a.minZ(), 1e-9);
        assertEquals(116.0, a.maxX(), 1e-9);
        assertEquals(66.0, a.maxZ(), 1e-9);
        assertEquals(48.0, a.minY(), 1e-9);
        assertEquals(86.0, a.maxY(), 1e-9);
    }

    @Test
    void deriveFromEmptyReturnsEmpty() {
        assertTrue(BattleArea.derive(List.of(), 16.0).isEmpty());
        assertTrue(BattleArea.derive(null, 16.0).isEmpty());
    }

    @Test
    void dimensions() {
        BattleArea a = new BattleArea(-10, 0, -5, 10, 64, 15);
        assertEquals(20.0, a.sizeX(), 1e-9);
        assertEquals(64.0, a.sizeY(), 1e-9);
        assertEquals(20.0, a.sizeZ(), 1e-9);
        assertEquals(0.0, a.centerX(), 1e-9);
        assertEquals(5.0, a.centerZ(), 1e-9);
    }

    @Test
    void rejectsNaN() {
        try {
            new BattleArea(Double.NaN, 0, 0, 1, 1, 1);
            assert false : "should have thrown";
        } catch (IllegalArgumentException ignored) {
            // expected
        }
    }
}