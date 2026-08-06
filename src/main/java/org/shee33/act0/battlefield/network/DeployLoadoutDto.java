package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 部署界面显示的配装数据快照。从服务器反射读取 Arcade 配装后发送给客户端。
 *
 * <p>每个槽位不再只是"当前选中项"的纯文本快照，还携带该槽位对玩家当前职业已解锁的全部可选项
 * （见 {@link DeploySlotOptionsDto}），供底部武器更换面板（《部署界面动效规格文档》3.6 节）
 * 展示可选列表，并供本次对局会话覆盖（{@code RedeployService#handleSlotOverride}）校验。
 */
public record DeployLoadoutDto(String className, List<DeploySlotOptionsDto> slots) {

    private static final int MAX_LIST_ENTRIES = 64;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(className);
        buf.writeVarInt(slots.size());
        for (DeploySlotOptionsDto slot : slots) {
            slot.encode(buf);
        }
    }

    public static DeployLoadoutDto decode(FriendlyByteBuf buf) {
        String cn = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<DeploySlotOptionsDto> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            slots.add(DeploySlotOptionsDto.decode(buf));
        }
        return new DeployLoadoutDto(cn, slots);
    }

    public static DeployLoadoutDto empty() {
        return new DeployLoadoutDto("", List.of());
    }

    /**
     * 纯校验：提交的槽位序号+物品名组合是否为本快照下的合法可选项。
     *
     * <p>用于防止伪造的 {@link DeploySlotOverridePacket} 塞入未解锁/不存在的物品——不依赖
     * {@code ServerPlayer}，可直接单测。
     */
    public boolean isValidOverride(int slotIndex, String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return false;
        }
        for (DeploySlotOptionsDto slot : slots) {
            if (slot.slotIndex() == slotIndex) {
                return slot.availableItemNames().contains(itemName);
            }
        }
        return false;
    }

    /**
     * 把本次对局会话覆盖（槽位序号 → 覆盖后物品名）叠加到各槽位的 {@code currentItemName} 上，
     * 让客户端 UI 立即反映覆盖后的选择（而不是 Arcade 里持久化保存的原始选择）。
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
                        slot.availableItemNames()));
                changed = true;
            } else {
                merged.add(slot);
            }
        }
        return changed ? new DeployLoadoutDto(className, merged) : this;
    }
}
