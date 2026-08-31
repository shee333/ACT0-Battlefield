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

    @Test
    void unbalancedCountsPreferSmallerSide() {
        assertSame(Faction.ALPHA, LatecomerAssignment.randomFaction(2, Integer.MAX_VALUE, 5, Integer.MAX_VALUE),
                "ALPHA 人少时应分到 ALPHA");
        assertSame(Faction.BRAVO, LatecomerAssignment.randomFaction(5, Integer.MAX_VALUE, 2, Integer.MAX_VALUE),
                "BRAVO 人少时应分到 BRAVO");
}

    @RepeatedTest(20)
    void sequentialJoinsStayBalanced() {
        // 模拟 0:0 起步顺序加入 20 人，双方人数差始终不应超过 1。
        int alpha = 0;
        int bravo = 0;
        for (int i = 0; i < 20; i++) {
            Faction f = LatecomerAssignment.randomFaction(alpha, Integer.MAX_VALUE, bravo, Integer.MAX_VALUE);
            if (f == Faction.ALPHA) {
                alpha++;
            } else {
                bravo++;
            }
            assertTrue(Math.abs(alpha - bravo) <= 1,
                    "第 " + (i + 1) + " 人加入后人数差应为 0 或 1，实际 " + alpha + ":" + bravo);
        }
    }
}
