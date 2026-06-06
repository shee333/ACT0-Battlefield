package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 大战场状态快照：候选名单/对局的双方人数、票数、据点归属，以及接收者自身阵营与是否可管理。
 *
 * <p>S→C 传输用，驱动加入界面的渲染。{@code active=false} 时表示尚未开局（仅展示候选名单人数）。
 *
 * @param active       是否有进行中的对局
 * @param canManage    接收者是否可管理（OP，可开局/停止）
 * @param myFaction    接收者所属阵营：0=未加入，1=北大西洋公约，2=无邦军团
 * @param alphaCount   北大西洋公约人数（候选名单或对局参战）
 * @param bravoCount   无邦军团人数
 * @param alphaTickets 北大西洋公约票数（仅 active 时有意义）
 * @param bravoTickets 无邦军团票数
 * @param alphaPoints  北大西洋公约控制据点数
 * @param bravoPoints  无邦军团控制据点数
 * @param totalPoints  据点总数
 */
public record BattlefieldStatusDto(
        boolean active,
        boolean canManage,
        int myFaction,
        int alphaCount,
        int bravoCount,
        int alphaTickets,
        int bravoTickets,
        int alphaPoints,
        int bravoPoints,
        int totalPoints) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeBoolean(canManage);
        buf.writeVarInt(myFaction);
        buf.writeVarInt(alphaCount);
        buf.writeVarInt(bravoCount);
        buf.writeVarInt(alphaTickets);
        buf.writeVarInt(bravoTickets);
        buf.writeVarInt(alphaPoints);
        buf.writeVarInt(bravoPoints);
        buf.writeVarInt(totalPoints);
    }

    public static BattlefieldStatusDto decode(FriendlyByteBuf buf) {
        return new BattlefieldStatusDto(
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt());
    }
}
