package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装网格里的一格：配装名 + 该套各槽位的当前选择（已按目录解析为生效值）。
 *
 * <p>{@code name} 为空串表示未命名，客户端显示默认名「配装 N」。
 *
 * @param name  玩家自定义名；空串 = 未命名
 * @param picks 各槽位的生效选择；槽位缺席表示该槽位未配置/未选
 */
public record LoadoutPresetDto(String name, List<LoadoutSlotPickDto> picks) {

    private static final int MAX_PICKS = 16;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeVarInt(picks.size());
        for (LoadoutSlotPickDto pick : picks) {
            pick.encode(buf);
        }
    }

    public static LoadoutPresetDto decode(FriendlyByteBuf buf) {
        String name = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_PICKS));
        List<LoadoutSlotPickDto> picks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            picks.add(LoadoutSlotPickDto.decode(buf));
        }
        return new LoadoutPresetDto(name, picks);
    }
}
