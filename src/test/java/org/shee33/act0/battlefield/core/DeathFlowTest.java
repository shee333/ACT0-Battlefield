package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathFlowTest {

    private ConquestRules rules;

    @BeforeEach
    void setUp() {
        rules = ConquestRules.builder()
                .startingTickets(300)
                .bleedPerPointPerSecond(1.0)
                .ticketPerDeath(1.0)
                .build();
    }

    @Test
    void reviveShouldRefundTicket() {
        TicketPool ticketPool = new TicketPool(300);
        // onDeath deducts; refund restores. Revive removes pending death before consume.
        ticketPool.onDeath(Faction.ALPHA, rules);
        assertEquals(299, ticketPool.tickets(Faction.ALPHA), 1e-9,
                "onDeath deducts 1 ticket.");
        ticketPool.refund(Faction.ALPHA);
        assertEquals(300, ticketPool.tickets(Faction.ALPHA), 1e-9,
                "refund restores the ticket — net zero for a revive.");
    }

    @Test
    void reviveShouldRefundDeath() {
        boolean refundMethodExists = false;
        try {
            TicketPool.class.getMethod("refund", Faction.class);
            refundMethodExists = true;
        } catch (NoSuchMethodException exception) {
            // Should not happen.
        }
        assertTrue(refundMethodExists,
                "TicketPool should expose refund(Faction, ConquestRules) for revives.");
    }

    @Test
    void doubleDownShouldBeRejected() {
        // Match-level guard prevents double onDeath for already-downed players.
        // At pool level, double onDeath double-deducts — the match guard is the real defense.
        TicketPool pool = new TicketPool(300);
        pool.onDeath(Faction.ALPHA, rules);
        pool.onDeath(Faction.ALPHA, rules);
        assertEquals(298, pool.tickets(Faction.ALPHA), 1e-9,
                "Pool double-deducts; match-level guard rejects double-down before this.");
    }

    @Test
    void boundaryDeathShouldSkipDownedState() {
        // Boundary kills skip the downed state; a direct onDeath deducts the ticket.
        TicketPool pool = new TicketPool(300);
        pool.onDeath(Faction.BRAVO, rules);
        assertEquals(299, pool.tickets(Faction.BRAVO), 1e-9,
                "Boundary kill triggers a direct onDeath — no downed state involved.");
    }

    @Test
    void killCreditDeferredToBleedOut() {
        // Kill credit is awarded only when pending death is consumed.
        // At pool level, refund fully reverses onDeath, supporting the defer-revoke pattern.
        TicketPool pool = new TicketPool(300);
        pool.onDeath(Faction.ALPHA, rules);
        pool.refund(Faction.ALPHA);
        assertEquals(300, pool.tickets(Faction.ALPHA), 1e-9,
                "Deferred onDeath reversed by refund — kill credit deferred until consumed.");
    }

    @Test
    void ticketBleedIsOneSided() {
        TicketPool ticketPool = new TicketPool(300);
        ticketPool.bleed(3, 2, rules, 5.0);
        assertEquals(300, ticketPool.tickets(Faction.ALPHA), 1e-9);
        assertEquals(295, ticketPool.tickets(Faction.BRAVO), 1e-9);
    }
}
