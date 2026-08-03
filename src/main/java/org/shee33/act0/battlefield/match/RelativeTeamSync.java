package org.shee33.act0.battlefield.match;

import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 按观察者视角同步虚拟队伍：该观察者看到的友方永远蓝色，倒地中的队友永远橙色，敌方永远红色。
 *
 * <p>倒地队友使用 {@link ChatFormatting#GOLD}（0xFFAA00）：原版 ChatFormatting 没有真正的
 * "橙色"常量，GOLD 是最接近的近似，且 {@link PlayerTeam#setColor(ChatFormatting)} 只接受该枚举，
 * 无法直接指定自定义 hex 颜色。
 *
 * <p>使用客户端私有虚拟队伍包，避免全局 scoreboard 污染。
 * 首次调用发送 ADD 创建队伍并同步全部成员；后续调用仅发送增量差异——新增成员 ADD，
 * 移除成员 REMOVE，关系发生变化（如友方倒地/被救起）时先从旧队伍 REMOVE 再 ADD 进新队伍。
 */
public final class RelativeTeamSync {

    /** 观察者视角下目标玩家与自己的关系：友方 / 倒地中的友方（橙色高亮） / 敌方。 */
    public enum Relation { FRIENDLY, FRIENDLY_DOWNED, ENEMY }

    private static final Set<String> initializedViewers = new HashSet<>();
    /** viewer UUID → (target UUID → 当前已同步的关系)，用于增量 diff 与关系变更检测 */
    private static final Map<UUID, Map<UUID, Relation>> lastKnownMembers = new HashMap<>();

    private RelativeTeamSync() {}

    /**
     * 为一个观察者同步队伍成员显示。
     *
     * <p>首次调用：发送三个虚拟队伍创建包，并对所有玩家按关系发送 ADD。
     * 后续调用：比较当前关系与上次已同步关系，仅对新增/移除/变更的成员发送数据包，
     * 未变更成员跳过。
     *
     * @param viewer            观察者玩家
     * @param players           当前所有在编玩家 UUID 集合（通常是 factionOf.keySet()）
     * @param playerResolver    UUID → ServerPlayer 解析器
     * @param relationResolver  UUID → 与观察者的关系判定器（FRIENDLY/FRIENDLY_DOWNED/ENEMY）
     */
    public static void sync(ServerPlayer viewer, Collection<UUID> players,
                            Function<UUID, ServerPlayer> playerResolver,
                            Function<UUID, Relation> relationResolver) {
        if (viewer == null || players == null) return;
        UUID vId = viewer.getUUID();
        String vKey = vId.toString();
        boolean firstTime = initializedViewers.add(vKey);

        String fName = teamName(viewer, 'F');
        String dName = teamName(viewer, 'D');
        String eName = teamName(viewer, 'E');

        Scoreboard board = new Scoreboard();
        PlayerTeam friendly = board.addPlayerTeam(fName);
        friendly.setColor(ChatFormatting.BLUE);
        friendly.setNameTagVisibility(Team.Visibility.ALWAYS);
        PlayerTeam friendlyDowned = board.addPlayerTeam(dName);
        friendlyDowned.setColor(ChatFormatting.GOLD);
        friendlyDowned.setNameTagVisibility(Team.Visibility.ALWAYS);
        PlayerTeam enemy = board.addPlayerTeam(eName);
        enemy.setColor(ChatFormatting.RED);
        enemy.setNameTagVisibility(Team.Visibility.NEVER);

        if (firstTime) {
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(friendly, true));
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(friendlyDowned, true));
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(enemy, true));
        }

        Map<UUID, Relation> lastKnown = lastKnownMembers.get(vId);
        Map<UUID, Relation> nextKnown = new HashMap<>();

        for (UUID id : players) {
            ServerPlayer target = playerResolver.apply(id);
            if (target == null) continue;
            Relation relation = relationResolver.apply(id);
            if (relation == null) continue;
            nextKnown.put(id, relation);

            Relation previous = lastKnown == null ? null : lastKnown.get(id);
            if (previous == relation) {
                continue; // 关系未变化，跳过
            }
            if (previous != null) {
                // 关系变更（如友方倒地/被救起）：先从旧队伍移除，避免同一玩家残留在两个队伍中
                viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        teamFor(previous, friendly, friendlyDowned, enemy), target.getScoreboardName(),
                        ClientboundSetPlayerTeamPacket.Action.REMOVE));
            }
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                    teamFor(relation, friendly, friendlyDowned, enemy), target.getScoreboardName(),
                    ClientboundSetPlayerTeamPacket.Action.ADD));
        }

        if (lastKnown != null) {
            for (Map.Entry<UUID, Relation> e : lastKnown.entrySet()) {
                UUID id = e.getKey();
                if (nextKnown.containsKey(id)) continue;
                // 已移除成员 → REMOVE
                ServerPlayer target = playerResolver.apply(id);
                if (target != null) {
                    viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                            teamFor(e.getValue(), friendly, friendlyDowned, enemy), target.getScoreboardName(),
                            ClientboundSetPlayerTeamPacket.Action.REMOVE));
                }
            }
        }

        // 保存本次同步后的状态，供下次 diff 使用
        lastKnownMembers.put(vId, nextKnown);
    }

    private static PlayerTeam teamFor(Relation relation, PlayerTeam friendly, PlayerTeam friendlyDowned, PlayerTeam enemy) {
        return switch (relation) {
            case FRIENDLY -> friendly;
            case FRIENDLY_DOWNED -> friendlyDowned;
            case ENEMY -> enemy;
        };
    }

    /**
     * 清理一个观察者的全部虚拟队伍状态。
     * 发送 REMOVE 销毁队伍实体，并从追踪中移除。
     */
    public static void clear(ServerPlayer viewer) {
        if (viewer == null) return;
        UUID vId = viewer.getUUID();
        initializedViewers.remove(vId.toString());
        lastKnownMembers.remove(vId);
        Scoreboard board = new Scoreboard();
        PlayerTeam f = board.addPlayerTeam(teamName(viewer, 'F'));
        PlayerTeam d = board.addPlayerTeam(teamName(viewer, 'D'));
        PlayerTeam e = board.addPlayerTeam(teamName(viewer, 'E'));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(f));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(d));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(e));
    }

    /**
     * 从指定观察者的视角中移除一个目标玩家。
     * 同时更新追踪状态，使下次增量同步将该目标视为"新增"并重新 ADD
     * （匹配原版"removeTarget 后下一次完整 sync 会重新加入"的行为）。
     */
    public static void removeTarget(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null) return;
        UUID vId = viewer.getUUID();
        UUID tId = target.getUUID();
        // 从追踪中移除，使下次 sync 将此目标视为新增并重新 ADD
        Map<UUID, Relation> lastKnown = lastKnownMembers.get(vId);
        if (lastKnown != null) {
            lastKnown.remove(tId);
        }
        Scoreboard board = new Scoreboard();
        PlayerTeam f = board.addPlayerTeam(teamName(viewer, 'F'));
        PlayerTeam d = board.addPlayerTeam(teamName(viewer, 'D'));
        PlayerTeam e = board.addPlayerTeam(teamName(viewer, 'E'));
        String name = target.getScoreboardName();
        // 目标当前具体在哪个队伍是未知的（三态之一），从全部三个队伍移除以保证安全
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                f, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                d, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                e, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
    }

    /**
     * 重置指定观察者的同步状态，强制下次 sync() 进行全量同步。
     * 用于玩家重新加入、队伍切换等需要完整重建的场景。
     */
    public static void reset(UUID viewerId) {
        if (viewerId == null) return;
        initializedViewers.remove(viewerId.toString());
        lastKnownMembers.remove(viewerId);
    }

    private static String teamName(ServerPlayer viewer, char suffix) {
        String base = "bf" + Integer.toHexString(viewer.getUUID().hashCode()) + suffix;
        return base.length() <= 16 ? base : base.substring(0, 16);
    }
}
