package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装界面里<b>一个兵种</b>在某张地图上的全部数据：激活的配装序号、4 套具名配装的槽位选择，
 * 以及整套槽位的可选项（目录配置，与具体套数无关）。
 *
 * <p>{@code slotOptions} 复用 {@link DeploySlotOptionsDto}：槽位名 + 当前选中 + 全部可选项。
 * 其中 {@code currentItemName} 取该兵种<b>激活套</b>对应槽位的生效选择，配装网格下方
 * 的槽位编辑器直接照常绘制即可。
 *
 * @param classId      {@code SoldierClass.id()}
 * @param activeIndex  该兵种当前激活的配装序号 [0, 4)
 * @param presets      4 套具名配装（名字 + 各槽位选择）
 * @param slotOptions  全部槽位的目录可选项（含激活套当前选中）
 */
public record ClassLoadoutDto(String classId, int activeIndex, List<LoadoutPresetDto> presets,
                              List<DeploySlotOptionsDto> slotOptions) {

    private static final int MAX_PRESETS = 8;
    private static final int MAX_SLOTS = 16;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(classId);
        buf.writeVarInt(activeIndex);
        buf.writeVarInt(presets.size());
        for (LoadoutPresetDto preset : presets) {
            preset.encode(buf);
        }
        buf.writeVarInt(slotOptions.size());
        for (DeploySlotOptionsDto slot : slotOptions) {
            slot.encode(buf);
        }
    }

    public static ClassLoadoutDto decode(FriendlyByteBuf buf) {
        String classId = buf.readUtf();
        int activeIndex = buf.readVarInt();
        int presetCount = Math.max(0, Math.min(buf.readVarInt(), MAX_PRESETS));
        List<LoadoutPresetDto> presets = new ArrayList<>(presetCount);
        for (int i = 0; i < presetCount; i++) {
            presets.add(LoadoutPresetDto.decode(buf));
        }
        int slotCount = Math.max(0, Math.min(buf.readVarInt(), MAX_SLOTS));
        List<DeploySlotOptionsDto> slots = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            slots.add(DeploySlotOptionsDto.decode(buf));
        }
        return new ClassLoadoutDto(classId, activeIndex, presets, slots);
    }
}
