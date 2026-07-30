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
 *   <li>{@code points} — 当前扇区内的突破点列表（顺序即推进顺序）</li>
 *   <li>{@code squad} — 小队成员快照</li>
 *   <li>{@code phase} — 0=COUNTDOWN, 1=LIVE, 2=POST_MATCH</li>
 *   <li>{@code winner} — 0=none, 1=attackers win, 2=defenders win</li>
 * </ul>
 */
public record BreakthroughHudDto(
        boolean show,
        int attackerTickets, int maxTickets,
        int currentSectorId, int totalSectors,
        List<BreakthroughPointDto> points,
        List<SquadMateHudDto> squad,
        int phase,
        int winner) {

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
    }

    public static BreakthroughHudDto decode(FriendlyByteBuf buf) {
        boolean show = buf.readBoolean();
        if (!show) {
            return new BreakthroughHudDto(false, 0, 0, 0, 0, List.of(), List.of(), 0, 0);
        }
        int attackerTickets = buf.readVarInt();
        int maxTickets = buf.readVarInt();
        int currentSectorId = buf.readVarInt();
        int totalSectors = buf.readVarInt();
        List<BreakthroughPointDto> points = readList(buf, BreakthroughPointDto::decode);
        List<SquadMateHudDto> squad = readList(buf, SquadMateHudDto::decode);
        int phase = buf.readVarInt();
        int winner = buf.readVarInt();
        return new BreakthroughHudDto(true, attackerTickets, maxTickets,
                currentSectorId, totalSectors, points, squad, phase, winner);
    }

    private static <T> List<T> readList(FriendlyByteBuf buf, Function<FriendlyByteBuf, T> reader) {
        int size = buf.readVarInt();
        List<T> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(reader.apply(buf));
        }
        return list;
    }
}
