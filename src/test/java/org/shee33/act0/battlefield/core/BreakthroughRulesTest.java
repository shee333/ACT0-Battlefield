package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BreakthroughRulesTest {

    @Test
    void testStandardDefaults() {
        BreakthroughRules rules = BreakthroughRules.standard();
        assertEquals(300, rules.startingTickets());
        assertEquals(50, rules.ticketsPerSector());
        assertEquals(15.0, rules.captureSeconds(), 1e-9);
        assertEquals(4, rules.maxCaptureBoost());
    }

    @Test
    void testCustomBuilder() {
        BreakthroughRules rules = new BreakthroughRules.Builder()
                .startingTickets(500)
                .ticketsPerSector(75)
                .captureSeconds(10.0)
                .maxCaptureBoost(6)
                .build();
        assertEquals(500, rules.startingTickets());
        assertEquals(75, rules.ticketsPerSector());
        assertEquals(10.0, rules.captureSeconds(), 1e-9);
        assertEquals(6, rules.maxCaptureBoost());
    }

    @Test
    void testInvalidStartingTickets() {
        assertThrows(IllegalArgumentException.class,
                () -> new BreakthroughRules.Builder().startingTickets(0).build());
    }

    @Test
    void testCaptureStep() {
        BreakthroughRules rules = new BreakthroughRules.Builder()
                .startingTickets(300)
                .captureSeconds(15.0)
                .maxCaptureBoost(4)
                .build();
        // 2 玩家 × 1.0 秒 ÷ 15 秒 = 0.1333...
        double step = rules.captureStep(2, 1.0);
        assertEquals(2.0 / 15.0, step, 1e-9);
    }

    @Test
    void testCaptureStepCapped() {
        BreakthroughRules rules = new BreakthroughRules.Builder()
                .startingTickets(300)
                .captureSeconds(15.0)
                .maxCaptureBoost(4)
                .build();
        // 8 玩家被上限封顶为 4，加速效果等同 4 玩家
        double step = rules.captureStep(8, 1.0);
        assertEquals(4.0 / 15.0, step, 1e-9);
    }
}
