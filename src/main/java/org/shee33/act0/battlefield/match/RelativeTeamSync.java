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
 * 按观察者视角同步虚拟队伍：该观察者看到的友方永远蓝色，敌方永远红色。
 *
 * <p>使用客户端私有虚拟队伍包，避免全局 scoreboard 污染。
 * 首次调用发送 ADD 创建队伍并同步全部成员；后续调用仅发送增量差异（ADD/REMOVE）。
 * 通过 lastKnownMembers 追踪每个观察者的上一次同步状态，仅对变更成员发送数据包。
 */
public final class RelativeTeamSync {

    private static final Set<String> initializedViewers = new HashSet<>();
    /** viewer UUID → set of target UUIDs currently synced to that viewer */
    private static final Map<UUID, Set<UUID>> lastKnownMembers = new HashMap<>();

    private RelativeTeamSync() {}

    /**
     * 为一个观察者同步队伍成员显示。
     *
     * <p>首次调用：发送友好/敌方队伍创建包，并对所有玩家发送 ADD。
     * 后续调用：比较当前玩家集合与上次已同步集合，仅对新增成员发送 ADD，
     * 对已移除成员发送 REMOVE，未变更成员跳过。
     *
     * @param viewer          观察者玩家
     * @param players         当前所有在编玩家 UUID 集合（通常是 factionOf.keySet()）
     * @param playerResolver  UUID → ServerPlayer 解析器
     * @param friendlyResolver UUID → 是否友方判定器（true=友方=蓝色，false=敌方=红色）
     */
    public static void sync(ServerPlayer viewer, Collection<UUID> players,
                            Function<UUID, ServerPlayer> playerResolver,
                            Function<UUID, Boolean> friendlyResolver) {
        if (viewer == null || players == null) return;
        UUID vId = viewer.getUUID();
        String vKey = vId.toString();
        boolean firstTime = initializedViewers.add(vKey);

        String fName = teamName(viewer, true);
        String eName = teamName(viewer, false);

        Scoreboard board = new Scoreboard();
        PlayerTeam friendly = board.addPlayerTeam(fName);
        friendly.setColor(ChatFormatting.BLUE);
        friendly.setNameTagVisibility(Team.Visibility.ALWAYS);
        PlayerTeam enemy = board.addPlayerTeam(eName);
        enemy.setColor(ChatFormatting.RED);
        enemy.setNameTagVisibility(Team.Visibility.ALWAYS);

        if (firstTime) {
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(friendly, true));
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(enemy, true));
        }

        Set<UUID> currentTargets = new HashSet<>(players);
        Set<UUID> lastKnown = lastKnownMembers.get(vId);

        if (lastKnown == null) {
            // 首次同步：发送全部成员 ADD
            for (UUID id : currentTargets) {
                ServerPlayer target = playerResolver.apply(id);
                if (target == null) continue;
                boolean isFriendly = Boolean.TRUE.equals(friendlyResolver.apply(id));
                PlayerTeam team = isFriendly ? friendly : enemy;
                viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        team, target.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.ADD));
            }
        } else {
            // 增量同步：仅发送变更（新增/移除）
            for (UUID id : currentTargets) {
                if (lastKnown.contains(id)) continue;
                // 新增成员 → ADD
                ServerPlayer target = playerResolver.apply(id);
                if (target == null) continue;
                boolean isFriendly = Boolean.TRUE.equals(friendlyResolver.apply(id));
                PlayerTeam team = isFriendly ? friendly : enemy;
                viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                        team, target.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.ADD));
            }
            for (UUID id : lastKnown) {
                if (currentTargets.contains(id)) continue;
                // 已移除成员 → REMOVE（从两个队伍中移除以保证安全）
                ServerPlayer target = playerResolver.apply(id);
                if (target != null) {
                    viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                            friendly, target.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.REMOVE));
                    viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                            enemy, target.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.REMOVE));
                }
            }
        }

        // 保存本次同步后的状态，供下次 diff 使用
        lastKnownMembers.put(vId, currentTargets);
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
        PlayerTeam f = board.addPlayerTeam(teamName(viewer, true));
        PlayerTeam e = board.addPlayerTeam(teamName(viewer, false));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(f));
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
        Set<UUID> lastKnown = lastKnownMembers.get(vId);
        if (lastKnown != null) {
            lastKnown.remove(tId);
        }
        Scoreboard board = new Scoreboard();
        PlayerTeam f = board.addPlayerTeam(teamName(viewer, true));
        PlayerTeam e = board.addPlayerTeam(teamName(viewer, false));
        String name = target.getScoreboardName();
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                f, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
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

    private static String teamName(ServerPlayer viewer, boolean friendly) {
        String base = "bf" + Integer.toHexString(viewer.getUUID().hashCode()) + (friendly ? "F" : "E");
        return base.length() <= 16 ? base : base.substring(0, 16);
    }
}
