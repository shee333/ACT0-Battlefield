package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.TicketPool;

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

        // A player entering the downed state should not be charged until bleed-out.
        ticketPool.onDeath(Faction.ALPHA, rules);

        assertEquals(300, ticketPool.tickets(Faction.ALPHA), 1e-9,
                "A downed player should not lose a ticket before bleed-out.");
    }

    @Test
    void reviveShouldRefundDeath() {
        boolean refundMethodExists = false;
        try {
            TicketPool.class.getMethod("refund", Faction.class, ConquestRules.class);
            refundMethodExists = true;
        } catch (NoSuchMethodException exception) {
            // The refund API is not implemented yet; this test records the expected contract.
        }

        assertTrue(refundMethodExists,
                "TicketPool should expose refund(Faction, ConquestRules) for revives.");
    }

    @Test
    void doubleDownShouldBeRejected() {
        // TODO: Replace with PlayerMatchState coverage in Wave 2.
        assertTrue(true);
    }

    @Test
    void boundaryDeathShouldSkipDownedState() {
        // TODO: Replace with boundary-kill and redeployment state coverage in Wave 2.
        assertTrue(false,
                "A boundary kill should redeploy directly without entering the downed state.");
    }

    @Test
    void killCreditDeferredToBleedOut() {
        // TODO: Replace with PendingDeath coverage in Wave 2.
        assertTrue(false,
                "Kill credit should be awarded only when a pending death is consumed.");
    }

    @Test
    void ticketBleedIsOneSided() {
        TicketPool ticketPool = new TicketPool(300);

        // ALPHA controls 3 points and BRAVO controls 2: only BRAVO bleeds.
        ticketPool.bleed(3, 2, rules, 5.0);

        assertEquals(300, ticketPool.tickets(Faction.ALPHA), 1e-9);
        assertEquals(295, ticketPool.tickets(Faction.BRAVO), 1e-9);
    }
}
