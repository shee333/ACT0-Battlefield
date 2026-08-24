package org.shee33.act0.battlefield.data;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 地图主键的唯一推导处：把一个世界解析成 {@link PlayerLoadoutStore} 使用的地图名。
 *
 * <p><b>为什么需要兜底</b>：{@code BattlefieldData.mapName} 是管理员可选设置的展示名，可能压根没设。
 * 但武器目录必须有个稳定的键，否则未命名的图一件武器都存不下。因此未命名时回落到维度 ID
 * （形如 {@code minecraft:overworld}）——它必定唯一且稳定，管理员之后再命名也只是多出一个键，
 * 不会污染已有数据。
 *
 * <p><b>为什么不持久化一张 mapName↔dimension 映射表</b>：那张表会与 {@code BattlefieldData.mapName}
 * 形成两个真相源，改名时必然漏同步。现推现算，一次遍历所有已加载世界的成本可以忽略。
 *
 * <p><b>对局跑在临时维度时的注意事项</b>：用地图模板开局会把对局放进一个新建维度，那个维度的
 * 每维度存档是空的，{@link #of} 会给出维度 ID 而不是管理员配置武器池时用的图名。因此对局必须在
 * {@code start()} 时从<b>大厅世界</b>解析一次主键并带着走，不能在对局世界里现算。
 */
public final class ArenaKey {

    private ArenaKey() {
    }

    /** 该世界对应的地图主键：管理员命名过就用地图名，否则用维度 ID。 */
    public static String of(ServerLevel level) {
        String name = BattlefieldData.get(level).mapName().trim();
        return name.isEmpty() ? level.dimension().location().toString() : name;
    }

    /**
     * 全服已知的地图名：所有已加载世界的主键，加上目录里已配置过但世界当前未加载的名字。
     *
     * <p>顺序上世界优先——管理员大概率是在给眼前这些图配武器，补全列表里它们该排在前面。
    /**
     * 全服已知的地图名：所有已加载世界的主键（管理员命名过的用地图名，否则用维度 ID）。
     *
     * <p>顺序上世界优先——管理员大概率是在给眼前这些图配装备。
     */
    public static List<String> knownNames(MinecraftServer server) {
        List<String> out = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            out.add(of(level));
        }
        return out;
    }

    /**
     * 把地图主键反查回它所属的世界；未加载返回 {@code null}。
     *
     * <p>配装预设存在每维度 {@link BattlefieldData} 里，而主键可能来自对局启动时的大厅世界——
     * 对局期间需要拿主键找回配置了该地图的世界才能读到预设。
     */
    @Nullable
    public static ServerLevel levelFor(MinecraftServer server, @Nullable String arenaKey) {
        if (arenaKey == null || arenaKey.isBlank()) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (of(level).equals(arenaKey)) {
                return level;
            }
        }
        return null;
    }

    /**
     * 把用户输入的地图名解析成规范键（忽略大小写）；不是已知地图则返回 {@code null}。
     *
     * <p>拒绝未知名字是刻意的：否则打错一个字就会静默生成一套没有任何世界会去读的武器池，
     * 管理员配了半天却在游戏里一件都看不到。
     */
    @Nullable
    public static String resolve(MinecraftServer server, @Nullable String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        List<String> known = knownNames(server);
        for (String name : known) {
            if (name.equals(trimmed)) {
                return name;
            }
        }
        for (String name : known) {
            if (name.equalsIgnoreCase(trimmed)) {
                return name;
            }
        }
        return null;
    }
}
