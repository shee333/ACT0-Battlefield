package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/** TAB 战绩面板中的一名玩家。state: 0=战斗中, 1=部署中, 2=离线, 3=倒地。 */
public record TabEntryDto(String name, int faction, int kills, int deaths, int ping, int state, int squad) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeVarInt(faction);
        buf.writeVarInt(kills);
        buf.writeVarInt(deaths);
        buf.writeVarInt(ping);
        buf.writeVarInt(state);
        buf.writeVarInt(squad);
    }

    public static TabEntryDto decode(FriendlyByteBuf buf) {
        return new TabEntryDto(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }
}