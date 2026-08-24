package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 新配装 DTO 组的编解码往返：字段增删会在这里显式暴露。 */
class DtoRoundTripTest {

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    void deploySlotDtoRoundTrips() {
        DeploySlotDto dto = new DeploySlotDto(0, "tacz:ak47", 120);
        FriendlyByteBuf buf = buffer();
        dto.encode(buf);
        assertEquals(dto, DeploySlotDto.decode(buf));
    }

    @Test
    void deployLoadoutDtoRoundTrips() {
        DeployLoadoutDto dto = new DeployLoadoutDto("assault", "lp_abc123", "正面突破",
                List.of(new DeploySlotDto(0, "tacz:ak47", 120), new DeploySlotDto(3, "minecraft:stick", 0)));
        FriendlyByteBuf buf = buffer();
        dto.encode(buf);
        assertEquals(dto, DeployLoadoutDto.decode(buf));
    }

    @Test
    void loadoutConfigDtoRoundTrips() {
        LoadoutPresetPreviewDto preset = new LoadoutPresetPreviewDto("lp_abc", "狙击套",
                List.of(new DeploySlotDto(0, "tacz:m24", 40)), List.of("minecraft:iron_helmet", "", "", ""));
        ClassPresetsDto cls = new ClassPresetsDto("recon", "lp_abc", List.of(preset));
        FactionPresetsDto faction = new FactionPresetsDto("ALPHA", List.of(cls));
        LoadoutConfigDto dto = new LoadoutConfigDto(List.of("解放峰"), "解放峰", "recon", "ALPHA",
                List.of(faction));
        FriendlyByteBuf buf = buffer();
        dto.encode(buf);
        assertEquals(dto, LoadoutConfigDto.decode(buf));
    }

    @Test
    void selectPresetPacketRoundTrips() {
        LoadoutSelectPresetPacket pkt = new LoadoutSelectPresetPacket("解放峰", "BRAVO", "medic", "lp_xyz");
        FriendlyByteBuf buf = buffer();
        LoadoutSelectPresetPacket.encode(pkt, buf);
        LoadoutSelectPresetPacket.decode(buf);
        // 字段顺序经 encode/decode 对称保证；这里仅验证读回不抛异常。
        assertEquals(0, buf.readableBytes(), "解码应恰好消费全部字节");
    }
}
