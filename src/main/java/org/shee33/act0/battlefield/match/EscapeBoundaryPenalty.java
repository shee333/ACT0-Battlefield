package org.shee33.act0.battlefield.match;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * 离开作战区域超时的惩戒：击杀（BF 系列同款"逃兵击毙"）。
 *
 * <p>用自定义伤害类型 {@code act0_battlefield:desertion}（见
 * {@code data/act0_battlefield/damage_type/desertion.json} + 语言文件），让死亡消息是
 * "X 逃离了战场"而不是通用的"X 被杀死了"——战场上突兀的通用提示会破坏沉浸感，
 * 而这条消息本身也要向全队传达"有人逃了"这一战术信息。
 *
 * <p><b>绝不向上抛异常</b>：惩戒路径在每 tick 的服务端循环里，一旦抛异常会连带整个
 * 对局 tick 崩掉。数据包未加载 / 注册表查不到时降级到原版 {@code genericKill}。
 */
final class EscapeBoundaryPenalty {

    private EscapeBoundaryPenalty() {
    }

    /** 以"逃离战场"为由击杀该玩家。 */
    static void killDesertion(ServerPlayer player) {
        player.hurt(desertionSource(player), Float.MAX_VALUE);
        if (player.isAlive()) {
            // 兜底：自定义伤害被无敌帧 / 免疫挡掉时，确保惩戒一定生效。
            player.kill();
        }
    }

    private static DamageSource desertionSource(ServerPlayer player) {
        try {
            ResourceKey<DamageType> key = ResourceKey.create(Registries.DAMAGE_TYPE,
                    new ResourceLocation(Act0Battlefield.MODID, "desertion"));
            return new DamageSource(player.serverLevel().registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(key));
        } catch (Throwable t) {
            return player.damageSources().genericKill();
        }
    }
}
