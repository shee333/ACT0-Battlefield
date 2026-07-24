package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * BF 风格大战场 HUD 快照：顶部票数条 + 据点进度 + 左下小队成员 + 当前所在据点进度 + 倒地队友 + 救援信息。
 *
 * @param focusState 当前所在据点状态：0=无，1=正在占领，2=正在防守，3=争夺中
 */
public record BattleHudDto(
        int myFaction,
        int alphaTickets,
        int bravoTickets,
        int maxTickets,
        List<ControlPointHudDto> points,
        List<SquadMateHudDto> squad,
        String focusName,
        int focusState,
        int focusProgress,
        int focusFaction,
        List<DownedMateDto> downedMates,
        String revivingName,
        int revivingProgress) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(myFaction);
        buf.writeVarInt(alphaTickets);
        buf.writeVarInt(bravoTickets);
        buf.writeVarInt(maxTickets);
        buf.writeVarInt(points.size());
        for (ControlPointHudDto p : points) {
            p.encode(buf);
        }
        buf.writeVarInt(squad.size());
        for (SquadMateHudDto s : squad) {
            s.encode(buf);
        }
        buf.writeUtf(focusName);
        buf.writeVarInt(focusState);
        buf.writeVarInt(focusProgress);
        buf.writeVarInt(focusFaction);
        buf.writeVarInt(downedMates.size());
        for (DownedMateDto d : downedMates) {
            d.encode(buf);
        }
        buf.writeUtf(revivingName);
        buf.writeVarInt(revivingProgress);
    }

    public static BattleHudDto decode(FriendlyByteBuf buf) {
        int myFaction = buf.readVarInt();
        int alphaTickets = buf.readVarInt();
        int bravoTickets = buf.readVarInt();
        int maxTickets = buf.readVarInt();
        int pn = buf.readVarInt();
        List<ControlPointHudDto> points = new ArrayList<>(pn);
        for (int i = 0; i < pn; i++) {
            points.add(ControlPointHudDto.decode(buf));
        }
        int sn = buf.readVarInt();
        List<SquadMateHudDto> squad = new ArrayList<>(sn);
        for (int i = 0; i < sn; i++) {
            squad.add(SquadMateHudDto.decode(buf));
        }
        String focusName = buf.readUtf();
        int focusState = buf.readVarInt();
        int focusProgress = buf.readVarInt();
        int focusFaction = buf.readVarInt();
        int dn = buf.readVarInt();
        List<DownedMateDto> downedMates = new ArrayList<>(dn);
        for (int i = 0; i < dn; i++) {
            downedMates.add(DownedMateDto.decode(buf));
        }
        String revivingName = buf.readUtf();
        int revivingProgress = buf.readVarInt();
        return new BattleHudDto(myFaction, alphaTickets, bravoTickets, maxTickets, points, squad,
                focusName, focusState, focusProgress, focusFaction, downedMates, revivingName, revivingProgress);
    }
}