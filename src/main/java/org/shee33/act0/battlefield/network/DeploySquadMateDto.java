package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/** 部署地图上的一个小队成员出生点。 */
public record DeploySquadMateDto(String id, String name, int entityId, boolean deployable, double x, double z) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(name);
        buf.writeVarInt(entityId);
        buf.writeBoolean(deployable);
        buf.writeDouble(x);
        buf.writeDouble(z);
    }

    public static DeploySquadMateDto decode(FriendlyByteBuf buf) {
        return new DeploySquadMateDto(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(),
                buf.readDouble(), buf.readDouble());
    }
}
