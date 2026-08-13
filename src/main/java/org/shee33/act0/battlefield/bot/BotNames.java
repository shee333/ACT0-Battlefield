package org.shee33.act0.battlefield.bot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * AI 士兵的身份来源：真人风格假名池 + 由名字确定性派生的 UUID。MC-free，可单测。
 *
 * <p><b>为什么用真人风格名而不是 {@code BOT_01}</b>：战场上的悬浮名牌会持续出现在玩家视野里，
 * {@code BOT_01} 等于每一秒都在提醒玩家"你在打假人"，破坏沉浸感。名字看起来像士兵，
 * 是否为 AI 交由 tab 列表与击杀记录上的独立标记诚实告知——战斗中沉浸，信息界面上不欺骗。
 *
 * <p><b>为什么 UUID 由名字派生而非随机</b>：{@code PlayerList.remove} 会把玩家数据落盘成
 * {@code .dat}。若每次生成 bot 都用随机 UUID，世界存档里的 {@code playerdata/} 会无限膨胀。
 * 由名字派生后，{@code .dat} 文件数上限就是名池大小（{@value #POOL_SIZE} 个），且 bot 身份
 * 跨服务器重启稳定，便于日后接入战绩统计。
 *
 * <p>前缀 {@value #UUID_NAMESPACE} 与原版离线玩家的 {@code OfflinePlayer:} 命名空间不同，
 * 因此即便离线服中存在同名真人玩家，两者的 UUID 也不会碰撞。
 */
public final class BotNames {

    /** UUID 派生命名空间，刻意区别于原版的 {@code OfflinePlayer:}，避免与离线真人玩家碰撞。 */
    public static final String UUID_NAMESPACE = "ACT0Bot:";

    /** 名池容量，同时也是 {@code playerdata/} 中 bot 存档文件数量的上限。 */
    public static final int POOL_SIZE = 32;

    /**
     * 真人风格士兵名池。
     *
     * <p>约束：仅 ASCII 字母，长度 3~16，符合原版 {@code GameProfile} 对玩家名的合法字符要求，
     * 避免客户端渲染名牌或 tab 列表时出现异常。刻意取多国来源，贴合战地系列的国际战场设定。
     */
    private static final List<String> POOL = List.of(
            "Novak", "Ivanov", "Petrov", "Kowalski",
            "Fischer", "Lindqvist", "Moreau", "Bianchi",
            "Okafor", "Haddad", "Nakamura", "Vasquez",
            "Sokolov", "Dubois", "Weber", "Mercier",
            "Rahman", "Tanaka", "Reyes", "Novotny",
            "Larsen", "Wolfe", "Baranov", "Ferreira",
            "Holt", "Marchetti", "Duarte", "Keller",
            "Brennan", "Sorensen", "Aliyev", "Castellan");

    private BotNames() {
    }

    /** 只读名池视图。 */
    public static List<String> pool() {
        return POOL;
    }

    /** 名池容量。 */
    public static int size() {
        return POOL.size();
    }

    /** 按索引取名，越界时环绕，便于调用方无需自行取模。 */
    public static String at(int index) {
        return POOL.get(Math.floorMod(index, POOL.size()));
    }

    /**
     * 由名字确定性派生 UUID：同名恒定同 UUID，跨进程、跨重启稳定。
     *
     * @param name bot 名字，不可为 {@code null}
     */
    public static UUID uuidOf(String name) {
        Objects.requireNonNull(name, "name");
        return UUID.nameUUIDFromBytes((UUID_NAMESPACE + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 挑选若干互不重复、且不与已占用名冲突的 bot 名。
     *
     * <p>用于开局补 bot：{@code taken} 传入当前在线玩家名与已在场 bot 名，避免 tab 列表重名
     * （原版允许重名但客户端记分板与名牌会混淆，属于会破坏体验的细节）。
     *
     * <p>从随机起点开始环绕扫描而非每次随机重试，保证可用名不足时也能确定性终止。
     *
     * @param count 需要的数量；{@code <= 0} 返回空列表
     * @param taken 已占用的名字（大小写不敏感）；{@code null} 视为空集
     * @param seed  随机起点种子，相同种子结果可复现，便于测试
     * @return 实际挑到的名字，数量可能少于 {@code count}（名池被占满时）
     */
    public static List<String> pick(int count, Set<String> taken, long seed) {
        if (count <= 0) {
            return List.of();
        }
        Set<String> occupied = taken == null ? Set.of() : taken;
        List<String> picked = new ArrayList<>(Math.min(count, POOL.size()));
        int start = Math.floorMod(new Random(seed).nextInt(), POOL.size());
        for (int i = 0; i < POOL.size() && picked.size() < count; i++) {
            String candidate = at(start + i);
            if (!containsIgnoreCase(occupied, candidate)) {
                picked.add(candidate);
            }
        }
        return List.copyOf(picked);
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        for (String s : set) {
            if (s != null && s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
