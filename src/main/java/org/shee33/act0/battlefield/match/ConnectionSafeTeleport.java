package org.shee33.act0.battlefield.match;

import net.minecraft.server.level.ServerPlayer;

/**
 * 对没有客户端连接的 {@link ServerPlayer} 也安全的位置/朝向写入。
 *
 * <p><b>为什么需要它。</b>本模组在三处强制修正玩家的位置或朝向——部署镜头平移、倒地时看向击杀者、
 * 倒地防上浮的 Y 钳制——都直接调 {@code player.connection.teleport(...)}。这对真人没问题，但
 * AI 士兵是服务端伪造的 {@code ServerPlayer}，{@code connection} 为 {@code null}，这三处会每 tick
 * 抛 NPE，bot 连重新部署都走不完。
 *
 * <p><b>为什么不是简单跳过。</b>跳过对镜头平移无害（那本就是表现层），但倒地 Y 钳制一旦跳过，
 * 倒地的 bot 会顺着速度一路上浮——那是可见缺陷。因此这里改为"有连接走原路，没有连接退回服务端
 * 写入"，两条路的语义一致。
 *
 * <p><b>对真人的行为完全不变</b>：有连接时调用的仍是同一个
 * {@code connection.teleport(...)}，参数原样传递。
 */
final class ConnectionSafeTeleport {

    private ConnectionSafeTeleport() {
    }

    /**
     * 把玩家强制移动到给定位置与朝向。
     *
     * <p>无连接时退回 {@link net.minecraft.world.entity.Entity#moveTo(double, double, double, float, float)}
     * ——它同样是服务端权威写入，只是不会向客户端补发确认包（伪玩家没有客户端，本就不需要）。
     */
    static void teleport(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        if (player.connection != null) {
            player.connection.teleport(x, y, z, yaw, pitch);
            return;
        }
        player.moveTo(x, y, z, yaw, pitch);
    }
}
