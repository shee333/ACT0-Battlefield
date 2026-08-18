package org.shee33.act0.battlefield.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.core.SoldierClass;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DtoRoundTripTest {

    @Test
    void squadMateHudDtoRoundTrip() {
        SquadMateHudDto dto = new SquadMateHudDto("Player1", 75, true, false, true, false,
                -1015.44, 661.07);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        dto.encode(buf);
        SquadMateHudDto decoded = SquadMateHudDto.decode(buf);
        assertEquals(dto.name(), decoded.name());
        assertEquals(dto.healthPct(), decoded.healthPct());
        assertEquals(dto.alive(), decoded.alive());
        assertEquals(dto.self(), decoded.self());
        assertEquals(dto.downed(), decoded.downed());
        assertEquals(dto.isSquadLeader(), decoded.isSquadLeader());
        assertEquals(dto.x(), decoded.x());
        assertEquals(dto.z(), decoded.z());
    }

    @Test
    void battleHudDtoRoundTrip() {
        ControlPointHudDto cp = new ControlPointHudDto("A", 1, 1, 50, 100.0, 64.0, 200.0, 1.0, 256, 3);
        SquadMateHudDto sm = new SquadMateHudDto("P1", 80, true, false, false, false, 12.5, -34.25);
        DownedMateDto dm = new DownedMateDto("P2", 10.0, 64.0, 20.0, 12);
        BattleHudDto dto = new BattleHudDto(1, 250, 180, 300,
                List.of(cp), List.of(sm),
                "A", 1, 50, 1,
                List.of(dm), "P2", 60, "P3", 35, false, 5, 3, true);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        dto.encode(buf);
        BattleHudDto decoded = BattleHudDto.decode(buf);
        assertEquals(dto.myFaction(), decoded.myFaction());
        assertEquals(dto.alphaTickets(), decoded.alphaTickets());
        assertEquals(1, decoded.points().size());
        assertEquals(1, decoded.squad().size());
        assertEquals(1, decoded.downedMates().size());
        assertEquals("P2", decoded.revivingName());
        assertEquals(60, decoded.revivingProgress());
        assertEquals("P3", decoded.beingRevivedByName());
        assertEquals(35, decoded.beingRevivedProgress());
        assertEquals(5, decoded.streak());
        assertEquals(3, decoded.squadOrderPointId());
        assertEquals(true, decoded.squadOrderAttack());
    }

    @Test
    void deployStatusDtoRoundTrip() {
        DeployPointDto dp = new DeployPointDto("0", "A", 1, true, 100.0, 65.0, 200.0);
        DeploySquadMateDto sm = new DeploySquadMateDto("uuid", "Mate", 42, true, 105.0, 64.0, 210.0);
        DeployAllyDto ally = new DeployAllyDto("ally-uuid", "Ally", 43, 110.0, 64.0, 220.0);
        DeployStatusDto dto = new DeployStatusDto(true, true, true, true, "point", "0", 60,
                100.0, 64.0, 200.0, 105.0, 64.0, 210.0,
                List.of(dp), List.of(sm), List.of(ally),
                true, -50.0, 0.0, -50.0, 150.0, 128.0, 150.0, false, 42,
                "征服模式", "解放峰");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        dto.encode(buf);
        DeployStatusDto decoded = DeployStatusDto.decode(buf);
        assertEquals(dto.active(), decoded.active());
        assertEquals(dto.hasArea(), decoded.hasArea());
        assertEquals(dto.areaMinX(), decoded.areaMinX());
        assertEquals(dto.areaMaxX(), decoded.areaMaxX());
        assertEquals(dto.areaExplicit(), decoded.areaExplicit());
        assertEquals(1, decoded.points().size());
        assertEquals(1, decoded.squadMates().size());
        assertEquals(1, decoded.allies().size());
        assertEquals("Ally", decoded.allies().get(0).name());
        assertEquals(dto.spectateEntityId(), decoded.spectateEntityId());
        assertEquals("征服模式", decoded.modeName());
        assertEquals("解放峰", decoded.mapName());
    }

    @Test
    void deployAllyDtoRoundTrip() {
        DeployAllyDto dto = new DeployAllyDto("uuid-1", "Rifleman", 7, 12.5, 64.0, -30.25);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        dto.encode(buf);
        DeployAllyDto decoded = DeployAllyDto.decode(buf);
        assertEquals(dto.id(), decoded.id());
        assertEquals(dto.name(), decoded.name());
        assertEquals(dto.entityId(), decoded.entityId());
        assertEquals(dto.x(), decoded.x());
        assertEquals(dto.y(), decoded.y());
        assertEquals(dto.z(), decoded.z());
    }

    @Test
    void deployLoadoutDtoRoundTrip() {
        DeploySlotOptionsDto slot = new DeploySlotOptionsDto(0, "主武器", "tacz:m4a1",
                List.of(new DeployOptionDto("tacz:m4a1", "M4A1 卡宾枪"),
                        new DeployOptionDto("tacz:ak74", "AK-74")));
        DeployLoadoutDto dto = new DeployLoadoutDto("medic", List.of(slot));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        dto.encode(buf);
        DeployLoadoutDto decoded = DeployLoadoutDto.decode(buf);
        assertEquals(1, decoded.slots().size());
        DeploySlotOptionsDto decodedSlot = decoded.slots().get(0);
        assertEquals(slot.slotIndex(), decodedSlot.slotIndex());
        assertEquals(slot.slotName(), decodedSlot.slotName());
        assertEquals(slot.currentItemName(), decodedSlot.currentItemName());
        assertEquals(slot.options(), decodedSlot.options(), "ID 与显示名必须成对往返");
        assertEquals("M4A1 卡宾枪", decodedSlot.currentDisplayName());
    }
}