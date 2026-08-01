package org.shee33.act0.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.core.BreakthroughRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.Sector;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.match.BreakthroughMatch;

import java.util.Arrays;
import java.util.List;

import static org.shee33.act0.battlefield.Act0Battlefield.BREAKTHROUGH_MANAGER;

/**
 * {@code /breakthrough} 命令树：突破模式（Breakthrough）的布场、开局、入队与状态查询。
 *
 * <p>所有布场/开局/停止操作均为 OP（权限等级 2）；{@code join}、{@code leave}、{@code status}
 * 对所有玩家开放。
 *
 * <p>{@code base set} 与 {@code sector} 直接维护 {@link BattlefieldData}；对局操作委托给全局
 * {@code BreakthroughManager}。
 */
public final class BreakthroughCommand {

    private BreakthroughCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("breakthrough")
                .then(Commands.literal("setup").requires(s -> s.hasPermission(2))
                    .executes(BreakthroughCommand::setup))
                .then(buildSectorBranch())
                .then(Commands.literal("base").requires(s -> s.hasPermission(2))
                    .then(Commands.literal("set")
                        .then(Commands.literal("attacker").executes(c -> setBase(c, Faction.ALPHA)))
                        .then(Commands.literal("defender").executes(c -> setBase(c, Faction.BRAVO)))))
                .then(buildStartBranch())
                .then(Commands.literal("stop").requires(s -> s.hasPermission(2))
                    .executes(BreakthroughCommand::stop))
                .then(Commands.literal("join")
                    .then(Commands.literal("attacker").executes(c -> join(c, Faction.ALPHA)))
                    .then(Commands.literal("defender").executes(c -> join(c, Faction.BRAVO))))
                .then(Commands.literal("leave").executes(BreakthroughCommand::leave))
                .then(buildOrderBranch())
                .then(Commands.literal("status").executes(BreakthroughCommand::status))
        );
    }

    /** /breakthrough order {attack|defend} <pointId>：小队长下达攻防命令。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildOrderBranch() {
        return Commands.literal("order")
                .then(Commands.literal("attack")
                        .then(Commands.argument("pointId", IntegerArgumentType.integer(0))
                                .executes(c -> setOrder(c, true))))
                .then(Commands.literal("defend")
                        .then(Commands.argument("pointId", IntegerArgumentType.integer(0))
                                .executes(c -> setOrder(c, false))));
    }

    private static int setOrder(CommandContext<CommandSourceStack> c, boolean attack) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        BreakthroughMatch match = BREAKTHROUGH_MANAGER.activeContaining(player.getUUID());
        if (match == null || !match.contains(player.getUUID())) {
            feedback(c, "§7当前未加入任何对局。");
            return 0;
        }
        if (!match.isSquadLeader(player.getUUID())) {
            feedback(c, "§c只有小队长可以下达命令。");
            return 0;
        }
        int pointId = IntegerArgumentType.getInteger(c, "pointId");
        String result = match.setSquadOrder(player.getUUID(), pointId, attack);
        feedback(c, result != null ? result : "§a命令已下达。");
        return result != null ? 0 : 1;
    }

    /** /breakthrough start [tickets] [name] [template]：四档可选参数链。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildStartBranch() {
        return Commands.literal("start")
            .requires(s -> s.hasPermission(2))
            .executes(c -> start(c, -1, null, null))
            .then(Commands.argument("tickets", IntegerArgumentType.integer(1, 100000))
                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "tickets"), null, null))
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(c -> start(c,
                        IntegerArgumentType.getInteger(c, "tickets"),
                        StringArgumentType.getString(c, "name"),
                        null))
                    .then(Commands.argument("template", StringArgumentType.string())
                        .executes(c -> start(c,
                            IntegerArgumentType.getInteger(c, "tickets"),
                            StringArgumentType.getString(c, "name"),
                            StringArgumentType.getString(c, "template"))))));
    }

    /** /breakthrough sector {add|list|remove}：管理突破模式的多个 sector。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildSectorBranch() {
        return Commands.literal("sector").requires(s -> s.hasPermission(2))
            .then(Commands.literal("add")
                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                    .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("pointIds", StringArgumentType.greedyString())
                            .executes(BreakthroughCommand::sectorAdd)))))
            .then(Commands.literal("list")
                .executes(BreakthroughCommand::sectorList))
            .then(Commands.literal("remove")
                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                    .executes(BreakthroughCommand::sectorRemove)));
    }

    // ---- 布场 ----

    private static int setup(CommandContext<CommandSourceStack> c) {
        feedback(c, "§a突破模式已就绪。");
        return 1;
    }

    private static int setBase(CommandContext<CommandSourceStack> c, Faction faction) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BattlefieldData.get(level).setBase(faction, new BattlefieldData.BaseSpawn(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        feedback(c, "§a已把 " + faction.coloredName() + " §a的基地设在你当前位置。");
        return 1;
    }

    private static int sectorAdd(CommandContext<CommandSourceStack> c) {
        int id = IntegerArgumentType.getInteger(c, "id");
        String name = StringArgumentType.getString(c, "name");
        String rawPointIds = StringArgumentType.getString(c, "pointIds");
        List<Integer> pointIds;
        try {
            pointIds = Arrays.stream(rawPointIds.trim().split("\\s+"))
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException e) {
            feedback(c, "§c据点编号必须是以空格分隔的整数。");
            return 0;
        }

        BattlefieldData.get(c.getSource().getLevel()).addSector(new Sector(id, pointIds, name));
        feedback(c, "§a已登记区域 §e#" + id + " " + name + " §a（据点 " + pointIds + "）。");
        return 1;
    }

    private static int sectorList(CommandContext<CommandSourceStack> c) {
        List<Sector> sectors = BattlefieldData.get(c.getSource().getLevel()).sectors();
        if (sectors.isEmpty()) {
            feedback(c, "§7当前世界没有突破模式区域。");
            return 1;
        }

        feedback(c, "§e突破模式区域（" + sectors.size() + "）：");
        for (Sector sector : sectors) {
            feedback(c, "§7- §f#" + sector.id() + " §e" + sector.displayName()
                    + " §7据点 " + sector.pointIds());
        }
        return 1;
    }

    private static int sectorRemove(CommandContext<CommandSourceStack> c) {
        int id = IntegerArgumentType.getInteger(c, "id");
        BattlefieldData data = BattlefieldData.get(c.getSource().getLevel());
        if (data.sectorById(id) == null) {
            feedback(c, "§c找不到编号为 " + id + " 的区域。");
            return 0;
        }

        data.removeSector(id);
        feedback(c, "§a已移除区域 §e#" + id + "§a。");
        return 1;
    }

    // ---- 开局 / 停止 ----

    private static int start(CommandContext<CommandSourceStack> c, int tickets, String name, String template) {
        BreakthroughRules rules = tickets < 0
                ? BreakthroughRules.standard()
                : new BreakthroughRules.Builder().startingTickets(tickets).build();
        String error = BREAKTHROUGH_MANAGER.start(c.getSource().getLevel(), rules, name, template);
        if (error != null) {
            feedback(c, error);
            return 0;
        }

        feedback(c, "§6突破对局已开始（进攻方 " + rules.startingTickets() + " 票"
                + (name == null ? "" : "，战役 " + name)
                + (template == null ? "" : "，模板 " + template) + "）。");
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        boolean stopped = BREAKTHROUGH_MANAGER.stop(c.getSource().getLevel());
        feedback(c, stopped ? "§7已停止当前突破对局。" : "§7当前世界没有进行中的突破对局。");
        return stopped ? 1 : 0;
    }

    // ---- 公共 ----

    private static int join(CommandContext<CommandSourceStack> c, Faction faction) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        BREAKTHROUGH_MANAGER.join(player.serverLevel(), player, faction);
        feedback(c, "§a已加入 " + faction.coloredName() + "§a，等待开局。");
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (BREAKTHROUGH_MANAGER.leave(player)) {
            feedback(c, "§7已退出当前突破对局。");
            return 1;
        }

        BREAKTHROUGH_MANAGER.leaveLobby(player.getUUID());
        BREAKTHROUGH_MANAGER.broadcastStatus(player.getServer());
        feedback(c, "§7已退出突破模式候选名单。");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        BreakthroughMatch active = BREAKTHROUGH_MANAGER.activeFor(c.getSource().getLevel());
        if (active != null) {
            feedback(c, "§a当前世界突破对局进行中 §8| §e" + active.attackerTickets()
                    + " §7票 §8| §7区域 §f" + (active.currentSectorIndex() + 1)
                    + "§7/§f" + active.totalSectors() + " §8| §7玩家 §f" + active.totalMembers());
        } else if (BREAKTHROUGH_MANAGER.hasActive()) {
            feedback(c, "§7当前世界空闲；其他世界有突破对局进行中。");
        } else {
            feedback(c, "§7当前没有进行中的突破对局。");
        }

        int attackers = 0;
        int defenders = 0;
        for (Faction faction : BREAKTHROUGH_MANAGER.lobby().values()) {
            if (faction == Faction.ALPHA) {
                attackers++;
            } else {
                defenders++;
            }
        }
        feedback(c, "§7候选名单 §9进攻 §f" + attackers + " §8/ §c防守 §f" + defenders);
        return 1;
    }

    private static void feedback(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSystemMessage(Component.literal(msg));
    }
}
