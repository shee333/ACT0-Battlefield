package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/** 部署地图上的一个据点点位。 */
public record DeployPointDto(String id, String name, int owner, boolean deployable, double x, double y, double z) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(name);
        buf.writeVarInt(owner);
        buf.writeBoolean(deployable);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    public static DeployPointDto decode(FriendlyByteBuf buf) {
        return new DeployPointDto(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
