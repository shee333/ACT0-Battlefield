package org.shee33.act0.battlefield.match;

import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 按观察者视角同步虚拟队伍：该观察者看到的友方永远蓝色，敌方永远红色。
 *
 * <p>使用客户端私有虚拟队伍包，避免全局 scoreboard 污染。
 * 首次调用发送 ADD 创建队伍，后续仅更新成员（MODIFY），不再重复创建。
 */
public final class RelativeTeamSync {

    private static final Set<String> initializedViewers = new HashSet<>();

    private RelativeTeamSync() {}

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

        for (UUID id : players) {
            ServerPlayer target = playerResolver.apply(id);
            if (target == null) continue;
            boolean isFriendly = Boolean.TRUE.equals(friendlyResolver.apply(id));
            PlayerTeam team = isFriendly ? friendly : enemy;
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                    team, target.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.ADD));
        }
    }

    public static void clear(ServerPlayer viewer) {
        if (viewer == null) return;
        initializedViewers.remove(viewer.getUUID().toString());
        Scoreboard board = new Scoreboard();
        PlayerTeam f = board.addPlayerTeam(teamName(viewer, true));
        PlayerTeam e = board.addPlayerTeam(teamName(viewer, false));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(f));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(e));
    }

    public static void removeTarget(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null) return;
        Scoreboard board = new Scoreboard();
        PlayerTeam f = board.addPlayerTeam(teamName(viewer, true));
        PlayerTeam e = board.addPlayerTeam(teamName(viewer, false));
        String name = target.getScoreboardName();
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                f, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                e, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
    }

    private static String teamName(ServerPlayer viewer, boolean friendly) {
        String base = "bf" + Integer.toHexString(viewer.getUUID().hashCode()) + (friendly ? "F" : "E");
        return base.length() <= 16 ? base : base.substring(0, 16);
    }
}