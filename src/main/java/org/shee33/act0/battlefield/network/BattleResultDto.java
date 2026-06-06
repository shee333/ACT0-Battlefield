package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 对局结束后的战报快照。 */
public record BattleResultDto(int winnerFaction, int myFaction,
                              int alphaTickets, int bravoTickets,
                              int myKills, int myDeaths,
                              List<TabEntryDto> leaderboard) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(winnerFaction);
        buf.writeVarInt(myFaction);
        buf.writeVarInt(alphaTickets);
        buf.writeVarInt(bravoTickets);
        buf.writeVarInt(myKills);
        buf.writeVarInt(myDeaths);
        buf.writeVarInt(leaderboard.size());
        for (TabEntryDto entry : leaderboard) {
            entry.encode(buf);
        }
    }

    public static BattleResultDto decode(FriendlyByteBuf buf) {
        int winner = buf.readVarInt();
        int mine = buf.readVarInt();
        int alpha = buf.readVarInt();
        int bravo = buf.readVarInt();
        int myKills = buf.readVarInt();
        int myDeaths = buf.readVarInt();
        int n = buf.readVarInt();
        List<TabEntryDto> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            entries.add(TabEntryDto.decode(buf));
        }
        return new BattleResultDto(winner, mine, alpha, bravo, myKills, myDeaths, entries);
    }
}
