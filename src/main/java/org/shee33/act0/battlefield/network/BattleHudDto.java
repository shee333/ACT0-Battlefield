package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

public record BattleHudDto(
        int myFaction, int alphaTickets, int bravoTickets, int maxTickets,
        List<ControlPointHudDto> points, List<SquadMateHudDto> squad,
        String focusName, int focusState, int focusProgress, int focusFaction,
        List<DownedMateDto> downedMates, String revivingName, int revivingProgress,
        int streak) {

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
        buf.writeVarInt(streak);
    }

    public static BattleHudDto decode(FriendlyByteBuf buf) {
        int mf = buf.readVarInt(), at = buf.readVarInt(), bt = buf.readVarInt(), mt = buf.readVarInt();
        int pn = buf.readVarInt();
        List<ControlPointHudDto> pts = new ArrayList<>(pn);
        for (int i = 0; i < pn; i++) pts.add(ControlPointHudDto.decode(buf));
        int sn = buf.readVarInt();
        List<SquadMateHudDto> sq = new ArrayList<>(sn);
        for (int i = 0; i < sn; i++) sq.add(SquadMateHudDto.decode(buf));
        String fn = buf.readUtf();
        int fs = buf.readVarInt(), fp = buf.readVarInt(), ff = buf.readVarInt();
        int dn = buf.readVarInt();
        List<DownedMateDto> dm = new ArrayList<>(dn);
        for (int i = 0; i < dn; i++) dm.add(DownedMateDto.decode(buf));
        String rn = buf.readUtf();
        int rp = buf.readVarInt(), sk = buf.readVarInt();
        return new BattleHudDto(mf, at, bt, mt, pts, sq, fn, fs, fp, ff, dm, rn, rp, sk);
    }
}