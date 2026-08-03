package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public record BattleHudDto(
        int myFaction, int alphaTickets, int bravoTickets, int maxTickets,
        List<ControlPointHudDto> points, List<SquadMateHudDto> squad,
        String focusName, int focusState, int focusProgress, int focusFaction,
        List<DownedMateDto> downedMates, String revivingName, int revivingProgress,
        String beingRevivedByName, int beingRevivedProgress,
        boolean isSquadLeader, int streak,
        int squadOrderPointId, boolean squadOrderAttack) {

    private static final int MAX_LIST_ENTRIES = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(myFaction);
        buf.writeVarInt(alphaTickets);
        buf.writeVarInt(bravoTickets);
        buf.writeVarInt(maxTickets);
        buf.writeVarInt(points.size());
        for (ControlPointHudDto p : points) { p.encode(buf); }
        buf.writeVarInt(squad.size());
        for (SquadMateHudDto s : squad) { s.encode(buf); }
        buf.writeUtf(focusName);
        buf.writeVarInt(focusState);
        buf.writeVarInt(focusProgress);
        buf.writeVarInt(focusFaction);
        buf.writeVarInt(downedMates.size());
        for (DownedMateDto d : downedMates) { d.encode(buf); }
        buf.writeUtf(revivingName);
        buf.writeVarInt(revivingProgress);
        buf.writeUtf(beingRevivedByName);
        buf.writeVarInt(beingRevivedProgress);
        buf.writeBoolean(isSquadLeader);
        buf.writeVarInt(streak);
        buf.writeVarInt(squadOrderPointId);
        buf.writeBoolean(squadOrderAttack);
    }

    public static BattleHudDto decode(FriendlyByteBuf buf) {
        int mf = buf.readVarInt(), at = buf.readVarInt(), bt = buf.readVarInt(), mt = buf.readVarInt();
        int pn = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<ControlPointHudDto> pts = new ArrayList<>(pn);
        for (int i = 0; i < pn; i++) pts.add(ControlPointHudDto.decode(buf));
        int sn = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<SquadMateHudDto> sq = new ArrayList<>(sn);
        for (int i = 0; i < sn; i++) sq.add(SquadMateHudDto.decode(buf));
        String fn = buf.readUtf();
        int fs = buf.readVarInt(), fp = buf.readVarInt(), ff = buf.readVarInt();
        int dn = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<DownedMateDto> dm = new ArrayList<>(dn);
        for (int i = 0; i < dn; i++) dm.add(DownedMateDto.decode(buf));
        String rn = buf.readUtf();
        int rp = buf.readVarInt();
        String brn = buf.readUtf();
        int brp = buf.readVarInt();
        boolean sl = buf.readBoolean();
        int sk = buf.readVarInt();
        int sop = buf.readVarInt();
        boolean soa = buf.readBoolean();
        return new BattleHudDto(mf, at, bt, mt, pts, sq, fn, fs, fp, ff, dm, rn, rp, brn, brp, sl, sk, sop, soa);
    }
}