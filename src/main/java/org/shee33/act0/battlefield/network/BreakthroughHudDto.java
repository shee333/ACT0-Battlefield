package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * S→C：突破模式 HUD 快照。{@code show=false} 表示隐藏 HUD，其余字段为占位零值。
 *
 * <ul>
 *   <li>{@code attackerTickets} / {@code maxTickets} — 进攻方总票数（与扇区压制速率挂钩）</li>
 *   <li>{@code currentSectorId} / {@code totalSectors} — 当前激活扇区索引与扇区总数</li>
 *   <li>{@code points} — 全部突破点列表（含所有扇区，{@link BreakthroughPointDto#sectorIndex()}
 *       标注归属，客户端据此筛出"当前区域行"，见占点 HUD 动效规格文档 §3.1）</li>
 *   <li>{@code squad} — 小队成员快照</li>
 *   <li>{@code phase} — 0=COUNTDOWN, 1=LIVE, 2=POST_MATCH</li>
 *   <li>{@code winner} — 0=none, 1=attackers win, 2=defenders win</li>
 *   <li>{@code focusName} / {@code focusState} / {@code focusProgress} — 本地玩家当前站立的目标点
 *       "特写"驱动字段，对应 {@code BattleHudDto} 的 {@code focusName}/{@code focusState}/
 *       {@code focusProgress}（规格文档 §1.3.2 的 FLIP 下拉特写）。突破模式无阵营相对性
 *       （颜色语义是绝对的：未被 ALPHA 占领即视为"敌方红"），因此不需要 {@code focusFaction}。
 *       {@code focusState}：0=未站在任何目标点内；1=正在占领（单圈制,无两轮）；
 *       2=已被 ALPHA 占领（己方满控）；3=争夺中（双方同时在场，"遭到反击"）。
 *       {@code focusProgress}：0~100，始终是"朝 ALPHA 占领"方向的绝对进度（与查看者阵营无关）。
 * </ul>
 */
public record BreakthroughHudDto(
        boolean show,
        int attackerTickets, int maxTickets,
        int currentSectorId, int totalSectors,
        List<BreakthroughPointDto> points,
        List<SquadMateHudDto> squad,
        int phase,
        int winner,
        String focusName, int focusState, int focusProgress) {

    private static final int MAX_LIST_ENTRIES = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(show);
        buf.writeVarInt(attackerTickets);
        buf.writeVarInt(maxTickets);
        buf.writeVarInt(currentSectorId);
        buf.writeVarInt(totalSectors);
        buf.writeVarInt(points.size());
        for (BreakthroughPointDto p : points) {
            p.encode(buf);
        }
        buf.writeVarInt(squad.size());
        for (SquadMateHudDto s : squad) {
            s.encode(buf);
        }
        buf.writeVarInt(phase);
        buf.writeVarInt(winner);
        buf.writeUtf(focusName);
        buf.writeVarInt(focusState);
        buf.writeVarInt(focusProgress);
    }

    public static BreakthroughHudDto decode(FriendlyByteBuf buf) {
        boolean show = buf.readBoolean();
        if (!show) {
            return new BreakthroughHudDto(false, 0, 0, 0, 0, List.of(), List.of(), 0, 0, "", 0, 0);
        }
        int attackerTickets = buf.readVarInt();
        int maxTickets = buf.readVarInt();
        int currentSectorId = buf.readVarInt();
        int totalSectors = buf.readVarInt();
        List<BreakthroughPointDto> points = readList(buf, BreakthroughPointDto::decode);
        List<SquadMateHudDto> squad = readList(buf, SquadMateHudDto::decode);
        int phase = buf.readVarInt();
        int winner = buf.readVarInt();
        String focusName = buf.readUtf();
        int focusState = buf.readVarInt();
        int focusProgress = buf.readVarInt();
        return new BreakthroughHudDto(true, attackerTickets, maxTickets,
                currentSectorId, totalSectors, points, squad, phase, winner,
                focusName, focusState, focusProgress);
    }

    private static <T> List<T> readList(FriendlyByteBuf buf, Function<FriendlyByteBuf, T> reader) {
        int size = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(reader.apply(buf));
        }
        return list;
    }
}
