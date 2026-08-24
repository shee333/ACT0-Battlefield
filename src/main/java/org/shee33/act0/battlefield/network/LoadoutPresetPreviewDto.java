package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装界面里的一套配装预览：稳定 id + 显示名 + 槽位（含弹药）+ 服装。
 *
 * <p>玩家只读不写——选择用 {@code LoadoutSelectPresetPacket}，内容全部由管理员在服务端配置。
 *
 * @param id           稳定标识（改名不影响它）
 * @param displayName  显示名
 * @param slots        配装的槽位（含虚拟弹药）
 * @param armor        服装四件套 itemId，按 头盔/胸甲/护腿/靴子 顺序；空串 = 该件未配置
 */
public record LoadoutPresetPreviewDto(String id, String displayName, List<DeploySlotDto> slots,
                                      List<String> armor) {

    private static final int MAX_SLOTS = 16;
    private static final int ARMOR_SLOTS = 4;

    public LoadoutPresetPreviewDto {
        armor = armor == null ? List.of() : List.copyOf(armor);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(displayName);
        buf.writeVarInt(slots.size());
        for (DeploySlotDto slot : slots) {
            slot.encode(buf);
        }
        buf.writeVarInt(armor.size());
        for (String a : armor) {
            buf.writeUtf(a == null ? "" : a);
        }
    }

    public static LoadoutPresetPreviewDto decode(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        String displayName = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_SLOTS));
        List<DeploySlotDto> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            slots.add(DeploySlotDto.decode(buf));
        }
        int armorCount = Math.max(0, Math.min(buf.readVarInt(), ARMOR_SLOTS));
        List<String> armor = new ArrayList<>(armorCount);
        for (int i = 0; i < armorCount; i++) {
            armor.add(buf.readUtf());
        }
        return new LoadoutPresetPreviewDto(id, displayName, slots, armor);
    }
}
