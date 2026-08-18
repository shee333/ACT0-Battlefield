package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装界面里<b>一个兵种</b>在某张地图上的全部槽位快照。
 *
 * <p>复用 {@link DeploySlotOptionsDto}：配装界面要展示的东西与部署界面底部换装面板完全一致
 * （槽位名 + 当前选中 + 全部可选项），没有理由再造一个结构。
 *
 * @param classId {@code SoldierClass.id()}；显示名与能力说明由客户端查枚举得到，不走线传
 */
public record ClassLoadoutDto(String classId, List<DeploySlotOptionsDto> slots) {

    private static final int MAX_SLOTS = 16;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(classId);
        buf.writeVarInt(slots.size());
        for (DeploySlotOptionsDto slot : slots) {
            slot.encode(buf);
        }
    }

    public static ClassLoadoutDto decode(FriendlyByteBuf buf) {
        String classId = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_SLOTS));
        List<DeploySlotOptionsDto> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            slots.add(DeploySlotOptionsDto.decode(buf));
        }
        return new ClassLoadoutDto(classId, slots);
    }
}
