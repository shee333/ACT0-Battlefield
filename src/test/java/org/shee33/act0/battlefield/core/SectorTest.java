package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SectorTest {

    @Test
    void testValidSector() {
        Sector s = new Sector(0, List.of(1, 2, 3), "Sector A");
        assertEquals(0, s.id());
        assertEquals(List.of(1, 2, 3), s.pointIds());
        assertEquals("Sector A", s.displayName());
    }

    @Test
    void testInvalidId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Sector(-1, List.of(1), "Sector A"));
    }

    @Test
    void testEmptyPointIds() {
        assertThrows(IllegalArgumentException.class,
                () -> new Sector(0, List.of(), "Sector A"));
    }

    @Test
    void testContainsPoint() {
        Sector s = new Sector(1, List.of(10, 20, 30), "Sector B");
        assertTrue(s.containsPoint(10));
        assertTrue(s.containsPoint(20));
        assertTrue(s.containsPoint(30));
        assertFalse(s.containsPoint(99));
    }

    @Test
    void testEquality() {
        Sector a = new Sector(0, List.of(1, 2), "Sector A");
        Sector b = new Sector(0, List.of(1, 2), "Sector A");
        Sector c = new Sector(1, List.of(1, 2), "Sector A");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
