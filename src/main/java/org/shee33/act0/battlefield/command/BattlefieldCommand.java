package org.shee33.act0.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.hologram.BattlefieldEntranceHolograms;
import org.shee33.act0.battlefield.match.ConquestMatch;
import org.shee33.act0.battlefield.match.ConquestManager;

import java.util.List;

/**
 * {@code /battlefield} 命令：布场（基地/据点参数）、开局/停止、加入/离开候选名单、查看状态。
 *
 * <p>布场与开局为 OP（权限等级 2）操作；加入/离开/状态对所有玩家开放。
 */
public final class BattlefieldCommand {

    private BattlefieldCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("battlefield")
                .executes(BattlefieldCommand::openUi)
                .then(Commands.literal("ui").executes(BattlefieldCommand::openUi))
                .then(Commands.literal("join")
                        .then(Commands.literal("alpha").executes(c -> join(c, Faction.ALPHA)))
                        .then(Commands.literal("bravo").executes(c -> join(c, Faction.BRAVO))))
                .then(Commands.literal("quickjoin")
                    .then(Commands.argument("battle", StringArgumentType.string())
                        .executes(BattlefieldCommand::quickJoin)))
                .then(Commands.literal("leave").executes(BattlefieldCommand::leave))
                .then(Commands.literal("squad").executes(BattlefieldCommand::squadInfo))
                .then(Commands.literal("status").executes(BattlefieldCommand::status))
                .then(buildHologramBranch())
                .then(Commands.literal("base").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("set")
                                .then(Commands.literal("alpha").executes(c -> setBase(c, Faction.ALPHA)))
                                .then(Commands.literal("bravo").executes(c -> setBase(c, Faction.BRAVO)))))
                .then(Commands.literal("point").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("list").executes(BattlefieldCommand::listPoints))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(1, 64))
                                                .executes(BattlefieldCommand::setRadius))))
                        .then(Commands.literal("height")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(1, 64))
                                                .executes(BattlefieldCommand::setHeight))))
                        .then(Commands.literal("name")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("name", StringArgumentType.string())
                                    .executes(BattlefieldCommand::setName))))
                        .then(Commands.literal("marker")
                            .then(Commands.literal("size")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                    .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.4, 5.0))
                                        .executes(BattlefieldCommand::setMarkerSize))))
                            .then(Commands.literal("distance")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                    .then(Commands.argument("blocks", IntegerArgumentType.integer(32, 1000))
                                        .executes(BattlefieldCommand::setMarkerDistance))))
                            .then(Commands.literal("offset")
                                .then(Commands.argument("id", IntegerArgumentType.integer(0))
                                    .then(Commands.argument("x", DoubleArgumentType.doubleArg(-64.0, 64.0))
                                        .then(Commands.argument("y", DoubleArgumentType.doubleArg(-64.0, 64.0))
                                            .then(Commands.argument("z", DoubleArgumentType.doubleArg(-64.0, 64.0))
                                                .executes(BattlefieldCommand::setMarkerOffset))))))))
                .then(Commands.literal("start").requires(s -> s.hasPermission(2))
                        .executes(c -> start(c, (int) ConquestRules.standard().startingTickets()))
                        .then(Commands.argument("tickets", IntegerArgumentType.integer(1, 100000))
                                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "tickets")))))
                .then(Commands.literal("stop").requires(s -> s.hasPermission(2))
                        .executes(BattlefieldCommand::stop)));
        dispatcher.register(Commands.literal("suicide").executes(BattlefieldCommand::suicide));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildHologramBranch() {
        return Commands.literal("hologram")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("browser")
                .executes(ctx -> createHologram(ctx, BattlefieldEntranceHolograms.EntryType.BROWSER)))
            .then(Commands.literal("loadout")
                .executes(ctx -> createHologram(ctx, BattlefieldEntranceHolograms.EntryType.LOADOUT)))
            .then(Commands.literal("battlefield")
                .executes(ctx -> createHologram(ctx, BattlefieldEntranceHolograms.EntryType.BATTLEFIELD)))
            .then(Commands.literal("all")
                .executes(BattlefieldCommand::createAllHolograms))
            .then(Commands.literal("clear")
                .executes(ctx -> clearHolograms(ctx, 6.0D))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0D, 64.0D))
                    .executes(ctx -> clearHolograms(ctx, DoubleArgumentType.getDouble(ctx, "radius")))));
    }

    private static int createHologram(CommandContext<CommandSourceStack> ctx,
                                      BattlefieldEntranceHolograms.EntryType type)
            throws CommandSyntaxException {
        return BattlefieldEntranceHolograms.create(ctx.getSource().getPlayerOrException(), type);
    }

    private static int createAllHolograms(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return BattlefieldEntranceHolograms.createAll(ctx.getSource().getPlayerOrException());
    }

    private static int clearHolograms(CommandContext<CommandSourceStack> ctx, double radius) throws CommandSyntaxException {
        return BattlefieldEntranceHolograms.clear(ctx.getSource().getPlayerOrException(), radius);
    }

    private static int suicide(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        c.getSource().getPlayerOrException().kill();
        return 1;
    }

    // ---- 候选名单 ----

    private static int openUi(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Act0Battlefield.manager().openFor(player);
        return 1;
    }

    private static int join(CommandContext<CommandSourceStack> c, Faction faction) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Act0Battlefield.manager().join(player, faction);
        feedback(c, "§a已加入 " + faction.coloredName() + "§a，等待开局。");
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (Act0Battlefield.manager().leaveMatch(player)) {
            return 1;
        }
        Act0Battlefield.manager().leaveLobby(player.getUUID());
        feedback(c, "§7已退出候选名单。");
        return 1;
    }

    private static int quickJoin(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Act0Battlefield.manager().quickJoin(player, StringArgumentType.getString(c, "battle"));
        return 1;
    }

    private static int squadInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ConquestMatch match = Act0Battlefield.manager().activeContaining(player.getUUID());
        if (match == null || !match.contains(player.getUUID())) {
            feedback(c, "§7当前未加入进行中的小队。");
            return 1;
        }
        int squadId = match.squadIdOf(player.getUUID());
        int size = match.squadSizeOf(player.getUUID());
        Faction faction = match.factionOf(player.getUUID());
        feedback(c, "§a你在 " + (faction != null ? faction.coloredName() : "§7未知")
                + " §a第 §e" + displaySquadNumber(squadId) + " §a小队（" + size + "/5）。");
        return 1;
    }

    private static int displaySquadNumber(int squadId) {
        if (squadId >= 101) {
            return squadId - 100;
        }
        return Math.max(0, squadId);
    }

    // ---- 布场 ----

    private static int setBase(CommandContext<CommandSourceStack> c, Faction faction) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BattlefieldData.get(level).setBase(faction, new BattlefieldData.BaseSpawn(
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
        feedback(c, "§a已把 " + faction.coloredName() + " §a的基地设在你当前位置。");
        return 1;
    }

    private static int listPoints(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        List<ControlPointDef> defs = BattlefieldData.get(level).points();
        if (defs.isEmpty()) {
            feedback(c, "§7当前世界没有据点。放置“据点标记”方块即可登记。");
            return 1;
        }
        feedback(c, "§e据点列表（" + defs.size() + "）：");
        for (ControlPointDef def : defs) {
            feedback(c, "§7- §f#" + def.pointId() + " §e" + def.name()
                    + " §7@ " + def.pos().getX() + "," + def.pos().getY() + "," + def.pos().getZ()
                    + " §7半径 " + def.radius() + " 高度 " + def.height());
        }
        return 1;
    }

    private static int setRadius(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ControlPointDef def = requirePoint(c);
        if (def == null) {
            return 0;
        }
        def.setRadius(IntegerArgumentType.getInteger(c, "value"));
        BattlefieldData.get(c.getSource().getPlayerOrException().serverLevel()).setDirty();
        feedback(c, "§a据点 §e" + def.name() + " §a半径已设为 " + def.radius() + "。");
        return 1;
    }

    private static int setHeight(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ControlPointDef def = requirePoint(c);
        if (def == null) {
            return 0;
        }
        def.setHeight(IntegerArgumentType.getInteger(c, "value"));
        BattlefieldData.get(c.getSource().getPlayerOrException().serverLevel()).setDirty();
        feedback(c, "§a据点 §e" + def.name() + " §a高度已设为 " + def.height() + "。");
        return 1;
    }

    private static int setName(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ControlPointDef def = requirePoint(c);
        if (def == null) {
            return 0;
        }
        def.setName(StringArgumentType.getString(c, "name"));
        BattlefieldData.get(c.getSource().getPlayerOrException().serverLevel()).setDirty();
        feedback(c, "§a据点已重命名为 §e" + def.name() + "§a。");
        return 1;
    }

    private static int setMarkerSize(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ControlPointDef def = requirePoint(c);
        if (def == null) {
            return 0;
        }
        def.setMarkerScale(DoubleArgumentType.getDouble(c, "value"));
        BattlefieldData.get(c.getSource().getPlayerOrException().serverLevel()).setDirty();
        feedback(c, "§a据点 §e" + def.name() + " §a浮标大小已设为 §e" + def.markerScale() + "§a。");
        return 1;
    }

    private static int setMarkerDistance(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ControlPointDef def = requirePoint(c);
        if (def == null) {
            return 0;
        }
        def.setMarkerDistance(IntegerArgumentType.getInteger(c, "blocks"));
        BattlefieldData.get(c.getSource().getPlayerOrException().serverLevel()).setDirty();
        feedback(c, "§a据点 §e" + def.name() + " §a浮标距离已设为 §e" + def.markerDistance() + " §a格。");
        return 1;
    }

    private static int setMarkerOffset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ControlPointDef def = requirePoint(c);
        if (def == null) {
            return 0;
        }
        double x = DoubleArgumentType.getDouble(c, "x");
        double y = DoubleArgumentType.getDouble(c, "y");
        double z = DoubleArgumentType.getDouble(c, "z");
        def.setMarkerOffset(x, y, z);
        BattlefieldData.get(c.getSource().getPlayerOrException().serverLevel()).setDirty();
        feedback(c, "§a据点 §e" + def.name() + " §a浮标偏移已设为 §e"
                + def.markerOffsetX() + ", " + def.markerOffsetY() + ", " + def.markerOffsetZ() + "§a。");
        return 1;
    }

    private static ControlPointDef requirePoint(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        int id = IntegerArgumentType.getInteger(c, "id");
        ControlPointDef def = BattlefieldData.get(level).pointById(id);
        if (def == null) {
            feedback(c, "§c找不到编号为 " + id + " 的据点。");
        }
        return def;
    }

    // ---- 开局/停止 ----

    private static int start(CommandContext<CommandSourceStack> c, int tickets) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        ConquestRules rules = ConquestRules.builder().startingTickets(tickets).build();
        String err = Act0Battlefield.manager().start(level, rules);
        if (err != null) {
            feedback(c, err);
            return 0;
        }
        feedback(c, "§6大战场已开始（每方 " + tickets + " 票）。");
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        boolean stopped;
        try {
            stopped = Act0Battlefield.manager().stop(c.getSource().getPlayerOrException().serverLevel());
        } catch (CommandSyntaxException e) {
            stopped = false;
        }
        feedback(c, stopped ? "§7已停止当前大战场对局。" : "§7当前没有进行中的对局。");
        return 1;
    }

    // ---- 状态 ----

    private static int status(CommandContext<CommandSourceStack> c) {
        ConquestManager mgr = Act0Battlefield.manager();
        try {
            if (mgr.activeFor(c.getSource().getPlayerOrException().serverLevel()) != null) {
                feedback(c, "§a当前世界大战场进行中。");
            } else {
                feedback(c, "§7当前世界空闲中。候选名单：" + mgr.lobby().size() + " 人。 ");
            }
        } catch (CommandSyntaxException e) {
            if (mgr.hasActive()) {
            feedback(c, "§a大战场进行中。");
            } else {
            feedback(c, "§7空闲中。候选名单：" + mgr.lobby().size() + " 人。");
            }
        }
        return 1;
    }

    private static void feedback(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSystemMessage(Component.literal(msg));
    }
}
