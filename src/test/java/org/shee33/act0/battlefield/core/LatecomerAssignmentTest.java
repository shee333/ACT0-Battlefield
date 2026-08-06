package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatecomerAssignmentTest {

    @Test
    void bothFullReturnsNull() {
        assertNull(LatecomerAssignment.randomFaction(5, 5, 5, 5));
    }

    @Test
    void alphaFullForcesBravo() {
        assertSame(Faction.BRAVO, LatecomerAssignment.randomFaction(5, 5, 3, 5));
    }

    @Test
    void bravoFullForcesAlpha() {
        assertSame(Faction.ALPHA, LatecomerAssignment.randomFaction(3, 5, 5, 5));
    }

    @RepeatedTest(50)
    void alphaFullAlwaysForcesBravoAcrossManyDraws() {
        assertSame(Faction.BRAVO, LatecomerAssignment.randomFaction(10, 10, 1, 10));
    }

    @RepeatedTest(50)
    void bravoFullAlwaysForcesAlphaAcrossManyDraws() {
        assertSame(Faction.ALPHA, LatecomerAssignment.randomFaction(1, 10, 10, 10));
    }

    @Test
    void unlimitedCapacityNeverReturnsNull() {
        assertNotNull(LatecomerAssignment.randomFaction(1000, Integer.MAX_VALUE, 0, Integer.MAX_VALUE));
    }

    @Test
    void bothOpenEventuallyProducesBothFactions() {
        boolean sawAlpha = false;
        boolean sawBravo = false;
        for (int i = 0; i < 500 && !(sawAlpha && sawBravo); i++) {
            Faction f = LatecomerAssignment.randomFaction(0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
            assertNotNull(f);
            if (f == Faction.ALPHA) {
                sawAlpha = true;
            } else {
                sawBravo = true;
            }
        }
        assertTrue(sawAlpha, "500 次随机应至少出现一次 ALPHA");
        assertTrue(sawBravo, "500 次随机应至少出现一次 BRAVO");
    }
}
