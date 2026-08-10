package org.shee33.act0.battlefield.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * 医疗针：点击倒地的同阵营玩家，以 3 倍于常规的速度将其救起。
 *
 * <p>左键与右键都接：左键走 {@link #onLeftClickEntity} 并返回 {@code true} 吞掉这次攻击——否则
 * 对着倒地队友按左键会先被当成一次攻击处理。
 *
 * <p>与常规救援（按住救援键 + 客户端持续心跳）不同，医疗针是<b>一次点击即开始</b>，之后由服务端
 * 自行推进；救援期间仍逐 tick 复核距离与朝向，走开就中断。
 */
public final class MedicSyringeItem extends Item {

    public MedicSyringeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        return tryRevive(player, target) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    @Override
    public boolean onLeftClickEntity(ItemStack stack, Player player, Entity entity) {
        return entity instanceof LivingEntity living && tryRevive(player, living);
    }

    private static boolean tryRevive(Player player, LivingEntity target) {
        if (player.level().isClientSide || !(player instanceof ServerPlayer reviver)
                || !(target instanceof ServerPlayer downed)) {
            return false;
        }
        return Act0Battlefield.manager().handleSyringeRevive(reviver, downed)
                || Act0Battlefield.BREAKTHROUGH_MANAGER.handleSyringeRevive(reviver, downed);
    }
}
