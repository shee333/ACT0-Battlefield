package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientDownedFeedback;

import java.util.function.Supplier;

/**
 * S→C：倒地/救援视觉反馈。{@code kind=0} 表示倒地开始（{@code payload} 忽略）；
 * {@code kind=1} 表示已被救起（{@code payload} 为救援者名字）；{@code kind=2} 表示倒地状态
 * 已提前结束但非被救起（倒地超时/主动放弃触发重生时发出），仅清除横幅/vignette，不显示
 * "已被救起"提示。
 */
public final class DownedFeedbackPacket {
    private final byte kind;
    private final String payload;

    public DownedFeedbackPacket(byte kind, String payload) {
        this.kind = kind;
        this.payload = payload != null ? payload : "";
    }

    public static void encode(DownedFeedbackPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.kind);
        buf.writeUtf(msg.payload);
    }

    public static DownedFeedbackPacket decode(FriendlyByteBuf buf) {
        return new DownedFeedbackPacket(buf.readByte(), buf.readUtf());
    }

    public static void handle(DownedFeedbackPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    if (msg.kind == 1) {
                        ClientDownedFeedback.triggerRevived(msg.payload);
                    } else if (msg.kind == 2) {
                        ClientDownedFeedback.clear();
                    } else {
                        ClientDownedFeedback.triggerDowned();
                    }
                }));
        context.setPacketHandled(true);
    }
}
