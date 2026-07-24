package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * HUD/世界标记用的倒地队友快照。
 */
public record DownedMateDto(String name, double x, double y, double z, int remainingSeconds) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(remainingSeconds);
    }

    public static DownedMateDto decode(FriendlyByteBuf buf) {
        return new DownedMateDto(buf.readUtf(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readVarInt());
    }
}