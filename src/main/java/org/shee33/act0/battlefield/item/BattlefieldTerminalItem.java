package org.shee33.act0.battlefield.item;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * 战地终端：玩家右键即可打开大战场加入/状态界面，不需要输入命令。
 *
 * <p>这是给普通玩家的低门槛入口；管理员布场指令仍保留作为后台工具。
 */
public final class BattlefieldTerminalItem extends Item {

    public BattlefieldTerminalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            Act0Battlefield.manager().openFor(serverPlayer);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
