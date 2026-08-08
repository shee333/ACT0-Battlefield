package org.shee33.act0.battlefield.network;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Path SOURCE =
            Path.of("src/main/java/org/shee33/act0/battlefield/network/BattlefieldNetwork.java");

    /** 改动包表后，请连同 PROTOCOL 一起更新此处。 */
    private static final String EXPECTED_PROTOCOL = "10";

    /** 包表指纹（index:ClassName:DIRECTION 逐行拼接后的 SHA-256）。 */
    private static final String EXPECTED_FINGERPRINT =
            "c99e98cabf320e7eaf8fd7515ca36c28b55523f8c5846639db9d579d64f4c9a6";

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
