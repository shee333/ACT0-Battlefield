package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoldierClassTest {

    @Test
    void hasExactlyFourClasses() {
        assertEquals(4, SoldierClass.values().length);
    }

    @Test
    void resolvesByIdIgnoringCaseAndPadding() {
        assertEquals(SoldierClass.MEDIC, SoldierClass.byId("medic"));
        assertEquals(SoldierClass.MEDIC, SoldierClass.byId("  MEDIC  "));
        assertEquals(SoldierClass.RECON, SoldierClass.byId("Recon"));
    }

    @Test
    void unknownIdIsNull() {
        assertNull(SoldierClass.byId("sniper"));
        assertNull(SoldierClass.byId(""));
        assertNull(SoldierClass.byId(null));
    }

    /** 读档与解包路径绝不能因为脏数据抛异常。 */
    @Test
    void unknownIdFallsBackToDefault() {
        assertEquals(SoldierClass.DEFAULT, SoldierClass.byIdOrDefault("sniper"));
        assertEquals(SoldierClass.DEFAULT, SoldierClass.byIdOrDefault(null));
        assertEquals(SoldierClass.DEFAULT, SoldierClass.byIdOrDefault(""));
    }

    /** id 是持久化键，改动会让所有玩家存档里的兵种选择失效。 */
    @Test
    void idsAreStableAndDistinct() {
        assertEquals("assault", SoldierClass.ASSAULT.id());
        assertEquals("medic", SoldierClass.MEDIC.id());
        assertEquals("engineer", SoldierClass.ENGINEER.id());
        assertEquals("recon", SoldierClass.RECON.id());
    }

    @Test
    void everyClassHasDisplayNameAndAbilityBrief() {
        for (SoldierClass c : SoldierClass.values()) {
            assertNotNull(c.displayName());
            assertTrue(!c.displayName().isBlank(), c + " 缺显示名");
            assertTrue(!c.abilityBrief().isBlank(), c + " 缺能力说明");
        }
    }
}
