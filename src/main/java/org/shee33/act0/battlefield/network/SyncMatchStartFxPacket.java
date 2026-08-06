package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientMatchStartFx;

import java.util.function.Supplier;

/**
 * S→C：比赛开局黑屏转场。
 *
 * <p>倒计时结束、COMBAT 阶段正式开始那一刻触发（"战斗开始"/"突破开始"字幕的同一分支），
 * 全体参战玩家客户端播放全屏黑幕淡入→停留→淡出的三段式转场——《战地》系列部署开局的
 * 仪式感黑屏。与落地反馈 {@code DeploySpawnFxPacket}/{@link ClientMatchStartFx} 之外
 * 的 {@code ClientDeployFx} 语义不同、状态互不干扰，无载荷（纯触发信号）。
 */
public final class SyncMatchStartFxPacket {

    public SyncMatchStartFxPacket() {
    }

    public static void encode(SyncMatchStartFxPacket msg, FriendlyByteBuf buf) {
        // 无载荷：仅作为触发信号。
    }

    public static SyncMatchStartFxPacket decode(FriendlyByteBuf buf) {
        return new SyncMatchStartFxPacket();
    }

    public static void handle(SyncMatchStartFxPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientMatchStartFx.trigger()));
        context.setPacketHandled(true);
    }
}
