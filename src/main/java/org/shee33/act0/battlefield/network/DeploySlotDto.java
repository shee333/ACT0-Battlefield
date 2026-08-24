package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 配装里的一个槽位（部署预览 / 配装预览共用）：槽位索引 + 物品注册 ID + 虚拟弹药。
 *
 * <p>显示名由客户端解析：服务器拿不到 TaCZ 等资源的语言包，只下发稳定 ID。
 *
 * @param slotIndex 与 {@code LoadoutSlot.hotbarIndex()} 对应
 * @param itemId    物品注册 ID；空串 = 该槽位未配置
 * @param ammo      枪械槽虚拟弹药数；非枪械槽恒 0
 */
public record DeploySlotDto(int slotIndex, String itemId, int ammo) {

    public DeploySlotDto {
        itemId = itemId == null ? "" : itemId;
    }

    public boolean isEmpty() {
        return itemId.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(slotIndex);
        buf.writeUtf(itemId);
        buf.writeVarInt(ammo);
    }

    public static DeploySlotDto decode(FriendlyByteBuf buf) {
        return new DeploySlotDto(buf.readVarInt(), buf.readUtf(), buf.readVarInt());
    }
}
