package org.shee33.act0.battlefield.match;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.BattlefieldConfig;

/**
 * 对局结束 / 中途退出后把玩家送回主世界（overworld）的返回位置计算。
 *
 * <p>原先对局结束与中途退出都回到"开局时所在世界（大厅）的阵营基地"，而玩家真正想去的
 * 是主世界。这里统一改为：默认传送到<b>主世界出生点</b>；服主若在
 * {@code config/act0_battlefield-server.toml} 的 {@code matchReturn} 段开启了
 * {@code useCustomCoords}，则改用配置的自定义坐标（同样落在主世界）。
 *
 * <p>使用 {@link ServerPlayer#teleportTo(ServerLevel, double, double, double, float, float)}
 * 做跨维度传送（与原有 quitPlayer/end 的写法一致，对无连接的 AI 士兵同样安全）。
 */
final class MatchReturnLocation {

    private MatchReturnLocation() {
    }

    /** 把玩家送回主世界，并同步其重生点，防止中途阵亡后回不到出生点。 */
    static void returnToMainWorld(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        if (BattlefieldConfig.MATCH_RETURN_USE_CUSTOM_COORDS.get()) {
            x = BattlefieldConfig.MATCH_RETURN_X.get();
            y = BattlefieldConfig.MATCH_RETURN_Y.get();
            z = BattlefieldConfig.MATCH_RETURN_Z.get();
            yaw = BattlefieldConfig.MATCH_RETURN_YAW.get().floatValue();
            pitch = BattlefieldConfig.MATCH_RETURN_PITCH.get().floatValue();
        } else {
            BlockPos spawn = overworld.getSharedSpawnPos();
            x = spawn.getX() + 0.5;
            y = spawn.getY();
            z = spawn.getZ() + 0.5;
            yaw = overworld.getSharedSpawnAngle();
            pitch = 0.0F;
        }
        player.teleportTo(overworld, x, y, z, yaw, pitch);
        player.setRespawnPosition(overworld.dimension(), BlockPos.containing(x, y, z), yaw, true, false);
    }
}
