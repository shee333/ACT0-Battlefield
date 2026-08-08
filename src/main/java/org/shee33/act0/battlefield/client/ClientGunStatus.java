package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.shee33.act0.battlefield.integration.TaczGunBridge;

/**
 * 武器信息块的数据源：弹药文本与换弹进度，优先取 TaCZ 真实数据，无 TaCZ 时回退到原版语义。
 *
 * <p>备弹需要遍历整个背包做反射判定，每帧算一次太贵（TaCZ 官方 HUD 同样是缓存的），这里按
 * {@link #RESERVE_TTL_MS} 节流；弹匣数是单次反射，每帧实时取，保证开火时数字立刻跳。
 */
final class ClientGunStatus {

    private static final long RESERVE_TTL_MS = 200L;

    private static int cachedReserve = -1;
    private static long reserveComputedAtMs;
    private static int reserveCachedSlot = -1;

    /** 本次换弹观测到的最大剩余时间，用作进度条分母（TaCZ 不下发总时长）。 */
    private static long reloadTotalMs;
    private static boolean wasReloading;

    private ClientGunStatus() {
    }

    static void clear() {
        cachedReserve = -1;
        reserveCachedSlot = -1;
        reserveComputedAtMs = 0L;
        reloadTotalMs = 0L;
        wasReloading = false;
    }

    /**
     * 弹药位文本。TaCZ 枪械显示 {@code 弹匣/备弹}；非枪按原版语义回退（可堆叠 {@code ×N}、
     * 有耐久显示剩余耐久、其余 {@code ∞}）。
     */
    static String ammoText(LocalPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return "—";
        }
        if (TaczGunBridge.isGun(stack)) {
            int mag = TaczGunBridge.currentAmmo(stack);
            if (mag >= 0) {
                // 与 TaCZ 官方 HUD 同口径：闭膛枪械把已上膛那发计入弹匣数，开膛枪械不计
                // （开膛没有独立的膛内一发）。
                if (TaczGunBridge.hasBulletInBarrel(stack) && !TaczGunBridge.isOpenBolt(stack)) {
                    mag += 1;
                }
                int reserve = reserveFor(player, stack);
                return reserve < 0 ? String.valueOf(mag) : mag + " / " + reserve;
            }
        }
        if (stack.getCount() > 1) {
            return "×" + stack.getCount();
        }
        if (stack.isDamageableItem()) {
            return String.valueOf(stack.getMaxDamage() - stack.getDamageValue());
        }
        return "∞";
    }

    /**
     * 缓存键用<b>快捷栏槽位</b>而不是 ItemStack 内容比对。TaCZ 把余弹存在物品 NBT 里，每开一枪
     * NBT 就变，用 {@code isSameItemSameTags} 做键会导致射击期间每帧都失配，进而每帧对整个背包
     * 做一遍反射扫描——缓存等于没有。槽位变了(换枪)才必须立刻重算，其余情况按 TTL 节流即可。
     */
    private static int reserveFor(LocalPlayer player, ItemStack stack) {
        long now = System.currentTimeMillis();
        int slot = player.getInventory().selected;
        if (slot == reserveCachedSlot && now - reserveComputedAtMs < RESERVE_TTL_MS) {
            return cachedReserve;
        }
        cachedReserve = TaczGunBridge.reserveAmmo(player, stack);
        reserveCachedSlot = slot;
        reserveComputedAtMs = now;
        return cachedReserve;
    }

    /**
     * 信息块底部进度条的填充比例 0..1。
     *
     * <p>TaCZ 枪械换弹中时是真实换弹进度；否则回退到物品冷却（原版里唯一真实的"这把武器正忙"
     * 信号）。两者都没有时返回 0，条不显示——不伪造进度。
     */
    static float progress() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return 0f;
        }
        if (TaczGunBridge.isReloading(player)) {
            long remaining = TaczGunBridge.reloadCountDownMs(player);
            if (remaining > 0L) {
                if (!wasReloading || remaining > reloadTotalMs) {
                    reloadTotalMs = remaining;
                }
                wasReloading = true;
                return 1f - Math.max(0f, Math.min(1f, remaining / (float) reloadTotalMs));
            }
        }
        wasReloading = false;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return 0f;
        }
        float remaining = player.getCooldowns().getCooldownPercent(stack.getItem(), mc.getFrameTime());
        return remaining <= 0f ? 0f : 1f - remaining;
    }
}
