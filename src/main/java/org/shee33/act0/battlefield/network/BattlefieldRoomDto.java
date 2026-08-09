package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 对局浏览器一行的快照：既可以是"等待中"的待命世界（地图已布置完毕、正等待玩家凑够人数），
 * 也可以是"运行中"的对局。
 *
 * @param roomKey        形如 {@code "bf@<dimension>"}/{@code "bt@<dimension>"} 的房间键，加入时原样回传
 * @param displayName    战役名称
 * @param breakthrough   {@code false}=征服，{@code true}=突破
 * @param mapName        地图名；服务端保证非空（未命名时已回退填充维度名）
 * @param running        {@code true}=运行中，{@code false}=等待中
 * @param cur            当前人数：等待中=候选名单人数，运行中=参战人数
 * @param max            对局人数上限（按地图自定义，未设时回退全局配置）
 * @param minToStart     自动开始所需人数（按地图自定义，未设时回退全局配置）。与 {@code max}
 *                       是两个独立概念：前者是"满了就进不来"，后者是"够了就开打"
 * @param viewerIn       接收者是否已在该候选名单/对局中
 * @param faction1Name   阵营1（ALPHA）显示名
 * @param faction2Name   阵营2（BRAVO）显示名
 * @param tickets1       阵营1剩余票数；仅 {@code running=true} 时有意义
 * @param tickets2       阵营2剩余票数；仅 {@code running=true} 时有意义
 * @param ticketsMax     起始票数，用于计算对峙条比例
 * @param elapsedSeconds 已进行秒数；仅 {@code running=true} 时有意义
 */
public record BattlefieldRoomDto(
        String roomKey,
        String displayName,
        boolean breakthrough,
        String mapName,
        boolean running,
        int cur,
        int max,
        int minToStart,
        boolean viewerIn,
        String faction1Name,
        String faction2Name,
        int tickets1,
        int tickets2,
        int ticketsMax,
        int elapsedSeconds) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(roomKey);
        buf.writeUtf(displayName);
        buf.writeBoolean(breakthrough);
        buf.writeUtf(mapName);
        buf.writeBoolean(running);
        buf.writeVarInt(cur);
        buf.writeVarInt(max);
        buf.writeVarInt(minToStart);
        buf.writeBoolean(viewerIn);
        buf.writeUtf(faction1Name);
        buf.writeUtf(faction2Name);
        buf.writeVarInt(tickets1);
        buf.writeVarInt(tickets2);
        buf.writeVarInt(ticketsMax);
        buf.writeVarInt(elapsedSeconds);
    }

    public static BattlefieldRoomDto decode(FriendlyByteBuf buf) {
        return new BattlefieldRoomDto(
                buf.readUtf(),
                buf.readUtf(),
                buf.readBoolean(),
                buf.readUtf(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt());
    }
}
