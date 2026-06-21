package org.shee33.act0.battlefield.match;

import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Function;

/**
 * 按观察者视角同步虚拟队伍：该观察者看到的友方永远蓝色，敌方永远红色。
 *
 * <p>原版 scoreboard 队伍颜色是全局的，无法同时满足双方玩家“友方蓝/敌方红”。
 * 这里给每个观察者下发只存在于其客户端的虚拟队伍包，从而让名字和发光轮廓都按观察者视角着色。
 */
public final class RelativeTeamSync {
    private RelativeTeamSync() {
    }

    public static void sync(ServerPlayer viewer, Collection<UUID> players,
                            Function<UUID, ServerPlayer> playerResolver,
                            Function<UUID, Boolean> friendlyResolver) {
        if (viewer == null || players == null) {
            return;
        }
        Scoreboard board = new Scoreboard();
        PlayerTeam friendly = board.addPlayerTeam(teamName(viewer, true));
        friendly.setColor(ChatFormatting.BLUE);
        friendly.setNameTagVisibility(Team.Visibility.ALWAYS);
        PlayerTeam enemy = board.addPlayerTeam(teamName(viewer, false));
        enemy.setColor(ChatFormatting.RED);
        enemy.setNameTagVisibility(Team.Visibility.ALWAYS);

        viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(friendly, true));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(enemy, true));

        for (UUID id : players) {
            ServerPlayer target = playerResolver.apply(id);
            if (target == null) {
                continue;
            }
            boolean isFriendly = Boolean.TRUE.equals(friendlyResolver.apply(id));
            PlayerTeam team = isFriendly ? friendly : enemy;
            viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                    team, target.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.ADD));
        }
    }

    /** 清除某观察者客户端里的本模组虚拟友方/敌方队伍。 */
    public static void clear(ServerPlayer viewer) {
        if (viewer == null) {
            return;
        }
        Scoreboard board = new Scoreboard();
        PlayerTeam friendly = board.addPlayerTeam(teamName(viewer, true));
        PlayerTeam enemy = board.addPlayerTeam(teamName(viewer, false));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(friendly));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(enemy));
    }

    /** 从某观察者客户端的虚拟友方/敌方队伍里移除一个目标玩家。 */
    public static void removeTarget(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null) {
            return;
        }
        Scoreboard board = new Scoreboard();
        PlayerTeam friendly = board.addPlayerTeam(teamName(viewer, true));
        PlayerTeam enemy = board.addPlayerTeam(teamName(viewer, false));
        String name = target.getScoreboardName();
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                friendly, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
        viewer.connection.send(ClientboundSetPlayerTeamPacket.createPlayerPacket(
                enemy, name, ClientboundSetPlayerTeamPacket.Action.REMOVE));
    }

    private static String teamName(ServerPlayer viewer, boolean friendly) {
        String base = "bf" + Integer.toHexString(viewer.getUUID().hashCode()) + (friendly ? "F" : "E");
        return base.length() <= 16 ? base : base.substring(0, 16);
    }
}
