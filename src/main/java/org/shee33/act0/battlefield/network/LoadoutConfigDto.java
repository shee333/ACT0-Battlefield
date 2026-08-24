package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装界面的一屏数据：地图标签、当前选中（阵营/兵种），以及每个阵营的配装组。
 *
 * @param mapNames        全部已知地图名（一级标签）
 * @param mapName         本包对应的地图
 * @param selectedClassId 玩家在这张图上的当前兵种（二级标签默认选中）
 * @param factionId       玩家当前查看的阵营（阵营标签默认选中）
 * @param factions        两个阵营的配装组
 */
public record LoadoutConfigDto(List<String> mapNames, String mapName, String selectedClassId,
                               String factionId, List<FactionPresetsDto> factions) {

    private static final int MAX_MAPS = 256;
    private static final int MAX_FACTIONS = 8;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(mapNames.size());
        for (String name : mapNames) {
            buf.writeUtf(name);
        }
        buf.writeUtf(mapName);
        buf.writeUtf(selectedClassId);
        buf.writeUtf(factionId);
        buf.writeVarInt(factions.size());
        for (FactionPresetsDto f : factions) {
            f.encode(buf);
        }
    }

    public static LoadoutConfigDto decode(FriendlyByteBuf buf) {
        int mapCount = Math.max(0, Math.min(buf.readVarInt(), MAX_MAPS));
        List<String> mapNames = new ArrayList<>(mapCount);
        for (int i = 0; i < mapCount; i++) {
            mapNames.add(buf.readUtf());
        }
        String mapName = buf.readUtf();
        String selectedClassId = buf.readUtf();
        String factionId = buf.readUtf();
        int factionCount = Math.max(0, Math.min(buf.readVarInt(), MAX_FACTIONS));
        List<FactionPresetsDto> factions = new ArrayList<>(factionCount);
        for (int i = 0; i < factionCount; i++) {
            factions.add(FactionPresetsDto.decode(buf));
        }
        return new LoadoutConfigDto(mapNames, mapName, selectedClassId, factionId, factions);
    }

    public static LoadoutConfigDto empty() {
        return new LoadoutConfigDto(List.of(), "", "", "", List.of());
    }

    /** 没有任何已命名地图时为空——此时配装界面只能提示管理员先建图。 */
    public boolean isEmpty() {
        return mapNames.isEmpty();
    }
}
