package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 突破模式 HUD 单个突破点快照。
 *
 * @param name     据点显示名
 * @param owner    归属：0=中立, 1=ALPHA(攻击方), 2=BRAVO(防守方)
 * @param pressure 压制进度（0~100）
 * @param progress 占领/压制进度（0~100）
 * @param locked   是否已锁定（不可被占领，例如已沦陷的据点或最终目标之外的目标）
 */
public record BreakthroughPointDto(String name, int owner, int pressure, int progress, boolean locked) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeVarInt(owner);
        buf.writeVarInt(pressure);
        buf.writeVarInt(progress);
        buf.writeBoolean(locked);
    }

    public static BreakthroughPointDto decode(FriendlyByteBuf buf) {
        return new BreakthroughPointDto(
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean());
    }
}
