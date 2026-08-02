package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/** 自定义 TAB 战绩面板快照。 */
public record BattleTabDto(int myFaction, int alphaTickets, int bravoTickets,
                           List<TabEntryDto> alpha, List<TabEntryDto> bravo) {

    private static final int MAX_LIST_ENTRIES = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(myFaction);
        buf.writeVarInt(alphaTickets);
        buf.writeVarInt(bravoTickets);
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
        return new BattleTabDto(myFaction, alphaTickets, bravoTickets, alpha, bravo);
    }
}
