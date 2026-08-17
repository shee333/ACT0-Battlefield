package org.shee33.act0.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * 本模组唯一的命令根 {@code /aew1}。
 *
 * <p>征服、突破、自杀、地图军械库四棵子树在这里合并成一次 {@code dispatcher.register}。
 * <b>只注册一次</b>是刻意的：Brigadier 允许对同名根重复 register 并做子节点合并，但那样一来
 * 节点归属就散在多个事件处理器里，任何一处改动都可能在无声中覆盖另一处的分支。
 */
public final class Aew1Command {

    public static final String ROOT = "aew1";

    private Aew1Command() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
        BattlefieldCommand.attachTo(root);
        root.then(BreakthroughCommand.tree());
        root.then(ArenaCommand.tree());
        dispatcher.register(root);
    }
}
