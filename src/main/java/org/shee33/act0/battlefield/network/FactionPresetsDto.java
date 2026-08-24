package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装界面里一个阵营的四个兵种配装组。
 *
 * @param factionId 阵营 id（{@code ALPHA}/{@code BRAVO}）
 * @param classes   该阵营下四个兵种的配装组
 */
public record FactionPresetsDto(String factionId, List<ClassPresetsDto> classes) {

    private static final int MAX_CLASSES = 16;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(factionId);
        buf.writeVarInt(classes.size());
        for (ClassPresetsDto c : classes) {
            c.encode(buf);
        }
    }

    public static FactionPresetsDto decode(FriendlyByteBuf buf) {
        String factionId = buf.readUtf();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_CLASSES));
        List<ClassPresetsDto> classes = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            classes.add(ClassPresetsDto.decode(buf));
        }
        return new FactionPresetsDto(factionId, classes);
    }
}
