package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 当前手持物的冷却进度，驱动武器信息块底部那条金色进度条。
 *
 * <p>规格 §4.2 那条是"打空自动换弹"进度条，但本模组没有弹匣/换弹系统。物品冷却
 * （{@code ItemCooldowns}，如末影珍珠、盾牌、投掷物）是 MC 里唯一真实存在的"这把武器正忙"
 * 信号，语义与换弹一致（都是"暂时不能再用，等条走完"），因此改由它驱动。没有冷却时返回 0，
 * 条不显示——不伪造一个玩家等不到结果的进度。
 */
final class ClientWeaponCooldown {

    private ClientWeaponCooldown() {
    }

    /** @return 0..1；0 表示当前没有冷却。 */
    static float progress() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return 0f;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return 0f;
        }
        float remaining = player.getCooldowns().getCooldownPercent(stack.getItem(), mc.getFrameTime());
        // getCooldownPercent 返回的是"剩余比例"，进度条要的是"已完成比例"。
        return remaining <= 0f ? 0f : 1f - remaining;
    }
}
