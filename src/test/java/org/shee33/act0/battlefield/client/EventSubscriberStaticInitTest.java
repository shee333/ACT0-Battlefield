package org.shee33.act0.battlefield.client;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 源码级 lint：禁止在静态初始化里解析 {@code VanillaGuiOverlay.XXX.type()}。
 *
 * <p>带 {@code @Mod.EventBusSubscriber} 的类会在 mod CONSTRUCT 阶段就被加载以注册事件方法，
 * 而原版覆盖层的 {@code NamedGuiOverlay} 实例要等 Forge 触发 {@code RegisterGuiOverlaysEvent}
 * 才被赋值。此刻 {@code type()} 全是 null，喂给 {@code Set.of} 会 NPE，直接让整个 mod 构造
 * 失败、客户端起不来。
 *
 * <p>这个坑真实发生过：{@code VanillaHudSuppressor} 因此让 0.1.81~0.1.87 的客户端全部无法启动。
 * 它属于 Forge 生命周期时序问题，纯 JUnit 起不了 Forge、跑不出来，只能从源码层面拦截——
 * 正确写法是存枚举常量本身、到事件期再解析 {@code type()}。
 */
class EventSubscriberStaticInitTest {

    private static final Path CLIENT_DIR = Path.of("src/main/java/org/shee33/act0/battlefield/client");

    /** 匹配 {@code static final ... = ... VanillaGuiOverlay.X.type() ...}（含跨行初始化）。 */
    private static final Pattern EAGER_OVERLAY_TYPE = Pattern.compile(
            "static\\s+final[^;=]*=[^;]*VanillaGuiOverlay\\.\\w+\\.type\\(\\)", Pattern.DOTALL);

    @Test
    void noStaticFieldResolvesVanillaOverlayType() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(CLIENT_DIR)) {
            files = stream.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = EAGER_OVERLAY_TYPE.matcher(src);
            if (m.find()) {
                offenders.add(file.getFileName().toString());
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下类在静态初始化里解析了 VanillaGuiOverlay.type()，会在 mod CONSTRUCT 阶段拿到 null "
                        + "并让客户端启动失败：" + offenders
                        + "\n改为存枚举常量本身，到事件回调里再调 type() 比较。");
    }

    /** lint 本身必须真的能匹配到问题写法，否则它只是个永远绿的摆设。 */
    @Test
    void lintPatternActuallyMatchesTheBrokenForm() {
        String broken = "    private static final Set<NamedGuiOverlay> S = Set.of(\n"
                + "            VanillaGuiOverlay.CROSSHAIR.type(),\n"
                + "            VanillaGuiOverlay.HOTBAR.type());";
        assertTrue(EAGER_OVERLAY_TYPE.matcher(broken).find(), "lint 正则漏掉了真实崩溃写法");

        String fixed = "    private static final Set<VanillaGuiOverlay> S = EnumSet.of(\n"
                + "            VanillaGuiOverlay.CROSSHAIR,\n"
                + "            VanillaGuiOverlay.HOTBAR);";
        assertTrue(!EAGER_OVERLAY_TYPE.matcher(fixed).find(), "lint 正则误报了正确写法");
    }
}
