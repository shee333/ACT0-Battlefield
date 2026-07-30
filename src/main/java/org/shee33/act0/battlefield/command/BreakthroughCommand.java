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
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;

/**
 * {@code /breakthrough} 命令树：突破模式（Breakthrough）的布场、开局、入队与状态查询。
 *
 * <p>所有布场/开局/停止操作均为 OP（权限等级 2）；{@code join}、{@code leave}、{@code status}
 * 对所有玩家开放。
 *
 * <p>当前为骨架阶段（T7 之前）：{@code BreakthroughManager} 尚未交付，多数 handler 仅返回占位
 * 反馈；{@code base set} 已直接复用 {@link BattlefieldData} 的基地存储。
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
                .then(Commands.literal("status").executes(BreakthroughCommand::status))
        );
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
                .then(Commands.argument("id", StringArgumentType.string())
                    .then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("pointIds", StringArgumentType.greedyString())
                            .executes(BreakthroughCommand::sectorAdd)))))
            .then(Commands.literal("list")
                .executes(BreakthroughCommand::sectorList))
            .then(Commands.literal("remove")
                .then(Commands.argument("id", StringArgumentType.string())
                    .executes(BreakthroughCommand::sectorRemove)));
    }

    // ---- 布场 ----

    private static int setup(CommandContext<CommandSourceStack> c) {
        feedback(c, "§e突破模式命令已注册。等待 BreakthroughManager 集成。");
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
        String id = StringArgumentType.getString(c, "id");
        String name = StringArgumentType.getString(c, "name");
        String pointIds = StringArgumentType.getString(c, "pointIds");
        feedback(c, "§e收到 sector add 占位：id=§a" + id + "§e, name=§a" + name
                + "§e, pointIds=§a" + pointIds + "§e。等待 BreakthroughManager 集成。");
        return 1;
    }

    private static int sectorList(CommandContext<CommandSourceStack> c) {
        feedback(c, "§e突破模式 sector 列表占位。等待 BreakthroughManager 集成。");
        return 1;
    }

    private static int sectorRemove(CommandContext<CommandSourceStack> c) {
        feedback(c, "§e收到 sector remove 占位：id=§a"
                + StringArgumentType.getString(c, "id") + "§e。等待 BreakthroughManager 集成。");
        return 1;
    }

    // ---- 开局 / 停止 ----

    private static int start(CommandContext<CommandSourceStack> c, int tickets, String name, String template) {
        feedback(c, "§e突破模式开局占位（tickets=" + tickets + ", name=" + name
                + ", template=" + template + "）。等待 BreakthroughManager 集成。");
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        feedback(c, "§e突破模式停止占位。等待 BreakthroughManager 集成。");
        return 1;
    }

    // ---- 公共 ----

    private static int join(CommandContext<CommandSourceStack> c, Faction faction) {
        feedback(c, "§e突破模式入队占位：" + faction.coloredName() + "§e。等待 BreakthroughManager 集成。");
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> c) {
        feedback(c, "§e突破模式离队占位。等待 BreakthroughManager 集成。");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        feedback(c, "§e突破模式状态占位。等待 BreakthroughManager 集成。");
        return 1;
    }

    private static void feedback(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSystemMessage(Component.literal(msg));
    }
}
