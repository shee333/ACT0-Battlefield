package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketPoolTest {

    private static final ConquestRules RULES = ConquestRules.builder()
            .startingTickets(100).bleedPerPointPerSecond(1.0).ticketPerDeath(1.0).build();

    @Test
    void leaderInPointsBleedsOpponent() {
        TicketPool pool = new TicketPool(RULES.startingTickets());
        // 甲方控 3 点，乙方控 1 点 → 差 2 → 乙方每秒掉 2 票
        pool.bleed(3, 1, RULES, 1.0);
        assertEquals(100, pool.tickets(Faction.ALPHA), 1e-9);
        assertEquals(98, pool.tickets(Faction.BRAVO), 1e-9);
    }

    @Test
    void equalPointsNoBleed() {
        TicketPool pool = new TicketPool(RULES.startingTickets());
        pool.bleed(2, 2, RULES, 5.0);
        assertEquals(100, pool.tickets(Faction.ALPHA), 1e-9);
        assertEquals(100, pool.tickets(Faction.BRAVO), 1e-9);
    }

    @Test
    void deathCostsTicket() {
        TicketPool pool = new TicketPool(RULES.startingTickets());
        pool.onDeath(Faction.ALPHA, RULES);
        assertEquals(99, pool.tickets(Faction.ALPHA), 1e-9);
    }

    @Test
    void winnerIsOpponentOfZeroedTeam() {
        TicketPool pool = new TicketPool(2);
        assertNull(pool.winner());
        assertFalse(pool.hasLoser());
        pool.bleed(2, 0, RULES, 5.0); // 乙方掉光
        assertTrue(pool.hasLoser());
        assertSame(Faction.ALPHA, pool.winner());
    }

    @Test
    void ticketsNeverNegativeForDisplay() {
        TicketPool pool = new TicketPool(1);
        pool.bleed(5, 0, RULES, 10.0);
        assertEquals(0, pool.displayTickets(Faction.BRAVO));
    }

    @Test
    void refundRestoresTicket() {
        TicketPool pool = new TicketPool(RULES.startingTickets());
        pool.onDeath(Faction.ALPHA, RULES);
        assertEquals(99, pool.tickets(Faction.ALPHA), 1e-9);
        pool.refund(Faction.ALPHA);
        assertEquals(100, pool.tickets(Faction.ALPHA), 1e-9);
    }

    @Test
    void bleedConfirmedOneSided() {
        ConquestRules rules = ConquestRules.builder()
                .startingTickets(300).bleedPerPointPerSecond(1.0).build();
        TicketPool pool = new TicketPool(300);
        pool.bleed(3, 2, rules, 1.0);
        assertEquals(300, pool.tickets(Faction.ALPHA), 1e-9);
        assertEquals(299, pool.tickets(Faction.BRAVO), 1e-9);
    }

    @Test
    void bleedNoChangeWhenTied() {
        TicketPool pool = new TicketPool(100);
        pool.bleed(3, 3, RULES, 5.0);
        assertEquals(100, pool.tickets(Faction.ALPHA), 1e-9);
        assertEquals(100, pool.tickets(Faction.BRAVO), 1e-9);
    }
}
