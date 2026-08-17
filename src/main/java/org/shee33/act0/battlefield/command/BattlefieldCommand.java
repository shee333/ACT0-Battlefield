package org.shee33.act0.battlefield.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.BattlefieldConfig;
import org.shee33.act0.battlefield.bot.AimModel;
import org.shee33.act0.battlefield.bot.mc.BotManager;
import org.shee33.act0.battlefield.core.BattleArea;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.MatchCapacity;
import org.shee33.act0.battlefield.core.MapTemplate;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.hologram.BattlefieldEntranceHolograms;
import org.shee33.act0.battlefield.match.ConquestMatch;
import org.shee33.act0.battlefield.match.ConquestManager;
import org.shee33.act0.battlefield.match.MapTemplateManager;
import org.shee33.act0.battlefield.reg.BattlefieldRegistry;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.io.IOException;

/**
 * 征服模式与通用管理指令：布场（基地/据点参数）、开局/停止、加入/离开候选名单、查看状态。
 *
 * <p>布场与开局为 OP（权限等级 2）操作；加入/离开/状态对所有玩家开放。
 *
 * <p>这些子命令直接挂在 {@link Aew1Command#ROOT} 根下（即 {@code /aew1 join} 而不是
 * {@code /aew1 battlefield join}）——征服是本模组的主模式，多一层前缀只是让最常用的指令更难打。
 */
public final class BattlefieldCommand {

    private BattlefieldCommand() {
    }

    /** 把本类的全部子命令挂到给定根节点上，由 {@link Aew1Command} 统一注册。 */
    public static void attachTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root
                .executes(BattlefieldCommand::openUi)
                .then(Commands.literal("ui").executes(BattlefieldCommand::openUi))
            .then(Commands.literal("browse").executes(BattlefieldCommand::openUi))
            .then(Commands.literal("browser").executes(BattlefieldCommand::openUi))
                .then(Commands.literal("join").executes(BattlefieldCommand::join))
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
                .then(buildBotBranch())
                .then(buildOrderBranch())
                .then(Commands.literal("status").executes(BattlefieldCommand::status))
                .then(buildHologramBranch())
                .then(Commands.literal("base").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("set")
                                .then(Commands.literal("alpha").executes(c -> setBase(c, Faction.ALPHA)))
                                .then(Commands.literal("bravo").executes(c -> setBase(c, Faction.BRAVO)))))
                .then(Commands.literal("map").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("name")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(BattlefieldCommand::setMapName)))
                        .then(Commands.literal("minplayers")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 128))
                                        .executes(BattlefieldCommand::setMinPlayers)))
                        .then(Commands.literal("maxplayers")
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 128))
                                        .executes(BattlefieldCommand::setMaxPlayers)))
                        .then(Commands.literal("info").executes(BattlefieldCommand::mapInfo)))
                .then(Commands.literal("point").requires(s -> s.hasPermission(2))
                        .then(Commands.literal("add")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(BattlefieldCommand::addPointAt)))
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
                .then(buildTemplateBranch())
                .then(buildPresetBranch())
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
                                                .executes(c -> ticketsOp(c, "sub", Faction.BRAVO))))))
                .then(Commands.literal("suicide").executes(BattlefieldCommand::suicide));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildHologramBranch() {
        return Commands.literal("hologram")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("battlefield")
                .executes(ctx -> createHologram(ctx, BattlefieldEntranceHolograms.EntryType.BATTLEFIELD)))
            .then(Commands.literal("all")
                .executes(BattlefieldCommand::createAllHolograms))
            .then(Commands.literal("clear")
                .executes(ctx -> clearHolograms(ctx, 6.0D))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1.0D, 64.0D))
                    .executes(ctx -> clearHolograms(ctx, DoubleArgumentType.getDouble(ctx, "radius")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildOrderBranch() {
        return Commands.literal("order")
                .then(Commands.literal("attack")
                        .then(Commands.argument("pointId", IntegerArgumentType.integer(0))
                                .executes(c -> setOrder(c, true))))
                .then(Commands.literal("defend")
                        .then(Commands.argument("pointId", IntegerArgumentType.integer(0))
                                .executes(c -> setOrder(c, false))));
    }

    /** /aew1 area {info|set|clear|here}：管理战斗区域边界。 */
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

    private static int join(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        Faction faction = Act0Battlefield.manager().join(player);
        if (faction == null) {
            feedback(c, "§c该大战场已满员，无法加入。");
            return 0;
        }
        feedback(c, "§a已加入 " + faction.coloredName() + "§a，等待开局。");
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        if (Act0Battlefield.manager().leaveMatch(player)) {
            return 1;
        }
        Act0Battlefield.manager().leaveLobby(player.getUUID());
        // 退出候选名单同样要刷新对局浏览器（leaveMatch 内部已自带广播，这条路径此前漏了，
        // 导致玩家从浏览器点\"退出\"后要等下一次 2 秒轮询列表才更新）。
        Act0Battlefield.broadcastRoomList(player.getServer());
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

    private static int setOrder(CommandContext<CommandSourceStack> c, boolean attack) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ConquestMatch match = Act0Battlefield.manager().activeContaining(player.getUUID());
        if (match == null || !match.contains(player.getUUID())) {
            feedback(c, "§7当前未加入任何对局。");
            return 0;
        }
        if (!match.isSquadLeader(player.getUUID())) {
            feedback(c, "§c只有小队长可以下达命令。");
            return 0;
        }
        int pointId = IntegerArgumentType.getInteger(c, "pointId");
        ConquestMatch mgr = match;
        String result = mgr.setSquadOrder(player.getUUID(), pointId, attack);
        feedback(c, result != null ? result : "§a命令已下达。");
        return result != null ? 0 : 1;
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

    private static int setMapName(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        String name = StringArgumentType.getString(c, "name");
        BattlefieldData.get(level).setMapName(name);
        feedback(c, "§a已将当前世界地图命名为 §e" + name);
        return 1;
    }

    /**
     * 设置本地图的自动开始人数。传 {@code 0} 清除自定义、回退全局配置。
     *
     * <p>设置前校验与人数上限的自洽性：开局人数大于上限时对局永远开不了，必须当场拒绝而不是
     * 让管理员事后从"玩家一直进不去"里反推。
     */
    private static int setMinPlayers(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        int value = IntegerArgumentType.getInteger(c, "value");
        BattlefieldData data = BattlefieldData.get(level);
        if (value > 0) {
            String error = MatchCapacity.validate(value, data.effectiveMaxPlayers(BattlefieldConfig.MAX_PLAYERS.get()));
            if (error != null) {
                feedback(c, "§c" + error);
                return 0;
            }
        }
        data.setMinPlayersToStart(value);
        feedback(c, value > 0
                ? "§a本地图自动开始人数已设为 §e" + value
                : "§7本地图自动开始人数已清除，跟随全局配置（当前 "
                        + BattlefieldConfig.MIN_PLAYERS_TO_START.get() + "）");
        Act0Battlefield.broadcastRoomList(c.getSource().getServer());
        return 1;
    }

    /** 设置本地图的对局人数上限。传 {@code 0} 清除自定义、回退全局配置。 */
    private static int setMaxPlayers(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        int value = IntegerArgumentType.getInteger(c, "value");
        BattlefieldData data = BattlefieldData.get(level);
        if (value > 0) {
            String error = MatchCapacity.validate(
                    data.effectiveMinPlayers(BattlefieldConfig.MIN_PLAYERS_TO_START.get()), value);
            if (error != null) {
                feedback(c, "§c" + error);
                return 0;
            }
        }
        data.setMaxPlayers(value);
        feedback(c, value > 0
                ? "§a本地图人数上限已设为 §e" + value
                : "§7本地图人数上限已清除，跟随全局配置（当前 " + BattlefieldConfig.MAX_PLAYERS.get() + "）");
        Act0Battlefield.broadcastRoomList(c.getSource().getServer());
        return 1;
    }

    /** 展示本地图当前生效的人数规则，并标明是自定义还是跟随全局。 */
    private static int mapInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        BattlefieldData data = BattlefieldData.get(level);
        int min = data.effectiveMinPlayers(BattlefieldConfig.MIN_PLAYERS_TO_START.get());
        int max = data.effectiveMaxPlayers(BattlefieldConfig.MAX_PLAYERS.get());
        feedback(c, "§7地图 §e" + (data.mapName().isBlank() ? level.dimension().location() : data.mapName()));
        feedback(c, "§7自动开始人数：§e" + min + (data.minPlayersToStartRaw() > 0 ? " §7(本地图自定义)" : " §7(跟随全局)"));
        feedback(c, "§7人数上限：§e" + max + (data.maxPlayersRaw() > 0 ? " §7(本地图自定义)" : " §7(跟随全局)"));
        return 1;
    }

    /**
     * {@code /aew1 point add <x> <y> <z>}：按坐标登记一个据点。
     *
     * <p>据点原本只能通过<b>实体放置</b>据点方块来登记（{@code ControlPointBlock.setPlacedBy}）。
     * 这条命令补上了按坐标登记的入口，作用是让布场可以脚本化：服务端自动开图、批量部署地图模板、
     * 以及在没有真人在线时验证对局流程——{@code /setblock} 不会触发 {@code setPlacedBy}，
     * 因此在此之前控制台完全无法把一张图布置到可开局状态。
     *
     * <p>方块本身也会一并放下，使命令登记出的据点与手动放置的据点在世界里完全一致
     * （破坏该方块仍会按 {@code onRemove} 注销据点）。
     */
    private static int addPointAt(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getLevel();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(c, "pos");
        level.setBlockAndUpdate(pos, BattlefieldRegistry.CONTROL_POINT.get().defaultBlockState());
        ControlPointDef def = BattlefieldData.get(level).addPoint(pos);
        c.getSource().sendSuccess(() -> Component.literal("§a已登记据点 §e" + def.name()
                + " §7(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                + " · 半径 " + def.radius() + " · 高度 " + def.height() + ")"), true);
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

    // ---- 模板管理 ----

    /** /aew1 template {save|list|delete|info}：管理地图模板。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildTemplateBranch() {
        return Commands.literal("template")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(BattlefieldCommand::templateSave)))
            .then(Commands.literal("list")
                .executes(BattlefieldCommand::templateList))
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(BattlefieldCommand::templateDelete)))
            .then(Commands.literal("info")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(BattlefieldCommand::templateInfo)));
    }

    private static int templateSave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        BattlefieldData data = BattlefieldData.get(level);
        BattleArea area = data.effectiveArea();
        if (!area.isSet()) {
            feedback(c, "§c当前世界没有设置战斗区域，无法保存模板。请先使用 /aew1 area 设置区域。");
            return 0;
        }
        String name = StringArgumentType.getString(c, "name");
        try {
            MapTemplateManager.saveTemplate(name, level, area, MapTemplateManager.DEFAULT_BASE_PATH);
            feedback(c, "§a模板 §e" + name + " §a已保存。");
            return 1;
        } catch (IOException e) {
            feedback(c, "§c保存模板失败: " + e.getMessage());
            return 0;
        } catch (IllegalArgumentException e) {
            feedback(c, "§c参数无效: " + e.getMessage());
            return 0;
        }
    }

    private static int templateList(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        try {
            List<MapTemplate> templates = MapTemplateManager.listTemplates(MapTemplateManager.DEFAULT_BASE_PATH);
            if (templates.isEmpty()) {
                feedback(c, "§7没有已保存的模板。");
                return 1;
            }
            feedback(c, "§e已保存的模板（" + templates.size() + "）：");
            for (MapTemplate t : templates) {
                feedback(c, "§7- §f" + t.name() + " §7创建于 " + t.createdAt());
            }
            return 1;
        } catch (IOException e) {
            feedback(c, "§c读取模板列表失败: " + e.getMessage());
            return 0;
        }
    }

    private static int templateDelete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "name");
        try {
            MapTemplateManager.deleteTemplate(name, MapTemplateManager.DEFAULT_BASE_PATH);
            feedback(c, "§a模板 §e" + name + " §a已删除。");
            return 1;
        } catch (IOException e) {
            feedback(c, "§c删除模板失败: " + e.getMessage());
            return 0;
        }
    }

    private static int templateInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "name");
        try {
            List<MapTemplate> templates = MapTemplateManager.listTemplates(MapTemplateManager.DEFAULT_BASE_PATH);
            MapTemplate found = null;
            for (MapTemplate t : templates) {
                if (t.name().equals(name)) {
                    found = t;
                    break;
                }
            }
            if (found == null) {
                feedback(c, "§c未找到模板 §e" + name + "§c。");
                return 0;
            }
            feedback(c, "§b模板信息：");
            feedback(c, "§7名称: §f" + found.name());
            feedback(c, "§7创建时间: §f" + found.createdAt());
            feedback(c, "§7路径: §f" + found.regionPath());
            return 1;
        } catch (IOException e) {
            feedback(c, "§c读取模板信息失败: " + e.getMessage());
            return 0;
        }
    }

    // ---- 预设管理 ----

    /** /aew1 preset {save|load|list|delete}：把据点+基地+区域另存为命名预设，省去重启后重新布场。 */
    private static LiteralArgumentBuilder<CommandSourceStack> buildPresetBranch() {
        return Commands.literal("preset")
            .requires(s -> s.hasPermission(2))
            .then(Commands.literal("save")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(BattlefieldCommand::presetSave)))
            .then(Commands.literal("load")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(BattlefieldCommand::presetLoad)))
            .then(Commands.literal("list")
                .executes(BattlefieldCommand::presetList))
            .then(Commands.literal("delete")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(BattlefieldCommand::presetDelete)));
    }

    private static int presetSave(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        BattlefieldData data = BattlefieldData.get(level);
        String name = StringArgumentType.getString(c, "name");
        if (name.isBlank()) {
            feedback(c, "§c预设名不能为空。");
            return 0;
        }
        data.savePreset(name, buildCurrentSetupTag(data));
        feedback(c, "§a预设 §e" + name + " §a已保存。");
        return 1;
    }

    private static int presetLoad(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        BattlefieldData data = BattlefieldData.get(level);
        String name = StringArgumentType.getString(c, "name");
        CompoundTag tag = data.loadPreset(name);
        if (tag == null) {
            feedback(c, "§c未找到预设 §e" + name + "§c。");
            return 0;
        }
        data.clearAll();
        ListTag pointsList = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < pointsList.size(); i++) {
            data.importPoint(ControlPointDef.load(pointsList.getCompound(i)));
        }
        if (tag.contains("alphaBase")) {
            data.setBase(Faction.ALPHA, BattlefieldData.BaseSpawn.load(tag.getCompound("alphaBase")));
        }
        if (tag.contains("bravoBase")) {
            data.setBase(Faction.BRAVO, BattlefieldData.BaseSpawn.load(tag.getCompound("bravoBase")));
        }
        if (tag.contains("area")) {
            CompoundTag a = tag.getCompound("area");
            data.setArea(new BattleArea(
                    a.getDouble("minX"), a.getDouble("minY"), a.getDouble("minZ"),
                    a.getDouble("maxX"), a.getDouble("maxY"), a.getDouble("maxZ")));
        }
        feedback(c, "§a预设 §e" + name + " §a已加载（" + pointsList.size() + " 个据点）。");
        return 1;
    }

    private static int presetList(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        List<String> names = BattlefieldData.get(level).listPresets();
        if (names.isEmpty()) {
            feedback(c, "§7没有已保存的预设。");
            return 1;
        }
        feedback(c, "§e已保存的预设（" + names.size() + "）：");
        for (String n : names) {
            feedback(c, "§7- §f" + n);
        }
        return 1;
    }

    private static int presetDelete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        BattlefieldData data = BattlefieldData.get(level);
        String name = StringArgumentType.getString(c, "name");
        if (!data.listPresets().contains(name)) {
            feedback(c, "§c未找到预设 §e" + name + "§c。");
            return 0;
        }
        data.deletePreset(name);
        feedback(c, "§a预设 §e" + name + " §a已删除。");
        return 1;
    }

    /** 把当前 {@link BattlefieldData} 的据点+基地+区域序列化为预设 NBT。 */
    private static CompoundTag buildCurrentSetupTag(BattlefieldData data) {
        CompoundTag tag = new CompoundTag();
        ListTag pointsList = new ListTag();
        for (ControlPointDef def : data.points()) {
            pointsList.add(def.save());
        }
        tag.put("points", pointsList);
        BattlefieldData.BaseSpawn alpha = data.base(Faction.ALPHA);
        if (alpha != null) {
            tag.put("alphaBase", alpha.save());
        }
        BattlefieldData.BaseSpawn bravo = data.base(Faction.BRAVO);
        if (bravo != null) {
            tag.put("bravoBase", bravo.save());
        }
        BattleArea area = data.areaOverride();
        if (area.isSet()) {
            CompoundTag a = new CompoundTag();
            a.putDouble("minX", area.minX());
            a.putDouble("minY", area.minY());
            a.putDouble("minZ", area.minZ());
            a.putDouble("maxX", area.maxX());
            a.putDouble("maxY", area.maxY());
            a.putDouble("maxZ", area.maxZ());
            tag.put("area", a);
        }
        return tag;
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

    /**
     * 命令回复。真人走 {@code sendSystemMessage}，其余源头（控制台／RCON／AI 士兵）走 {@code sendSuccess}。
     *
     * <p>不能只用其中一个：{@code sendSystemMessage} 在源头实体是玩家时会把消息投给<b>那个实体</b>而不是
     * 命令的发起者，于是 {@code execute as <某玩家>} 的回显对控制台与 RCON 永远不可见（AI 士兵同样是
     * {@code ServerPlayer}，调试时整条链路就是哑的）；而 {@code sendSuccess} 受 {@code sendCommandFeedback}
     * 游戏规则管辖，服主一旦关掉，真人点命令会毫无反应。
     *
     * <p>因此走标准命令输出，仅在该规则被关掉时补发给玩家本人——两条保证各自成立且不会重复投递。
     */
    static void feedback(CommandContext<CommandSourceStack> c, String msg) {
        CommandSourceStack source = c.getSource();
        source.sendSuccess(() -> Component.literal(msg), false);
        if (!source.getServer().getGameRules().getBoolean(GameRules.RULE_SENDCOMMANDFEEDBACK)
                && source.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(msg));
        }
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

    // ---------------- bot（AI 士兵） ----------------

    /**
     * {@code /aew1 bot ...}：对<b>进行中的对局</b>手动补入 AI 士兵。
     *
     * <p>刻意不做成"开局前配置席位"：大战场的对局是长时间连续进行的，管理员真正需要的是在人数
     * 不够或一边被打崩时随时补人，而不是开局那一刻定好数量。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> buildBotBranch() {
        LiteralArgumentBuilder<CommandSourceStack> difficulty = Commands.literal("difficulty");
        for (AimModel.Difficulty tier : AimModel.Difficulty.values()) {
            String key = tier.name().toLowerCase(Locale.ROOT);
            difficulty.then(Commands.literal(key)
                    .executes(c -> botDifficultyAll(c, tier))
                    .then(Commands.argument("name", StringArgumentType.word())
                            .executes(c -> botDifficultyOne(c, tier))));
        }
        return Commands.literal("bot")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("add")
                        .executes(c -> botAdd(c, 1, null))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                .executes(c -> botAdd(c, IntegerArgumentType.getInteger(c, "count"), null))
                                .then(Commands.literal("alpha")
                                        .executes(c -> botAdd(c, IntegerArgumentType.getInteger(c, "count"), Faction.ALPHA)))
                                .then(Commands.literal("bravo")
                                        .executes(c -> botAdd(c, IntegerArgumentType.getInteger(c, "count"), Faction.BRAVO)))))
                .then(Commands.literal("spawn")
                        .executes(c -> botSpawnBare(c, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                .executes(c -> botSpawnBare(c, IntegerArgumentType.getInteger(c, "count")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(BattlefieldCommand::botRemove)))
                .then(Commands.literal("clear").executes(BattlefieldCommand::botClear))
                .then(Commands.literal("list").executes(BattlefieldCommand::botList))
                .then(difficulty);
    }

    /**
     * 定位要操作的对局：执行者自己所在的那一场优先，否则取服务器上唯一进行中的一场。
     *
     * <p>优先取执行者所在对局，是为了让管理员在多维度同时开局时不必额外指定——他站在哪一局里，
     * 补的人就进哪一局。
     */
    @Nullable
    private static ConquestMatch resolveMatch(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            ConquestMatch mine = Act0Battlefield.manager().activeContaining(player.getUUID());
            if (mine != null) {
                return mine;
            }
        }
        return Act0Battlefield.manager().active();
    }

    private static int botAdd(CommandContext<CommandSourceStack> ctx, int count,
                              @Nullable Faction faction) {
        ConquestMatch match = resolveMatch(ctx);
        if (match == null) {
            ctx.getSource().sendFailure(Component.literal("§c当前没有进行中的对局。"));
            return 0;
        }
        // 未指定阵营时补给人少的一方——管理员补 bot 的动机通常就是人数不均。
        Faction target = faction != null ? faction
                : (match.memberCount(Faction.ALPHA) <= match.memberCount(Faction.BRAVO)
                        ? Faction.ALPHA : Faction.BRAVO);
        List<String> added = BotManager.INSTANCE.addToMatch(
                ctx.getSource().getServer(), match, target, count);
        if (added.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c未能加入任何 AI 士兵（该方基地未设置、名字用尽或对局已满）。"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已向 " + target.coloredName()
                + " §a补入 §e" + added.size() + " §a名 AI 士兵：§7" + String.join(", ", added)), true);
        return added.size();
    }

    /** {@code /aew1 bot spawn [n]}：在执行者位置裸生成 AI 士兵，用作管理命令的执行者。 */
    private static int botSpawnBare(CommandContext<CommandSourceStack> ctx, int count) {
        var src = ctx.getSource();
        var pos = src.getPosition();
        List<String> added = BotManager.INSTANCE.spawnBare(
                src.getServer(), src.getLevel(), pos.x, pos.y, pos.z, count);
        if (added.isEmpty()) {
            src.sendFailure(Component.literal("§c未能生成任何 AI 士兵。"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§a已生成 §e" + added.size()
                + " §a名 AI 士兵：§7" + String.join(", ", added)), true);
        return added.size();
    }

    private static int botRemove(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!BotManager.INSTANCE.despawn(ctx.getSource().getServer(), name)) {
            ctx.getSource().sendFailure(Component.literal("§c没有名为 " + name + " 的 AI 士兵。"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a已撤走 AI 士兵 §e" + name), true);
        return 1;
    }

    private static int botClear(CommandContext<CommandSourceStack> ctx) {
        int removed = BotManager.INSTANCE.despawnAll(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已撤走 §e" + removed + " §a名 AI 士兵。"), true);
        return removed;
    }

    private static int botList(CommandContext<CommandSourceStack> ctx) {
        List<String> names = BotManager.INSTANCE.activeNames();
        if (names.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7当前没有 AI 士兵。"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§eAI 士兵（" + names.size() + "）：§f" + String.join(", ", names)), false);
        return names.size();
    }

    private static int botDifficultyAll(CommandContext<CommandSourceStack> ctx,
                                        AimModel.Difficulty tier) {
        int n = BotManager.INSTANCE.setDifficultyForAll(tier);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已将 §e" + n
                + " §a名 AI 士兵的难度设为 §e" + tier.displayName()), true);
        return n;
    }

    private static int botDifficultyOne(CommandContext<CommandSourceStack> ctx,
                                        AimModel.Difficulty tier) {
        String name = StringArgumentType.getString(ctx, "name");
        if (!BotManager.INSTANCE.setDifficulty(name, tier)) {
            ctx.getSource().sendFailure(Component.literal("§c没有名为 " + name + " 的 AI 士兵。"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + name + " §a难度 → §e" + tier.displayName()), true);
        return 1;
    }
}
