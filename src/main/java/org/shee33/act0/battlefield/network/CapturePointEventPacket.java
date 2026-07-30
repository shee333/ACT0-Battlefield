package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientCapturePointEvent;

import java.util.function.Supplier;

/**
 * S→C：据点状态边沿事件（争夺开始 / 占领成功 / 占领失败），驱动 HUD 顶部横幅与小地图
 * 据点图标的一次性提亮反馈。
 *
 * <p>与 {@link BattleHudDto} 里 {@code focusState} 等每 0.5s 轮询同步的“持续状态”不同，
 * 这是一次性 transient 事件：仅在服务端检测到据点状态真正发生变化（owner 变化 / 首次进入
 * 争夺）的那一刻下发一次，不随占点结算的 tick interval 重复广播。纯视觉增强，不影响任何
 * 占领判定/票数平衡逻辑。
 */
public final class CapturePointEventPacket {
    private final int pointId;
    private final Kind kind;
    private final int factionCode;

    public CapturePointEventPacket(int pointId, Kind kind, int factionCode) {
        this.pointId = pointId;
        this.kind = kind;
        this.factionCode = factionCode;
    }

    public static void encode(CapturePointEventPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.pointId);
        buf.writeByte(msg.kind.ordinal());
        buf.writeVarInt(msg.factionCode);
    }

    public static CapturePointEventPacket decode(FriendlyByteBuf buf) {
        int pointId = buf.readVarInt();
        Kind kind = Kind.values()[buf.readByte()];
        int factionCode = buf.readVarInt();
        return new CapturePointEventPacket(pointId, kind, factionCode);
    }

    public static void handle(CapturePointEventPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientCapturePointEvent.trigger(msg.pointId, msg.kind, msg.factionCode)));
        context.setPacketHandled(true);
    }

    /**
     * 据点状态边沿事件类型。
     *
     * <p>{@code factionCode}（0=中立，1=ALPHA，2=BRAVO）随事件类型语义不同：
     * <ul>
     *   <li>{@link #STARTED} — 争夺(CONTESTED)开始时为 0；单方推进(CAPTURING)开始时为推进方。</li>
     *   <li>{@link #CAPTURED_NEW} / {@link #CAPTURED_RECOVERED} — 新的据点归属方。</li>
     *   <li>{@link #LOST} — 丢失据点的一方（被中立化前的归属方）。</li>
     * </ul>
     */
    public enum Kind {
        /** 争夺/推进开始：CONTESTED 或 CAPTURING 首次触发（非每 tick 重复）。 */
        STARTED,
        /** 首次占领成功：从中立（{@code prevOwner==null}）被某方占领。 */
        CAPTURED_NEW,
        /** 夺回据点：从敌方手中（{@code prevOwner} 为敌对阵营）被占领，视觉上更强烈。 */
        CAPTURED_RECOVERED,
        /** 占领失败/据点失守：从己方满控被推回中立。 */
        LOST
    }
}
