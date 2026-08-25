package org.shee33.act0.battlefield.match;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.BattlefieldConfig;
import org.shee33.act0.battlefield.data.BattlefieldData;

import javax.annotation.Nullable;

/**
 * 对局结束 / 中途退出后把玩家送回指定地点的返回逻辑。
 *
 * <p>返回位置按优先级：
 * <ol>
 *   <li><b>地图返回点</b>：管理员用 {@code /aew1 map returnpoint} 站在某处设置（存在
 *       该图 {@link BattlefieldData} 里），对局结束传送到该点所在维度与坐标；</li>
 *   <li><b>配置覆盖</b>：{@code config/act0_battlefield-server.toml} 的 {@code matchReturn}
 *       段开启 {@code useCustomCoords} 时，用配置的自定义坐标（主世界）；</li>
 *   <li><b>默认</b>：主世界（overworld）出生点。</li>
 * </ol>
 *
 * <p>使用 {@link ServerPlayer#teleportTo(ServerLevel, double, double, double, float, float)}
 * 做跨维度传送（与原有 quitPlayer/end 的写法一致，对无连接的 AI 士兵同样安全）。
 */
final class MatchReturnLocation {

    private MatchReturnLocation() {
    }

    /** 把玩家送回返回点，并同步其重生点，防止中途阵亡后回不到返回点。 */
    static void returnToMainWorld(ServerPlayer player, @Nullable BattlefieldData mapData) {
        BattlefieldData.ReturnPoint rp = mapData == null ? null : mapData.returnPoint();
        if (rp != null) {
            ServerLevel target = resolveDimension(player, rp.dimension());
            if (target != null) {
                teleportAndSetRespawn(player, target, rp.x(), rp.y(), rp.z(), rp.yaw(), rp.pitch());
                return;
            }
        }
        ServerLevel overworld = player.server.overworld();
        if (BattlefieldConfig.MATCH_RETURN_USE_CUSTOM_COORDS.get()) {
            teleportAndSetRespawn(player, overworld,
                    BattlefieldConfig.MATCH_RETURN_X.get(),
                    BattlefieldConfig.MATCH_RETURN_Y.get(),
                    BattlefieldConfig.MATCH_RETURN_Z.get(),
                    BattlefieldConfig.MATCH_RETURN_YAW.get().floatValue(),
                    BattlefieldConfig.MATCH_RETURN_PITCH.get().floatValue());
            return;
        }
        BlockPos spawn = overworld.getSharedSpawnPos();
        teleportAndSetRespawn(player, overworld,
                spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                overworld.getSharedSpawnAngle(), 0.0F);
    }

    private static void teleportAndSetRespawn(ServerPlayer player, ServerLevel level,
                                              double x, double y, double z, float yaw, float pitch) {
        player.teleportTo(level, x, y, z, yaw, pitch);
        player.setRespawnPosition(level.dimension(), BlockPos.containing(x, y, z), yaw, true, false);
    }

    @Nullable
    private static ServerLevel resolveDimension(ServerPlayer player, String location) {
        net.minecraft.resources.ResourceLocation loc = net.minecraft.resources.ResourceLocation.tryParse(location);
        if (loc == null) {
            return null;
        }
        return player.server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, loc));
    }
}
