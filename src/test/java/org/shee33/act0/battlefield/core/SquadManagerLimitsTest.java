package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.match.SquadManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** core 层的上限副本必须与 MC 层的真值一致，否则准入规则会按错误的容量放人进来。 */
class SquadManagerLimitsTest {

    @Test
    void coreCopyMatchesTheAuthoritativeLimit() {
        assertEquals(SquadManager.MAX_SQUAD_SIZE, SquadManagerLimits.MAX_SQUAD_SIZE);
    }
}
