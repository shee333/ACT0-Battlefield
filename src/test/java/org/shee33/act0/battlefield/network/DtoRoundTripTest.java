package org.shee33.act0.battlefield.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DtoRoundTripTest {

    @Test
    void squadMateHudDtoRoundTrip() {
        SquadMateHudDto dto = new SquadMateHudDto("Player1", 75, true, false, true, false);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        dto.encode(buf);
        SquadMateHudDto decoded = SquadMateHudDto.decode(buf);
        assertEquals(dto.name(), decoded.name());
        assertEquals(dto.healthPct(), decoded.healthPct());
        assertEquals(dto.alive(), decoded.alive());
        assertEquals(dto.self(), decoded.self());
        assertEquals(dto.downed(), decoded.downed());
        assertEquals(dto.isSquadLeader(), decoded.isSquadLeader());
    }

    @Test
    void battleHudDtoRoundTrip() {
        ControlPointHudDto cp = new ControlPointHudDto("A", 1, 1, 50, 100.0, 64.0, 200.0, 1.0, 256);
        SquadMateHudDto sm = new SquadMateHudDto("P1", 80, true, false, false, false);
        DownedMateDto dm = new DownedMateDto("P2", 10.0, 64.0, 20.0, 12);
        BattleHudDto dto = new BattleHudDto(1, 250, 180, 300,
                List.of(cp), List.of(sm),
                "A", 1, 50, 1,
                List.of(dm), "P2", 60, false, 5);
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
        assertEquals(5, decoded.streak());
    }

    @Test
    void deployStatusDtoRoundTrip() {
        DeployPointDto dp = new DeployPointDto("0", "A", 1, true, 100.0, 65.0, 200.0);
        DeploySquadMateDto sm = new DeploySquadMateDto("uuid", "Mate", 42, true, 105.0, 64.0, 210.0);
        DeployStatusDto dto = new DeployStatusDto(true, true, true, true, "point", "0", 60,
                100.0, 64.0, 200.0, 105.0, 64.0, 210.0,
                List.of(dp), List.of(sm),
                true, -50.0, 0.0, -50.0, 150.0, 128.0, 150.0, false);
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
    }
}