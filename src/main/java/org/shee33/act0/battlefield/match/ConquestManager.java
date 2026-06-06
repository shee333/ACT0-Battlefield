package org.shee33.act0.battlefield.match;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.shee33.act0.battlefield.command.BattlefieldCommand;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.network.ActionPacket;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.BattlefieldStatusDto;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 征服对局管理器：维护一个候选名单（lobby）与至多一场进行中的 {@link ConquestMatch}，
 * 把 Forge 事件（服务器刻、死亡、注册命令、玩家登出、服务器关闭）路由到对局。
 *
 * <p>注册到 Forge 事件总线（{@code MinecraftForge.EVENT_BUS.register(manager)}）。MVP 阶段同一时间
 * 只支持一场对局。
 */
public final class ConquestManager {

    /** 候选名单：玩家 → 阵营，开局时转入对局。 */
    private final Map<UUID, Faction> lobby = new LinkedHashMap<>();

    @Nullable
    private ConquestMatch active;

    // ---- 候选名单 ----

    public void join(ServerPlayer player, Faction faction) {
        lobby.put(player.getUUID(), faction);
        broadcastStatus(player.getServer());
    }

    public void leaveLobby(UUID id) {
        lobby.remove(id);
    }

    public Map<UUID, Faction> lobby() {
        return lobby;
    }

    public boolean hasActive() {
        return active != null && !active.isEnded();
    }

    @Nullable
    public ConquestMatch active() {
        return active;
    }

    /**
     * 用当前候选名单开局。
     *
     * @return 失败原因；{@code null} 表示成功
     */
    @Nullable
    public String start(ServerLevel level, ConquestRules rules) {
        if (hasActive()) {
            return "§c已有进行中的大战场对局。";
        }
        if (lobby.isEmpty()) {
            return "§c还没有玩家选择阵营。";
        }
        BattlefieldData data = BattlefieldData.get(level);
        List<ControlPointDef> defs = data.points();
        if (defs.isEmpty()) {
            return "§c该世界尚未布置任何据点。";
        }
        if (data.base(Faction.ALPHA) == null || data.base(Faction.BRAVO) == null) {
            return "§c两个阵营的基地出生点都需先设置。";
        }
        active = new ConquestMatch(level, rules, defs, new LinkedHashMap<>(lobby), data);
        lobby.clear();
        active.begin();
        return null;
    }

    /** 中止当前对局。 */
    public boolean stop() {
        if (active != null && !active.isEnded()) {
            active.abort();
            active = null;
            return true;
        }
        active = null;
        return false;
    }

    // ---- 加入界面（UI）----

    /** 处理加入界面的玩家操作，并回推最新状态。 */
    public void handleAction(ServerPlayer player, ActionPacket.Action action) {
        switch (action) {
            case JOIN_ALPHA, JOIN_BRAVO -> {
                if (hasActive()) {
                    player.sendSystemMessage(Component.literal("§c对局进行中，无法在此加入。"));
                } else {
                    lobby.put(player.getUUID(), ActionPacket.factionOf(action));
                    broadcastStatus(player.getServer());
                    return;
                }
            }
            case LEAVE -> lobby.remove(player.getUUID());
            case START -> {
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("§c只有管理员可以开局。"));
                } else {
                    String err = start(player.serverLevel(), ConquestRules.standard());
                    if (err != null) {
                        player.sendSystemMessage(Component.literal(err));
                    } else {
                        broadcastStatus(player.getServer());
                        return;
                    }
                }
            }
            case STOP -> {
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("§c只有管理员可以停止。"));
                } else {
                    stop();
                    broadcastStatus(player.getServer());
                    return;
                }
            }
            case OPEN -> {
                openFor(player);
                return;
            }
            case REFRESH -> {
            }
        }
        // 单人即时反馈（刷新自己的界面）
        BattlefieldNetwork.sendStatus(player, false, snapshotFor(player));
    }

    /** 主动为玩家打开加入界面。 */
    public void openFor(ServerPlayer player) {
        BattlefieldNetwork.sendStatus(player, true, snapshotFor(player));
    }

    /** 为某玩家构建一份状态快照。 */
    public BattlefieldStatusDto snapshotFor(ServerPlayer player) {
        boolean canManage = player.hasPermissions(2);
        UUID id = player.getUUID();
        if (hasActive() && active != null) {
            int my = factionToCode(active.factionOf(id));
            return new BattlefieldStatusDto(true, canManage, my,
                    active.memberCount(Faction.ALPHA), active.memberCount(Faction.BRAVO),
                    active.displayTickets(Faction.ALPHA), active.displayTickets(Faction.BRAVO),
                    active.ownedPoints(Faction.ALPHA), active.ownedPoints(Faction.BRAVO),
                    active.totalPoints());
        }
        Faction mine = lobby.get(id);
        int alpha = 0;
        int bravo = 0;
        for (Faction f : lobby.values()) {
            if (f == Faction.ALPHA) {
                alpha++;
            } else {
                bravo++;
            }
        }
        return new BattlefieldStatusDto(false, canManage, factionToCode(mine),
                alpha, bravo, 0, 0, 0, 0, 0);
    }

    private static int factionToCode(@Nullable Faction f) {
        if (f == Faction.ALPHA) {
            return 1;
        }
        if (f == Faction.BRAVO) {
            return 2;
        }
        return 0;
    }

    /** 向所有在线玩家刷新状态（仅刷新已打开的界面）。 */
    public void broadcastStatus(@Nullable net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            BattlefieldNetwork.sendStatus(p, false, snapshotFor(p));
        }
    }

    // ---- 事件 ----

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BattlefieldCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || active == null) {
            return;
        }
        active.tick();
        if (active.isEnded()) {
            active = null;
            broadcastStatus(event.getServer());
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (active == null || !(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        if (active.onDeath(victim.getUUID(), resolveKiller(event.getSource().getEntity(), event.getSource().getDirectEntity()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (active == null || !(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        UUID attacker = resolveKiller(event.getSource().getEntity(), event.getSource().getDirectEntity());
        if (active.shouldCancelDamage(victim.getUUID(), attacker)) {
            event.setCanceled(true);
        }
    }

    private UUID resolveKiller(Entity attacker, Entity direct) {
        if (attacker instanceof ServerPlayer killer) {
            return killer.getUUID();
        }
        if (direct instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer shooter) {
            return shooter.getUUID();
        }
        return null;
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        lobby.remove(id);
    }
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (active != null && event.getEntity() instanceof ServerPlayer player) {
            active.onPlayerLogin(player);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (active != null) {
            active.abort();
            active = null;
        }
        lobby.clear();
    }
}
