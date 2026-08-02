package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientHud;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * S→C：大战场 HUD 内容（票数 + 据点归属）。{@code show=false} 表示清除 HUD。
 */
public final class SyncHudPacket {

    private static final int MAX_LIST_ENTRIES = 256;

    private final boolean show;
    private final String title;
    private final List<String> lines;

    public SyncHudPacket(boolean show, String title, List<String> lines) {
        this.show = show;
        this.title = title != null ? title : "";
        this.lines = lines != null ? lines : List.of();
    }

    public static void encode(SyncHudPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.show);
        buf.writeUtf(msg.title);
        buf.writeVarInt(msg.lines.size());
        for (String line : msg.lines) {
            buf.writeUtf(line);
        }
    }

    public static SyncHudPacket decode(FriendlyByteBuf buf) {
        boolean show = buf.readBoolean();
        String title = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<String> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(buf.readUtf());
        }
        return new SyncHudPacket(show, title, lines);
    }

    public static void handle(SyncHudPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientHud.accept(msg.show, msg.title, msg.lines)));
        context.setPacketHandled(true);
    }
}
