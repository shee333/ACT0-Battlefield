package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.SquadJoinRules.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquadJoinRulesTest {

    @Test
    void joinsAnOpenSameFactionSquad() {
        assertEquals(Result.OK, SquadJoinRules.canJoin(1, 2, 0, false, true));
        assertEquals(Result.OK, SquadJoinRules.canJoin(0, 2, 3, false, true), "未加入者也能加入");
        assertEquals(Result.OK, SquadJoinRules.canJoin(1, 2, 3, false, true), "还差一个位就能进");
    }

    @Test
    void rejectsFullSquadAtTheCapNotAboveIt() {
        assertEquals(Result.OK, SquadJoinRules.canJoin(1, 2, SquadManagerLimits.MAX_SQUAD_SIZE - 1, false, true));
        assertEquals(Result.FULL, SquadJoinRules.canJoin(1, 2, SquadManagerLimits.MAX_SQUAD_SIZE, false, true));
        assertEquals(Result.FULL, SquadJoinRules.canJoin(1, 2, 99, false, true));
    }

    @Test
    void rejectsLockedAndCrossFactionAndSelf() {
        assertEquals(Result.LOCKED, SquadJoinRules.canJoin(1, 2, 0, true, true));
        assertEquals(Result.WRONG_FACTION, SquadJoinRules.canJoin(1, 102, 0, false, false));
        assertEquals(Result.ALREADY_IN, SquadJoinRules.canJoin(2, 2, 1, false, true));
        assertEquals(Result.NO_SUCH_SQUAD, SquadJoinRules.canJoin(1, 0, 0, false, true));
        assertEquals(Result.NO_SUCH_SQUAD, SquadJoinRules.canJoin(1, -3, 0, false, true));
    }

    /** 跨阵营必须在满员/锁定之前就被拒——否则会泄露敌方小队的锁定与人数状态。 */
    @Test
    void crossFactionIsRejectedBeforeAnyCapacityCheck() {
        assertEquals(Result.WRONG_FACTION, SquadJoinRules.canJoin(1, 102, 99, true, false));
    }

    /** 同时锁定又满员时报"已锁定"：满员会随人员流动缓解，锁定不会。 */
    @Test
    void lockedTakesPrecedenceOverFull() {
        assertEquals(Result.LOCKED,
                SquadJoinRules.canJoin(1, 2, SquadManagerLimits.MAX_SQUAD_SIZE, true, true));
    }

    /** 已在队内优先于一切：重复点自己的小队不该报"已满"。 */
    @Test
    void alreadyInBeatsEveryOtherRejection() {
        assertEquals(Result.ALREADY_IN,
                SquadJoinRules.canJoin(2, 2, SquadManagerLimits.MAX_SQUAD_SIZE, true, false));
    }

    @Test
    void onlyLeaderOfAnExistingSquadMayToggleLock() {
        assertTrue(SquadJoinRules.canToggleLock(true, 1));
        assertFalse(SquadJoinRules.canToggleLock(false, 1), "非队长不能锁队");
        assertFalse(SquadJoinRules.canToggleLock(true, 0), "未加入小队无从锁定");
    }

    @Test
    void leavingRequiresBeingInASquad() {
        assertTrue(SquadJoinRules.canLeave(1));
        assertFalse(SquadJoinRules.canLeave(0));
        assertFalse(SquadJoinRules.canLeave(-1));
    }
}
