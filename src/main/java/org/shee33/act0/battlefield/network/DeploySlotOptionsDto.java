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
 *
 * <p>{@code currentItemName} 与 {@link DeployOptionDto#id} 一样是注册 ID，不是显示名——
 * 换装校验按 ID 走，显示名只用于绘制，两者不可互换。
 */
public record DeploySlotOptionsDto(int slotIndex, String slotName, String currentItemName,
                                    List<DeployOptionDto> options) {

    private static final int MAX_LIST_ENTRIES = 128;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(slotIndex);
        buf.writeUtf(slotName);
        buf.writeUtf(currentItemName);
        buf.writeVarInt(options.size());
        for (DeployOptionDto option : options) {
            option.encode(buf);
        }
    }

    public static DeploySlotOptionsDto decode(FriendlyByteBuf buf) {
        int slotIndex = buf.readVarInt();
        String slotName = buf.readUtf();
        String currentItemName = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<DeployOptionDto> options = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            options.add(DeployOptionDto.decode(buf));
        }
        return new DeploySlotOptionsDto(slotIndex, slotName, currentItemName, options);
    }

    /** 本槽位全部可选项的注册 ID，供换装合法性校验使用。 */
    public List<String> availableItemNames() {
        List<String> ids = new ArrayList<>(options.size());
        for (DeployOptionDto option : options) {
            ids.add(option.id());
        }
        return ids;
    }

    /** 某个注册 ID 对应的显示名；不在本槽位可选项里时退回 ID 本身。 */
    public String displayNameOf(String id) {
        for (DeployOptionDto option : options) {
            if (option.id().equals(id)) {
                return option.displayName();
            }
        }
        return DeployOptionDto.fallbackName(id);
    }

    /** 当前选中项的显示名。 */
    public String currentDisplayName() {
        return displayNameOf(currentItemName);
    }
}
