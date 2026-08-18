package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 对局结束后的战报快照。
 *
 * <p>{@code topCapturer}/{@code bestSquad} 等字段为 Conquest 专属的"最佳表现"维度；
 * Breakthrough 没有对应统计时传空字符串/0（详见 {@code BreakthroughMatch#buildResultFor}），
 * 渲染端（{@code BattleResultScreen}）按空值判断是否隐藏对应板块。
 *
 * <p>{@code matchSeconds}/{@code sectorsCaptured}/{@code totalSectors} 为通用/Breakthrough
 * 专属的扩展字段：{@code matchSeconds} 两种模式都可提供；{@code sectorsCaptured}/
 * {@code totalSectors} 仅 Breakthrough（扇区推进）有意义，Conquest 传 0/0，渲染端在
 * {@code totalSectors <= 0} 时隐藏该板块。
 */
public record BattleResultDto(int winnerFaction, int myFaction,
                              int alphaTickets, int bravoTickets,
                              String alphaName, String bravoName,
                              int myKills, int myDeaths,
                              List<TabEntryDto> leaderboard,
                              String topCapturer, int topCapturerTime,
                              String bestSquad, int bestSquadKills,
                              int matchSeconds, int sectorsCaptured, int totalSectors) {

    private static final int MAX_LIST_ENTRIES = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(winnerFaction);
        buf.writeVarInt(myFaction);
        buf.writeVarInt(alphaTickets);
        buf.writeVarInt(bravoTickets);
        buf.writeUtf(alphaName);
        buf.writeUtf(bravoName);
        buf.writeVarInt(myKills);
        buf.writeVarInt(myDeaths);
        buf.writeVarInt(leaderboard.size());
        for (TabEntryDto entry : leaderboard) { entry.encode(buf); }
        buf.writeUtf(topCapturer);
        buf.writeVarInt(topCapturerTime);
        buf.writeUtf(bestSquad);
        buf.writeVarInt(bestSquadKills);
        buf.writeVarInt(matchSeconds);
        buf.writeVarInt(sectorsCaptured);
        buf.writeVarInt(totalSectors);
    }

    public static BattleResultDto decode(FriendlyByteBuf buf) {
        int winner = buf.readVarInt();
        int mine = buf.readVarInt();
        int alpha = buf.readVarInt();
        int bravo = buf.readVarInt();
        String alphaName = buf.readUtf();
        String bravoName = buf.readUtf();
        int myKills = buf.readVarInt();
        int myDeaths = buf.readVarInt();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<TabEntryDto> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { entries.add(TabEntryDto.decode(buf)); }
        String tc = buf.readUtf();
        int tct = buf.readVarInt();
        String bs = buf.readUtf();
        int bsk = buf.readVarInt();
        int matchSeconds = buf.readVarInt();
        int sectorsCaptured = buf.readVarInt();
        int totalSectors = buf.readVarInt();
        return new BattleResultDto(winner, mine, alpha, bravo, alphaName, bravoName,
                myKills, myDeaths, entries, tc, tct, bs, bsk,
                matchSeconds, sectorsCaptured, totalSectors);
    }
}