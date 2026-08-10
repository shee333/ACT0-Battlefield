package org.shee33.act0.battlefield.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.core.SupplyRules;
import org.shee33.act0.battlefield.deployable.DeployableKind;

/**
 * 可部署补给箱物品基类：右键把箱子朝视线前方抛出。
 *
 * <p>物品本身<b>不消耗</b>，改用与箱子存活时长等长的冷却。这样同一名玩家场上同时最多只有一个
 * 自己部署的箱子（旧箱到期的那一刻冷却刚好结束），既避免了满地铺箱，也不会让配装槽里的装置用
 * 一次就没——装置槽在战地里本来就是"有冷却的可重复使用能力"，不是消耗品。
 */
public abstract class DeployableBoxItem extends Item {

    private final DeployableKind kind;

    protected DeployableBoxItem(Properties properties, DeployableKind kind) {
        super(properties);
        this.kind = kind;
    }

    /** 部署到世界后作为箱体显示的掉落物模型。 */
    protected abstract ItemStack displayStack();

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide);
        }
        boolean deployed = Act0Battlefield.manager().handleDeployGadget(serverPlayer, kind, displayStack())
                || Act0Battlefield.BREAKTHROUGH_MANAGER.handleDeployGadget(serverPlayer, kind, displayStack());
        if (!deployed) {
            serverPlayer.displayClientMessage(Component.literal("§7只能在对局中部署"), true);
            return InteractionResultHolder.fail(held);
        }
        player.getCooldowns().addCooldown(this, SupplyRules.LIFETIME_TICKS);
        return InteractionResultHolder.consume(held);
    }
}
