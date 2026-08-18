package org.shee33.act0.battlefield.core.arena;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.SoldierClass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住「四个兵种各存一套配装」的核心承诺：切兵种不能动到别的兵种已存的选择。
 */
class PlayerMapLoadoutTest {

    @Test
    void emptyStartsAtDefaultClass() {
        assertEquals(SoldierClass.DEFAULT, PlayerMapLoadout.EMPTY.selected());
        assertTrue(PlayerMapLoadout.EMPTY.isEmpty());
        assertSame(PlayerArenaLoadout.EMPTY, PlayerMapLoadout.EMPTY.current());
    }

    /** 这是整个兵种配装的立命之本：改医疗兵不能碰到突击兵。 */
    @Test
    void picksAreIsolatedPerClass() {
        PlayerMapLoadout l = PlayerMapLoadout.EMPTY
                .withPick(SoldierClass.ASSAULT, LoadoutSlot.PRIMARY, "tacz:ak47")
                .withPick(SoldierClass.MEDIC, LoadoutSlot.PRIMARY, "tacz:mp5");

        assertEquals("tacz:ak47", l.loadout(SoldierClass.ASSAULT).pick(LoadoutSlot.PRIMARY));
        assertEquals("tacz:mp5", l.loadout(SoldierClass.MEDIC).pick(LoadoutSlot.PRIMARY));
        assertEquals(null, l.loadout(SoldierClass.RECON).pick(LoadoutSlot.PRIMARY));
    }

    @Test
    void switchingClassKeepsEveryStoredPick() {
        PlayerMapLoadout l = PlayerMapLoadout.EMPTY
                .withPick(SoldierClass.ASSAULT, LoadoutSlot.PRIMARY, "tacz:ak47")
                .withPick(SoldierClass.RECON, LoadoutSlot.PRIMARY, "tacz:m24")
                .withSelected(SoldierClass.RECON);

        assertEquals(SoldierClass.RECON, l.selected());
        assertEquals("tacz:m24", l.current().pick(LoadoutSlot.PRIMARY));
        assertEquals("tacz:ak47", l.loadout(SoldierClass.ASSAULT).pick(LoadoutSlot.PRIMARY));
    }

    @Test
    void selectingSameClassReturnsSameInstance() {
        PlayerMapLoadout l = PlayerMapLoadout.EMPTY.withSelected(SoldierClass.DEFAULT);
        assertSame(PlayerMapLoadout.EMPTY, l);
    }

    /** 只换了兵种也是玩家做过的选择，不能当空记录丢掉。 */
    @Test
    void nonDefaultClassAloneIsNotEmpty() {
        assertFalse(PlayerMapLoadout.EMPTY.withSelected(SoldierClass.MEDIC).isEmpty());
    }

    @Test
    void blankPickClearsThatSlotOnly() {
        PlayerMapLoadout l = PlayerMapLoadout.EMPTY
                .withPick(SoldierClass.ASSAULT, LoadoutSlot.PRIMARY, "tacz:ak47")
                .withPick(SoldierClass.ASSAULT, LoadoutSlot.SECONDARY, "tacz:m1911")
                .withPick(SoldierClass.ASSAULT, LoadoutSlot.PRIMARY, "");

        assertEquals(null, l.loadout(SoldierClass.ASSAULT).pick(LoadoutSlot.PRIMARY));
        assertEquals("tacz:m1911", l.loadout(SoldierClass.ASSAULT).pick(LoadoutSlot.SECONDARY));
    }

    @Test
    void emptyBucketsAreDroppedFromMap() {
        PlayerMapLoadout l = PlayerMapLoadout.EMPTY
                .withPick(SoldierClass.MEDIC, LoadoutSlot.PRIMARY, "tacz:mp5")
                .withPick(SoldierClass.MEDIC, LoadoutSlot.PRIMARY, "");
        assertTrue(l.byClass().isEmpty());
        assertTrue(l.isEmpty());
    }
}
