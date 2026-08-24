package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装界面里一个兵种的可选配装：全部预设 + 玩家当前选中的那个。
 *
 * @param classId          兵种 id
 * @param selectedPresetId 玩家为该兵种选中的配装 id；空串 = 未选
 * @param presets          该兵种下的全部预设（按创建顺序）
 */
public record ClassPresetsDto(String classId, String selectedPresetId, List<LoadoutPresetPreviewDto> presets) {

    private static final int MAX_PRESETS = 64;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(classId);
        buf.writeUtf(selectedPresetId);
        buf.writeVarInt(presets.size());
        for (LoadoutPresetPreviewDto p : presets) {
            p.encode(buf);
        }
    }

    public static ClassPresetsDto decode(FriendlyByteBuf buf) {
        String classId = buf.readUtf();
        String selectedPresetId = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_PRESETS));
        List<LoadoutPresetPreviewDto> presets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            presets.add(LoadoutPresetPreviewDto.decode(buf));
        }
        return new ClassPresetsDto(classId, selectedPresetId, presets);
    }
}
