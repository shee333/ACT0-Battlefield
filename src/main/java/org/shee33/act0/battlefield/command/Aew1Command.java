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

    /**
     * 界面代码需要代玩家执行的命令，集中在这里定义。
     *
     * <p><b>存在的唯一理由是让改名不可能改漏。</b>暂停菜单的"退出对局"、对局浏览器的加入/退出
     * 按钮都是拼一条命令发给服务端；把根名散写在各个界面里时，一次改名就会让这些按钮全部指向
     * 不存在的命令——而客户端发出的未知命令只会得到一句通用报错，按钮看起来就是"点了没反应"。
     * 这些都是编译期常量，引用它们不会把命令类拉进客户端类加载。
     */
    public static final String CMD_LEAVE = ROOT + " leave";
    public static final String CMD_QUICKJOIN = ROOT + " quickjoin";
    public static final String CMD_SUICIDE = ROOT + " suicide";
    public static final String CMD_BREAKTHROUGH_LEAVE = ROOT + " breakthrough leave";
    public static final String CMD_BREAKTHROUGH_QUICKJOIN = ROOT + " breakthrough quickjoin";

    private Aew1Command() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT);
        BattlefieldCommand.attachTo(root);
        root.then(BreakthroughCommand.tree());
        LoadoutCommand.attachTo(root);
        dispatcher.register(root);
    }
}
