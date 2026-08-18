package org.shee33.act0.battlefield.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住"界面代码不得硬编码命令串"。
 *
 * <p>命令根从 {@code /battlefield} 改成 {@code /aew1} 时，暂停菜单的"退出对局"与对局浏览器的
 * 加入/退出按钮仍在发旧命令。编译通过、测试全绿、服务端也不报错——玩家点按钮只是"没反应"，
 * 而当时对局中还禁用了其余命令，人实际被锁死在对局里出不去。
 *
 * <p>这类错误没有任何类型层面的抓手，只能从源码层面锁：凡是代玩家执行命令的地方，
 * 参数必须引用 {@link Aew1Command} 里的常量，不能是字符串字面量。
 */
class ClientCommandStringTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/org/shee33/act0/battlefield");

    /** {@code sendCommand(...)} 的实参；捕获到字符串字面量即视为违规。 */
    private static final Pattern SEND_COMMAND = Pattern.compile("sendCommand\\(\\s*\"([^\"]*)\"");

    @Test
    void noHardcodedCommandStringsInSendCommand() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaSources()) {
            Matcher m = SEND_COMMAND.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                offenders.add(file.getFileName() + " → \"" + m.group(1) + "\"");
            }
        }
        assertTrue(offenders.isEmpty(),
                "界面不得硬编码命令串，请改用 Aew1Command 里的常量，否则命令改名时这些按钮会静默失效：\n  "
                        + String.join("\n  ", offenders));
    }

    /** 全息终端的入口命令同样是代玩家执行的，必须挂在现有命令根下。 */
    @Test
    void hologramEntryCommandsUseTheRegisteredRoot() throws IOException {
        Path holograms = SOURCE_ROOT.resolve("hologram/BattlefieldEntranceHolograms.java");
        Matcher m = Pattern.compile("\\(\"[a-z_]+\", \"[^\"]*\", \"[^\"]*\", \"([^\"]*)\"\\)")
                .matcher(Files.readString(holograms, StandardCharsets.UTF_8));
        int checked = 0;
        while (m.find()) {
            String command = m.group(1);
            assertTrue(command.equals(Aew1Command.ROOT) || command.startsWith(Aew1Command.ROOT + " "),
                    "全息终端入口执行的命令不在 /" + Aew1Command.ROOT + " 下：" + command);
            checked++;
        }
        assertTrue(checked > 0, "未解析到任何全息终端入口，正则可能与源码格式脱节");
    }

    /** 对局中放行的命令必须真的以命令根开头，否则玩家会被锁在对局里。 */
    @Test
    void guiCommandConstantsLiveUnderTheRoot() {
        for (String command : List.of(Aew1Command.CMD_LEAVE, Aew1Command.CMD_SUICIDE,
                Aew1Command.CMD_QUICKJOIN, Aew1Command.CMD_BREAKTHROUGH_LEAVE,
                Aew1Command.CMD_BREAKTHROUGH_QUICKJOIN)) {
            assertTrue(command.startsWith(Aew1Command.ROOT + " "), "命令常量脱离命令根：" + command);
        }
        assertEquals("aew1", Aew1Command.ROOT, "改动命令根是玩家可见的破坏性变更，必须是刻意的");
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        }
    }
}
