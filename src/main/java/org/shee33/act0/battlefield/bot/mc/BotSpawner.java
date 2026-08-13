package org.shee33.act0.battlefield.bot.mc;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.shee33.act0.battlefield.bot.BotNames;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 士兵的生成与回收：把 bot 送进服务端玩家列表，或从中撤走。
 *
 * <p><b>为什么必须走 {@code placeNewPlayer} 而不是自己往世界里塞实体</b>：
 * {@code ArcadeMatch} 全程用 {@code server.getPlayerList().getPlayer(uuid)} 解析参战者——
 * 不在玩家列表里的 bot 会被一律视作离线玩家，既不计入热区占领、也收不到配装、更不会被伤害逻辑
 * 认可。走原版入场流程还顺带白拿了区块加载票据、实体追踪广播（其他玩家能正常看见它）
 * 与背包/属性初始化。
 *
 * <p>所有方法必须在服务器主线程调用。
 */
public final class BotSpawner {

    private BotSpawner() {
    }

    /**
     * 按名字生成一个 AI 士兵并登记进玩家列表。
     *
     * @param server 服务器实例
     * @param level  目标维度
     * @param name   bot 名，通常取自 {@link BotNames#pick}
     * @param x      生成坐标 X
     * @param y      生成坐标 Y
     * @param z      生成坐标 Z
     * @param yaw    初始偏航
     * @param pitch  初始俯仰
     * @return 生成的 bot；若同 UUID 者已在线则返回 {@code null}
     */
    @Nullable
    public static BotPlayer spawn(MinecraftServer server,
                                  ServerLevel level,
                                  String name,
                                  double x, double y, double z,
                                  float yaw, float pitch) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(name, "name");

        UUID uuid = BotNames.uuidOf(name);
        if (server.getPlayerList().getPlayer(uuid) != null) {
            // 同名 bot 已在场。UUID 由名字确定性派生，重复生成会顶掉前一个，属于调用方的逻辑错误。
            return null;
        }

        BotPlayer bot = new BotPlayer(server, level, new GameProfile(uuid, name));

        // 入场流程内部会自行 new 一个网络监听器，但其 send 最终都委托到本连接，
        // 因此在连接层吞包即可覆盖全部出站路径，无需 mixin 替换监听器。
        server.getPlayerList().placeNewPlayer(BotConnection.create(), bot);

        // placeNewPlayer 会把玩家放到世界出生点或其存档记录的位置，此处覆盖为调用方指定的落点。
        bot.teleportTo(level, x, y, z, yaw, pitch);
        bot.setGameMode(GameType.SURVIVAL);
        bot.setHealth(bot.getMaxHealth());
        return bot;
    }

    /**
     * 撤走一个 AI 士兵。
     *
     * <p>走原版 {@code remove} 以保证玩家列表、实体追踪、区块票据都被正确清理；
     * 其副作用是会把 bot 的数据落盘。由于 bot 的 UUID 由名字确定性派生
     * （见 {@link BotNames#uuidOf}），{@code playerdata/} 中的文件数上限就是名池大小，不会无限膨胀。
     *
     * @param server 服务器实例
     * @param bot    要撤走的 bot
     */
    public static void despawn(MinecraftServer server, BotPlayer bot) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(bot, "bot");
        server.getPlayerList().remove(bot);
    }

    /**
     * 该玩家是否为 AI 士兵。
     *
     * <p>供 HUD、记分板、击杀记录等信息界面加 BOT 标记用——战场上让 bot 看起来像士兵，
     * 但信息界面必须诚实。
     */
    public static boolean isBot(@Nullable ServerPlayer player) {
        return player instanceof BotPlayer;
    }
}
