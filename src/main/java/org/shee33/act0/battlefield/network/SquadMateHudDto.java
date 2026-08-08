package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * HUD 用的小队成员快照，{@link BattleHudDto} 与 {@link BreakthroughHudDto} 共用。
 *
 * @param name         玩家名
 * @param healthPct    血量百分比（0~100）
 * @param alive        是否在线/存活
 * @param self         是否为当前客户端玩家
 * @param downed       是否倒地等待救援
 * @param isSquadLeader 是否为小队队长
 * @param x            世界坐标 X，供小地图绘制队友点位
 * @param z            世界坐标 Z（小地图是 2D 俯视，不需要 Y）
 */
public record SquadMateHudDto(String name, int healthPct, boolean alive, boolean self, boolean downed,
                              boolean isSquadLeader, double x, double z) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeVarInt(healthPct);
        buf.writeBoolean(alive);
        buf.writeBoolean(self);
        buf.writeBoolean(downed);
        buf.writeBoolean(isSquadLeader);
        buf.writeDouble(x);
        buf.writeDouble(z);
    }

    public static SquadMateHudDto decode(FriendlyByteBuf buf) {
        return new SquadMateHudDto(buf.readUtf(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readDouble(), buf.readDouble());
    }
}