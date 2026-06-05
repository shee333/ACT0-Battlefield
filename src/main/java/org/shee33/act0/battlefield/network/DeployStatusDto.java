package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 死亡后部署界面状态。
 *
 * @param active         是否处于部署流程
 * @param canSquad       是否可部署到小队队友
 * @param canPoint       是否可部署到己方据点
 * @param canBase        是否可部署到基地
 * @param selectedKind   当前选择：squad / point / base / ""
 * @param readyInTicks   距离可部署剩余 tick，0 表示已可部署
 */
public record DeployStatusDto(boolean active, boolean canSquad, boolean canPoint, boolean canBase,
                              String selectedKind, int readyInTicks) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeBoolean(canSquad);
        buf.writeBoolean(canPoint);
        buf.writeBoolean(canBase);
        buf.writeUtf(selectedKind);
        buf.writeVarInt(readyInTicks);
    }

    public static DeployStatusDto decode(FriendlyByteBuf buf) {
        return new DeployStatusDto(
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readUtf(),
                buf.readVarInt());
    }
}
