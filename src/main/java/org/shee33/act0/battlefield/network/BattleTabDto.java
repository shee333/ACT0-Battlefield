package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义 TAB 战绩面板快照。
 *
 * <p>{@code alphaName}/{@code bravoName} 随包下发而非由客户端从枚举取：阵营名称是每张地图
 * 各自配置的，客户端无从得知自己正在打哪张图。
 */
public record BattleTabDto(int myFaction, int alphaTickets, int bravoTickets,
                           String alphaName, String bravoName,
                           List<TabEntryDto> alpha, List<TabEntryDto> bravo) {

    private static final int MAX_LIST_ENTRIES = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(myFaction);
        buf.writeVarInt(alphaTickets);
        buf.writeVarInt(bravoTickets);
        buf.writeUtf(alphaName);
        buf.writeUtf(bravoName);
        buf.writeVarInt(alpha.size());
        for (TabEntryDto e : alpha) {
            e.encode(buf);
        }
        buf.writeVarInt(bravo.size());
        for (TabEntryDto e : bravo) {
            e.encode(buf);
        }
    }

    public static BattleTabDto decode(FriendlyByteBuf buf) {
        int myFaction = buf.readVarInt();
        int alphaTickets = buf.readVarInt();
        int bravoTickets = buf.readVarInt();
        String alphaName = buf.readUtf();
        String bravoName = buf.readUtf();
        int an = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<TabEntryDto> alpha = new ArrayList<>(an);
        for (int i = 0; i < an; i++) {
            alpha.add(TabEntryDto.decode(buf));
        }
        int bn = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<TabEntryDto> bravo = new ArrayList<>(bn);
        for (int i = 0; i < bn; i++) {
            bravo.add(TabEntryDto.decode(buf));
        }
        return new BattleTabDto(myFaction, alphaTickets, bravoTickets, alphaName, bravoName, alpha, bravo);
    }
}
