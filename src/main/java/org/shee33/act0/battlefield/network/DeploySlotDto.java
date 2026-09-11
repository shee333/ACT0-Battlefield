package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 配装里的一个槽位（部署预览 / 配装预览共用）：槽位索引 + 物品注册 ID + 虚拟弹药 + 显示键。
 *
 * <p>{@code displayKey} 是服务端上架时抓取的物品描述键（如 {@code item.lrtactical.dagger}），
 * 客户端用它本地化显示。某些模组物品（近战/道具）的名字由 NBT 决定，裸注册 ID 只能解析出
 * 通用 key（如 {@code item.lrtactical.melee}，该 key 无翻译），故必须随快照下发。
 *
 * @param slotIndex  与 {@code LoadoutSlot.hotbarIndex()} 对应
 * @param itemId     物品注册 ID；空串 = 该槽位未配置
 * @param ammo       枪械槽虚拟弹药数；非枪械槽恒 0
 * @param displayKey 物品描述键（可为空串，客户端回退到按 itemId 解析）
 */
public record DeploySlotDto(int slotIndex, String itemId, int ammo, String displayKey) {

    public DeploySlotDto {
        itemId = itemId == null ? "" : itemId;
        displayKey = displayKey == null ? "" : displayKey;
    }

    public boolean isEmpty() {
        return itemId.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(slotIndex);
        buf.writeUtf(itemId);
        buf.writeVarInt(ammo);
        buf.writeUtf(displayKey);
    }

    public static DeploySlotDto decode(FriendlyByteBuf buf) {
        return new DeploySlotDto(buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readUtf());
    }
}