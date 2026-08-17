package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 部署界面底部武器更换面板（《部署界面动效规格文档》3.6 节）单个槽位的数据快照。
 *
 * <p>{@code slotIndex} 与 {@code LoadoutSlot.hotbarIndex()} 一一对应（同时也是玩家快捷栏里
 * 该装备实际所在的格位），供 {@link DeploySlotOverridePacket} 回传选择、以及
 * {@code BattlefieldLoadoutService#apply} 落地时直接定位背包格位使用。
 */
public record DeploySlotOptionsDto(int slotIndex, String slotName, String currentItemName,
                                    List<String> availableItemNames) {

    private static final int MAX_LIST_ENTRIES = 128;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(slotIndex);
        buf.writeUtf(slotName);
        buf.writeUtf(currentItemName);
        buf.writeVarInt(availableItemNames.size());
        for (String item : availableItemNames) {
            buf.writeUtf(item);
        }
    }

    public static DeploySlotOptionsDto decode(FriendlyByteBuf buf) {
        int slotIndex = buf.readVarInt();
        String slotName = buf.readUtf();
        String currentItemName = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<String> available = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            available.add(buf.readUtf());
        }
        return new DeploySlotOptionsDto(slotIndex, slotName, currentItemName, available);
    }
}
