package org.shee33.act0.battlefield.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁住 {@link BattlefieldNetwork} 的包表，防止"改了包表却忘记 bump PROTOCOL"再次发生。
 *
 * <p>这类错误没有任何运行期征兆：两端协议版本字符串相同 → Forge 握手放行 → 玩家正常进服 →
 * 之后所有包按错位索引解码。0.1.74 就是这么把线上玩家踢下线的（详见 BattlefieldNetwork.PROTOCOL
 * 的注释）。所以只能在编译期用测试锁死。
 *
 * <p>不加载 {@code SimpleChannel} 而是解析源码文本：{@code registerMessage} 需要 Forge 运行时，
 * 纯 JUnit 环境跑不起来。
 */
class NetworkProtocolFingerprintTest {

    private static final Path NETWORK_DIR = Path.of("src/main/java/org/shee33/act0/battlefield/network");
    private static final Path SOURCE = NETWORK_DIR.resolve("BattlefieldNetwork.java");

    /** 改动包表或任何包的 payload 结构后，请连同 PROTOCOL 一起更新此处。 */
    private static final String EXPECTED_PROTOCOL = "19";

    /** 包表指纹（index:ClassName:DIRECTION 逐行拼接后的 SHA-256）。 */
    private static final String EXPECTED_FINGERPRINT =
            "fd893714ca74c34e84edd21d662e6070ac6165974c8408b1a0d433205b27fe95";

    /** 线格式指纹（network 包下每个文件的 buf.writeXxx / 嵌套 encode 调用序列）。 */
    private static final String EXPECTED_WIRE_FORMAT =
            "ce1008ecad45b4bcccd21dee06fef204c22d329a276e3723180d3439c253d4a5";

    /** 枚举常量指纹（network 包下所有枚举的常量顺序）。 */
    private static final String EXPECTED_ENUM_CONSTANTS =
            "7b6e5961584618acf786389d58d7346996311f5e6150e8f06e0fee784706031d";

    @Test
    void packetTableMatchesFingerprint() throws IOException {
        List<String> table = readPacketTable();
        assertTrue(table.size() > 10, "包表解析失败，只解析出 " + table.size() + " 项——正则可能与源码格式脱节");
        assertEquals(EXPECTED_FINGERPRINT, sha256(String.join("\n", table)),
                "\n\n包表已变更。任何增删包、调整注册顺序、改动包 payload 结构的行为都会破坏\n"
                        + "与旧客户端的兼容性，必须同时 bump BattlefieldNetwork.PROTOCOL 并更新本测试的\n"
                        + "EXPECTED_PROTOCOL / EXPECTED_FINGERPRINT 常量。\n\n"
                        + "当前包表：\n  " + String.join("\n  ", table) + "\n");
    }

    /**
     * 锁住线格式。包表指纹只覆盖 (索引, 类名, 方向)，对"包还是那个包、但字段变了"完全无感——
     * 而这正是 ACT0-Arcade 给 RoomDto 加四个字段却没 bump 的那类破坏。这里改抓每个文件里
     * {@code buf.writeXxx} 与嵌套 {@code .encode(buf)} 的调用序列，即真正的字节流布局：
     * 字段增删改一定会变，重命名局部变量或调整缩进则不会。
     */
    @Test
    void wireFormatMatchesFingerprint() throws IOException {
        List<String> wire = readWireFormat();
        assertTrue(wire.size() > 10, "线格式解析失败，只解析出 " + wire.size() + " 项");
        assertEquals(EXPECTED_WIRE_FORMAT, sha256(String.join("\n", wire)),
                "\n\n包的 payload 结构已变更（字段增删或类型改变），旧客户端会读错字节流。\n"
                        + "必须 bump BattlefieldNetwork.PROTOCOL 并更新本测试的 EXPECTED_PROTOCOL /\n"
                        + "EXPECTED_WIRE_FORMAT 常量。\n\n"
                        + "当前线格式：\n  " + String.join("\n  ", wire) + "\n");
    }

    /**
     * 锁住枚举常量顺序。{@code buf.writeEnum} 写的是<b>序号</b>，因此往枚举中间增删一个常量
     * 就是一次实打实的线格式变更——但它既不改包表、也不改 {@code buf.writeXxx} 的调用序列，
     * 上面两个指纹对它完全无感。0.2.x 移除 {@code Action.OPEN_LOADOUT} 时正是这样悄悄溜过去的：
     * 旧客户端发 {@code OPEN} 之后的任何动作，新服务端都会解成错位的另一个动作。
     */
    @Test
    void enumConstantsMatchFingerprint() throws IOException {
        List<String> enums = readEnumConstants();
        assertTrue(!enums.isEmpty(), "枚举解析失败，一个都没解析出来——正则可能与源码格式脱节");
        assertEquals(EXPECTED_ENUM_CONSTANTS, sha256(String.join("\n", enums)),
                "\n\n包内枚举的常量顺序已变更。writeEnum 写的是序号，增删常量会让旧客户端把动作\n"
                        + "解码成错位的另一个值，必须 bump BattlefieldNetwork.PROTOCOL 并更新本测试的\n"
                        + "EXPECTED_PROTOCOL / EXPECTED_ENUM_CONSTANTS 常量。\n\n"
                        + "当前枚举：\n  " + String.join("\n  ", enums) + "\n");
    }

    @Test
    void protocolMatchesExpected() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("String\\s+PROTOCOL\\s*=\\s*\"([^\"]+)\"").matcher(src);
        assertTrue(m.find(), "未能在源码中定位 PROTOCOL 常量");
        assertEquals(EXPECTED_PROTOCOL, m.group(1),
                "PROTOCOL 与测试期望不一致——若为有意 bump，请同步更新 EXPECTED_PROTOCOL");
    }

    private static List<String> readPacketTable() throws IOException {
        String src = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int start = src.indexOf("public static void register()");
        assertTrue(start >= 0, "未能在源码中定位 register() 方法");
        String body = src.substring(start);

        // non-greedy 使每个 registerMessage 与其后最近的一个 NetworkDirection 配对。
        Matcher m = Pattern.compile(
                        "registerMessage\\(id\\+\\+,\\s*(\\w+)\\.class.*?NetworkDirection\\.(PLAY_TO_CLIENT|PLAY_TO_SERVER)",
                        Pattern.DOTALL)
                .matcher(body);
        List<String> table = new ArrayList<>();
        int index = 0;
        while (m.find()) {
            table.add(index++ + ":" + m.group(1) + ":" + m.group(2));
        }
        return table;
    }

    private static final Pattern WIRE_TOKEN =
            Pattern.compile("buf\\.(write\\w+)\\(|(\\w+)\\.encode\\(buf\\)");

    private static List<String> readWireFormat() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path file : networkSources()) {
            Matcher m = WIRE_TOKEN.matcher(Files.readString(file, StandardCharsets.UTF_8));
            List<String> tokens = new ArrayList<>();
            while (m.find()) {
                tokens.add(m.group(1) != null ? m.group(1) : "encode:" + m.group(2));
            }
            if (!tokens.isEmpty()) {
                lines.add(file.getFileName() + "=" + String.join(",", tokens));
            }
        }
        return lines;
    }

    private static final Pattern ENUM_BODY =
            Pattern.compile("enum\\s+(\\w+)\\s*\\{([^}]*)\\}", Pattern.DOTALL);

    private static List<String> readEnumConstants() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path file : networkSources()) {
            Matcher m = ENUM_BODY.matcher(stripComments(Files.readString(file, StandardCharsets.UTF_8)));
            while (m.find()) {
                List<String> constants = new ArrayList<>();
                for (String token : m.group(2).split("[,;]")) {
                    String name = token.trim();
                    if (name.matches("[A-Z][A-Z0-9_]*")) {
                        constants.add(name);
                    }
                }
                if (!constants.isEmpty()) {
                    lines.add(file.getFileName() + ":" + m.group(1) + "=" + String.join(",", constants));
                }
            }
        }
        return lines;
    }

    /** 去掉注释，避免 javadoc 里提到的枚举名混进指纹——改注释不该让线格式指纹变红。 */
    private static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static List<Path> networkSources() throws IOException {
        try (Stream<Path> stream = Files.list(NETWORK_DIR)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static String sha256(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 未提供 SHA-256", e);
        }
    }
}
