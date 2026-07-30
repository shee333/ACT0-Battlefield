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
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.core.BattleArea;
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
            .then(Commands.literal("browse").executes(BattlefieldCommand::openUi))
            .then(Commands.literal("browser").executes(BattlefieldCommand::openUi))
                .then(Commands.literal("join")
                        .then(Commands.literal("alpha").executes(c -> join(c, Faction.ALPHA)))
                    .then(Commands.literal("bravo").executes(c -> join(c, Faction.BRAVO))))
                .then(Commands.literal("joinall").requires(s -> s.hasPermission(2))
                    .executes(BattlefieldCommand::joinAll))
                .then(Commands.literal("join_all").requires(s -> s.hasPermission(2))
                    .executes(BattlefieldCommand::joinAll))
                .then(Commands.literal("quickjoin")
                    .then(Commands.argument("battle", StringArgumentType.greedyString())
                        .executes(BattlefieldCommand::quickJoin)))
                .then(Commands.literal("quick_join")
                    .then(Commands.argument("battle", StringArgumentType.greedyString())
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
                        .executes(c -> start(c, IntegerArgumentType.getInteger(c, "tickets")))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                            .executes(c -> start(c, IntegerArgumentType.getInteger(c, "tickets"),
                                StringArgumentType.getString(c, "name"))))))
                .then(buildAreaBranch())
                .then(Commands.literal("stop").requires(s -> s.hasPermission(2))
                        .executes(BattlefieldCommand::stop))
                .then(Commands.literal("kick").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(BattlefieldCommand::kick)))
                .then(Commands.literal("force").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.literal("alpha").executes(c -> force(c, Faction.ALPHA)))
                                .then(Commands.literal("bravo").executes(c -> force(c, Faction.BRAVO)))))
                .then(Commands.literal("pause").requires(s -> s.hasPermission(2))
                        .executes(BattlefieldCommand::pauseCmd))
                .then(Commands.literal("resume").requires(s -> s.hasPermission(2))
                        .executes(BattlefieldCommand::resumeCmd))
                .then(Commands.literal("tickets").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("set")
                                .then(Commands.literal("alpha")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 100000))
                                                .executes(c -> ticketsOp(c, "set", Faction.ALPHA))))
                                .then(Commands.literal("bravo")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 100000))
                                                .executes(c -> ticketsOp(c, "set", Faction.BRAVO)))))
                        .then(Commands.literal("add")
                                .then(Commands.literal("alpha")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                                .executes(c -> ticketsOp(c, "add", Faction.ALPHA))))
                                .then(Commands.literal("bravo")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                                .executes(c -> ticketsOp(c, "add", Faction.BRAVO)))))
                        .then(Commands.literal("sub")
                                .then(Commands.literal("alpha")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                                .executes(c -> ticketsOp(c, "sub", Faction.ALPHA))))
                                .then(Commands.literal("bravo")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
                                                .executes(c -> ticketsOp(c, "sub", Faction.BRAVO)))))));
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

    /** /battlefield area {info|set|clear|here}：管理战斗区域边界。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildAreaBranch() {
        return Commands.literal("area")
            .then(Commands.literal("info")
                .executes(BattlefieldCommand::areaInfo))
            .then(Commands.literal("set").requires(s -> s.hasPermission(2))
                .then(Commands.argument("minX", IntegerArgumentType.integer())
                    .then(Commands.argument("minY", IntegerArgumentType.integer())
                        .then(Commands.argument("minZ", IntegerArgumentType.integer())
                            .then(Commands.argument("maxX", IntegerArgumentType.integer())
                                .then(Commands.argument("maxY", IntegerArgumentType.integer())
                                    .then(Commands.argument("maxZ", IntegerArgumentType.integer())
                                        .executes(BattlefieldCommand::areaSet))))))))
            .then(Commands.literal("clear").requires(s -> s.hasPermission(2))
                .executes(BattlefieldCommand::areaClear))
            .then(Commands.literal("here").requires(s -> s.hasPermission(2))
                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 4096))
                    .executes(BattlefieldCommand::areaHere)));
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

    private static int joinAll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return Act0Battlefield.manager().joinAllInWorld(c.getSource().getPlayerOrException());
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
                + " §a第 §e" + displaySquadNumber(squadId) + " §a小队（" + size + "/4）。");
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
        return start(c, tickets, null);
    }

    private static int start(CommandContext<CommandSourceStack> c, int tickets, String name) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        ConquestRules rules = ConquestRules.builder().startingTickets(tickets).build();
        String err = name == null
                ? Act0Battlefield.manager().start(level, rules)
                : Act0Battlefield.manager().start(level, rules, name);
        if (err != null) {
            feedback(c, err);
            return 0;
        }
        feedback(c, "§6大战场已开始（每方 " + tickets + " 票" + (name == null ? "" : "，战役 " + name) + "）。");
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

    // ---- 战斗区域 ----

    private static int areaInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        BattlefieldData data = BattlefieldData.get(level);
        BattleArea override = data.areaOverride();
        BattleArea effective = data.effectiveArea();
        if (!effective.isSet()) {
            feedback(c, "§7当前世界尚未布置任何据点或基地，无法推导战斗区域。");
            return 1;
        }
        feedback(c, "§b战斗区域（" + (override.isSet() ? "显式录入" : "由据点/基地推导") + "）：");
        feedback(c, "§7  范围 §f" + fmt(effective.minX()) + " ~ " + fmt(effective.maxX())
                + " §8/ §f" + fmt(effective.minY()) + " ~ " + fmt(effective.maxY())
                + " §8/ §f" + fmt(effective.minZ()) + " ~ " + fmt(effective.maxZ()));
        feedback(c, "§7  尺寸 §f" + fmt(effective.sizeX()) + " × " + fmt(effective.sizeY())
                + " × " + fmt(effective.sizeZ()));
        return 1;
    }

    private static int areaSet(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        int minX = IntegerArgumentType.getInteger(c, "minX");
        int minY = IntegerArgumentType.getInteger(c, "minY");
        int minZ = IntegerArgumentType.getInteger(c, "minZ");
        int maxX = IntegerArgumentType.getInteger(c, "maxX");
        int maxY = IntegerArgumentType.getInteger(c, "maxY");
        int maxZ = IntegerArgumentType.getInteger(c, "maxZ");
        if (maxX <= minX || maxY <= minY || maxZ <= minZ) {
            feedback(c, "§c区域非法：最大值必须大于最小值。");
            return 0;
        }
        if (maxX - minX > 8192 || maxY - minY > 1024 || maxZ - minZ > 8192) {
            feedback(c, "§c区域过大：单边上限 8192/1024/8192。");
            return 0;
        }
        BattlefieldData.get(level).setArea(new BattleArea(minX, minY, minZ, maxX, maxY, maxZ));
        feedback(c, "§a战斗区域已设为 §f" + minX + "," + minY + "," + minZ
                + " §7~ §f" + maxX + "," + maxY + "," + maxZ + "§a。");
        return 1;
    }

    private static int areaClear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        BattlefieldData.get(level).setArea(BattleArea.EMPTY);
        feedback(c, "§7已清除显式战斗区域，恢复为据点/基地推导。");
        return 1;
    }

    private static int areaHere(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        int r = IntegerArgumentType.getInteger(c, "radius");
        BattleArea area = new BattleArea(
                p.getX() - r, p.getY() - 32, p.getZ() - r,
                p.getX() + r, p.getY() + 96, p.getZ() + r);
        BattlefieldData.get(p.serverLevel()).setArea(area);
        feedback(c, "§a战斗区域已设为以你为中心、半径 §e" + r + " §a格（高度自动 32~96）。");
        return 1;
    }

    private static String fmt(double v) {
        return (v == Math.floor(v)) ? Integer.toString((int) v) : String.format("%.2f", v);
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

    // ---- 管理员命令 (OP 2) ----

    private static int kick(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(c, "target");
        ConquestMatch match = Act0Battlefield.manager().activeContaining(target.getUUID());
        if (match == null) {
            feedback(c, "§c该玩家不在任何进行中的对局中。");
            return 0;
        }
        match.quitPlayer(target);
        feedback(c, "§a已将 " + target.getGameProfile().getName() + " 移出对局。");
        return 1;
    }

    private static int force(CommandContext<CommandSourceStack> c, Faction faction) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(c, "target");
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        ConquestMatch match = Act0Battlefield.manager().activeFor(level);
        if (match == null) {
            feedback(c, "§c当前世界没有进行中的对局。");
            return 0;
        }
        if (match.contains(target.getUUID())) {
            match.quitPlayer(target);
        } else {
            Act0Battlefield.manager().leaveMatch(target);
        }
        if (!match.addLatecomer(target, faction)) {
            feedback(c, "§c无法将 " + target.getGameProfile().getName() + " 分配到 " + faction.coloredName() + "。");
            return 0;
        }
        feedback(c, "§a已将 " + target.getGameProfile().getName() + " 强制分配至 " + faction.coloredName() + "。");
        return 1;
    }

    private static int pauseCmd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ConquestMatch match = Act0Battlefield.manager().activeFor(c.getSource().getPlayerOrException().serverLevel());
        if (match == null) {
            feedback(c, "§c当前世界没有进行中的对局。");
            return 0;
        }
        if (match.isPaused()) {
            feedback(c, "§7对局已经处于暂停状态。");
            return 1;
        }
        match.pause();
        feedback(c, "§e大战场对局已暂停。");
        return 1;
    }

    private static int resumeCmd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ConquestMatch match = Act0Battlefield.manager().activeFor(c.getSource().getPlayerOrException().serverLevel());
        if (match == null) {
            feedback(c, "§c当前世界没有进行中的对局。");
            return 0;
        }
        if (!match.isPaused()) {
            feedback(c, "§7对局未处于暂停状态。");
            return 1;
        }
        match.resume();
        feedback(c, "§a大战场对局已恢复。");
        return 1;
    }

    private static int ticketsOp(CommandContext<CommandSourceStack> c, String op, Faction faction) throws CommandSyntaxException {
        ConquestMatch match = Act0Battlefield.manager().activeFor(c.getSource().getPlayerOrException().serverLevel());
        if (match == null) {
            feedback(c, "§c当前世界没有进行中的对局。");
            return 0;
        }
        int amount = IntegerArgumentType.getInteger(c, "amount");
        switch (op) {
            case "set" -> match.setTickets(faction, amount);
            case "add" -> match.addTickets(faction, amount);
            case "sub" -> match.subTickets(faction, amount);
        }
        feedback(c, "§a已修改 " + faction.coloredName() + " §a票数（当前：" + match.displayTickets(faction) + "）。");
        return 1;
    }
}
