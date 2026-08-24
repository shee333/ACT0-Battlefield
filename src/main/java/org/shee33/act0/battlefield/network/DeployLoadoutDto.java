package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 部署界面展示的配装预览：玩家当前兵种所选的那套预设。
 *
 * @param classId     兵种 id
 * @param presetId    选中配装的稳定 id；空串 = 未选（该兵种无预设）
 * @param presetName  配装显示名
 * @param slots       配装的槽位（含虚拟弹药）；客户端自行解析显示名
 */
public record DeployLoadoutDto(String classId, String presetId, String presetName, List<DeploySlotDto> slots) {

    private static final int MAX_SLOTS = 16;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(classId);
        buf.writeUtf(presetId);
        buf.writeUtf(presetName);
        buf.writeVarInt(slots.size());
        for (DeploySlotDto slot : slots) {
            slot.encode(buf);
        }
    }

    public static DeployLoadoutDto decode(FriendlyByteBuf buf) {
        String classId = buf.readUtf();
        String presetId = buf.readUtf();
        String presetName = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_SLOTS));
        List<DeploySlotDto> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            slots.add(DeploySlotDto.decode(buf));
        }
        return new DeployLoadoutDto(classId, presetId, presetName, slots);
    }

    public static DeployLoadoutDto empty() {
        return new DeployLoadoutDto("", "", "", List.of());
    }
}
