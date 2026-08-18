package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装界面的一屏数据：第一级标签的全部地图名，加上<b>当前这一张</b>地图下四个兵种的完整槽位。
 *
 * <p><b>为什么只带一张图而不是全部图</b>：单张图上限是 5 槽 × 64 可选项 × 4 兵种，一次性
 * 把所有地图都塞进一个包里会随地图数线性膨胀，很容易撞上包体上限。切换第一级标签时重新
 * 向服务端请求（{@link RequestLoadoutConfigPacket}）代价很低，而且顺带保证了目录改动能立刻反映。
 *
 * @param mapNames        全部已知地图名，用于第一级标签
 * @param mapName         本包对应的地图；服务端解析后的规范名，客户端原样回传
 * @param selectedClassId 玩家在这张图上当前选中的兵种，用于二级标签默认选中项
 */
public record LoadoutConfigDto(List<String> mapNames, String mapName, String selectedClassId,
                               List<ClassLoadoutDto> classes) {

    private static final int MAX_MAPS = 256;
    private static final int MAX_CLASSES = 16;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(mapNames.size());
        for (String name : mapNames) {
            buf.writeUtf(name);
        }
        buf.writeUtf(mapName);
        buf.writeUtf(selectedClassId);
        buf.writeVarInt(classes.size());
        for (ClassLoadoutDto c : classes) {
            c.encode(buf);
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
        int classCount = Math.max(0, Math.min(buf.readVarInt(), MAX_CLASSES));
        List<ClassLoadoutDto> classes = new ArrayList<>(classCount);
        for (int i = 0; i < classCount; i++) {
            classes.add(ClassLoadoutDto.decode(buf));
        }
        return new LoadoutConfigDto(mapNames, mapName, selectedClassId, classes);
    }

    public static LoadoutConfigDto empty() {
        return new LoadoutConfigDto(List.of(), "", "", List.of());
    }

    /** 没有任何已命名地图时为空——此时配装界面只能提示管理员先建图。 */
    public boolean isEmpty() {
        return mapNames.isEmpty();
    }
}
