package org.shee33.act0.battlefield.core.arena;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerArenaLoadoutTest {

    private static ArenaCatalog catalogWithTwoRifles() {
        ArenaCatalog c = new ArenaCatalog();
        c.addWeapon(WeaponCategory.RIFLE, new ArenaWeaponEntry("tacz:ak47", "AK-47", 120));
        c.addWeapon(WeaponCategory.RIFLE, new ArenaWeaponEntry("tacz:m4", "M4", 120));
        c.addWeapon(WeaponCategory.PISTOL, new ArenaWeaponEntry("tacz:glock", "Glock", 45));
        c.addItem(LoadoutSlot.GADGET_1, new ArenaItemEntry("act0:ammo_box", "弹药箱", 1));
        return c;
    }

    @Test
    void emptyLoadoutHasNoPicks() {
        assertTrue(PlayerArenaLoadout.EMPTY.isEmpty());
        assertNull(PlayerArenaLoadout.EMPTY.pick(LoadoutSlot.PRIMARY));
    }

    @Test
    void withReplacesAndClears() {
        PlayerArenaLoadout l = PlayerArenaLoadout.EMPTY.with(LoadoutSlot.PRIMARY, "tacz:m4");
        assertEquals("tacz:m4", l.pick(LoadoutSlot.PRIMARY));
        assertEquals("tacz:ak47", l.with(LoadoutSlot.PRIMARY, "tacz:ak47").pick(LoadoutSlot.PRIMARY));
        assertTrue(l.with(LoadoutSlot.PRIMARY, null).isEmpty(), "传 null 应清除该槽位");
        assertTrue(l.with(LoadoutSlot.PRIMARY, "  ").isEmpty(), "传空白同样视为清除");
        assertFalse(l.isEmpty(), "with 必须返回新实例，不得改动原对象");
    }

    /** record 持有外部传入的 map，不做防御性拷贝就会被调用方后续修改。 */
    @Test
    void picksAreCopiedAndImmutable() {
        Map<LoadoutSlot, String> src = new EnumMap<>(LoadoutSlot.class);
        src.put(LoadoutSlot.PRIMARY, "tacz:ak47");
        PlayerArenaLoadout l = new PlayerArenaLoadout(src);
        src.put(LoadoutSlot.SECONDARY, "tacz:glock");
        assertNull(l.pick(LoadoutSlot.SECONDARY), "构造后修改入参不应影响已建实例");
        assertThrows(UnsupportedOperationException.class,
                () -> l.picks().put(LoadoutSlot.MELEE, "tacz:knife"));
    }

    @Test
    void blankPicksAreDroppedAtConstruction() {
        Map<LoadoutSlot, String> src = new EnumMap<>(LoadoutSlot.class);
        src.put(LoadoutSlot.PRIMARY, "");
        src.put(LoadoutSlot.SECONDARY, "   ");
        src.put(LoadoutSlot.MELEE, "tacz:knife");
        PlayerArenaLoadout l = new PlayerArenaLoadout(src);
        assertEquals(1, l.picks().size(), "空白 ID 不应进入存档");
        assertEquals("tacz:knife", l.pick(LoadoutSlot.MELEE));
    }

    @Test
    void sanitizeDropsPicksNoLongerInCatalog() {
        ArenaCatalog c = catalogWithTwoRifles();
        PlayerArenaLoadout l = PlayerArenaLoadout.EMPTY
                .with(LoadoutSlot.PRIMARY, "tacz:m4")
                .with(LoadoutSlot.SECONDARY, "tacz:deagle")
                .with(LoadoutSlot.GADGET_1, "act0:ammo_box");
        PlayerArenaLoadout clean = l.sanitize(c);
        assertEquals("tacz:m4", clean.pick(LoadoutSlot.PRIMARY));
        assertNull(clean.pick(LoadoutSlot.SECONDARY), "已下架的枪应被清出存档");
        assertEquals("act0:ammo_box", clean.pick(LoadoutSlot.GADGET_1));
    }

    /** 全部合法时返回同一实例，避免在每次读取时白白产生垃圾对象。 */
    @Test
    void sanitizeReturnsSameInstanceWhenNothingChanged() {
        ArenaCatalog c = catalogWithTwoRifles();
        PlayerArenaLoadout l = PlayerArenaLoadout.EMPTY.with(LoadoutSlot.PRIMARY, "tacz:ak47");
        assertSame(l, l.sanitize(c));
        assertSame(PlayerArenaLoadout.EMPTY, PlayerArenaLoadout.EMPTY.sanitize(c));
    }

    /** 目录里删掉玩家选的枪之后，出生时必须自动回落，而不是发不出武器。 */
    @Test
    void resolveFallsBackToCatalogDefault() {
        ArenaCatalog c = catalogWithTwoRifles();
        Map<LoadoutSlot, String> resolved = PlayerArenaLoadout.EMPTY
                .with(LoadoutSlot.PRIMARY, "tacz:removed")
                .resolve(c);
        assertEquals("tacz:ak47", resolved.get(LoadoutSlot.PRIMARY), "失效选择应回落目录首项");
        assertEquals("tacz:glock", resolved.get(LoadoutSlot.SECONDARY), "没选过的槽位也给默认项");
        assertEquals("act0:ammo_box", resolved.get(LoadoutSlot.GADGET_1));
        assertFalse(resolved.containsKey(LoadoutSlot.MELEE), "目录没配近战时该槽位应缺席");
        assertFalse(resolved.containsKey(LoadoutSlot.GADGET_2));
    }

    @Test
    void resolveKeepsValidPickOverDefault() {
        ArenaCatalog c = catalogWithTwoRifles();
        Map<LoadoutSlot, String> resolved =
                PlayerArenaLoadout.EMPTY.with(LoadoutSlot.PRIMARY, "tacz:m4").resolve(c);
        assertEquals("tacz:m4", resolved.get(LoadoutSlot.PRIMARY));
    }

    /** 生效配装按槽位声明顺序遍历，出生发装依赖这个顺序写快捷栏。 */
    @Test
    void resolveIsOrderedBySlotDeclaration() {
        ArenaCatalog c = catalogWithTwoRifles();
        List<LoadoutSlot> order = new ArrayList<>(PlayerArenaLoadout.EMPTY.resolve(c).keySet());
        assertEquals(List.of(LoadoutSlot.PRIMARY, LoadoutSlot.SECONDARY, LoadoutSlot.GADGET_1), order);
    }

    @Test
    void resolveOnEmptyCatalogYieldsNothing() {
        assertTrue(PlayerArenaLoadout.EMPTY
                .with(LoadoutSlot.PRIMARY, "tacz:ak47")
                .resolve(new ArenaCatalog())
                .isEmpty(), "目录空时不应凭玩家存档发出任何装备");
    }
}
