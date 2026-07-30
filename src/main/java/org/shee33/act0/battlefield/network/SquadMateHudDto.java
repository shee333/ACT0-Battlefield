package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * HUD 用的小队成员快照。首版没有真实 Squad 系统，使用"同阵营前若干玩家"作为小队显示。
 *
 * @param name      玩家名
 * @param healthPct 血量百分比（0~100）
 * @param alive     是否在线/存活
 * @param self      是否为当前客户端玩家
 * @param downed    是否倒地等待救援
 */
public record SquadMateHudDto(String name, int healthPct, boolean alive, boolean self, boolean downed,
                              boolean isSquadLeader) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeVarInt(healthPct);
        buf.writeBoolean(alive);
        buf.writeBoolean(self);
        buf.writeBoolean(downed);
        buf.writeBoolean(isSquadLeader);
    }

    public static SquadMateHudDto decode(FriendlyByteBuf buf) {
        return new SquadMateHudDto(buf.readUtf(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }
}