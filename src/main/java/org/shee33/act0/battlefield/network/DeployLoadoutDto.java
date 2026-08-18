package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import org.shee33.act0.battlefield.core.SoldierClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 部署界面显示的配装数据快照。服务端按地图目录 + 玩家存档解析后发送给客户端。
 *
 * <p>每个槽位不只是"当前选中项"的快照，还携带该槽位在本图目录里的全部可选项（见
 * {@link DeploySlotOptionsDto}），供底部武器更换面板（《部署界面动效规格文档》3.6 节）展示，
 * 并供换装提交（{@code RedeployService#handleSlotOverride}）校验。
 */
public record DeployLoadoutDto(String selectedClassId, List<DeploySlotOptionsDto> slots) {

    private static final int MAX_LIST_ENTRIES = 64;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(selectedClassId);
        buf.writeVarInt(slots.size());
        for (DeploySlotOptionsDto slot : slots) {
            slot.encode(buf);
        }
    }

    public static DeployLoadoutDto decode(FriendlyByteBuf buf) {
        String selectedClassId = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<DeploySlotOptionsDto> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            slots.add(DeploySlotOptionsDto.decode(buf));
        }
        return new DeployLoadoutDto(selectedClassId, slots);
    }

    public static DeployLoadoutDto empty() {
        return new DeployLoadoutDto(SoldierClass.DEFAULT.id(), List.of());
    }

    /**
     * 把一组选择（槽位序号 → 物品名）叠加到各槽位的 {@code currentItemName} 上，
     * 让客户端在服务端回包到达前就先乐观反映这次点击。
     *
     * <p>纯函数：只读取已知合法的覆盖并生成新快照，非法/未知槽位的覆盖会被忽略。
     */
    public DeployLoadoutDto withOverrides(Map<Integer, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return this;
        }
        List<DeploySlotOptionsDto> merged = new ArrayList<>(slots.size());
        boolean changed = false;
        for (DeploySlotOptionsDto slot : slots) {
            String override = overrides.get(slot.slotIndex());
            if (override != null && !override.equals(slot.currentItemName())
                    && slot.availableItemNames().contains(override)) {
                merged.add(new DeploySlotOptionsDto(slot.slotIndex(), slot.slotName(), override,
                        slot.options()));
                changed = true;
            } else {
                merged.add(slot);
            }
        }
        return changed ? new DeployLoadoutDto(selectedClassId, merged) : this;
    }
}
