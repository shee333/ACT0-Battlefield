package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 一套配装里某个槽位的选择：快捷栏索引 + 注册 ID。
 *
 * @param slotIndex 与 {@code LoadoutSlot.hotbarIndex()} 对应
 * @param itemId    地图目录里的注册 ID；null 表示该槽位未选
 */
public record LoadoutSlotPickDto(int slotIndex, @javax.annotation.Nullable String itemId) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(slotIndex);
        buf.writeBoolean(itemId != null);
        if (itemId != null) {
            buf.writeUtf(itemId);
        }
    }

    public static LoadoutSlotPickDto decode(FriendlyByteBuf buf) {
        int slotIndex = buf.readVarInt();
        String itemId = buf.readBoolean() ? buf.readUtf() : null;
        return new LoadoutSlotPickDto(slotIndex, itemId);
    }
}
