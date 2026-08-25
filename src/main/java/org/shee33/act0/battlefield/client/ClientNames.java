package org.shee33.act0.battlefield.client;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.shee33.act0.battlefield.integration.TaczGunBridge;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端物品显示名的二次解析。
 *
 * <p>地图军械库里的显示名是在<b>服务器端</b>用 {@code ItemStack.getHoverName().getString()} 抓取的。
 * 服务器没有语言包，对 TaCZ 这类用 {@link Component#translatable(String)} 显示名的物品，取到的是
 * 未翻译的原始 key（形如 {@code item.tacz.ak47}），直接画到部署/配装界面上就是一团
 * {@code item.tacz.xxxx}。
 *
 * <p>客户端持有完整的语言包（TaCZ 的 lang 已加载），用 {@code Component.translatable(name)} 再解析一次：
 * 若 name 是翻译 key 就得到本地化名称；若本来就是个字面量（找不到对应 key），translatable 会原样
 * 返回 key 本身——两条路径都不会出错，可无脑套用。
 */
public final class ClientNames {

    private ClientNames() {
    }

    /** 把可能未翻译的显示名解析为玩家可读文本。空串原样返回。 */
    public static String resolve(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Component.translatable(name).getString();
    }

    /** 物品注册 ID → 客户端本地化显示名（客户端持有语言包，取 ItemStack 的 hover 名）。 */
    public static String itemName(@Nullable String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return "空槽位";
        }
        // TaCZ 枪械 ID（tacz:xxx）优先走客户端枪械索引：所有枪共用同一个物品，
        // 靠物品 hover 名只能得到 item.tacz.modern_kinetic_gun，不是枪的真实型号。
        String gunName = TaczGunBridge.clientGunDisplayName(itemId);
        if (gunName != null) {
            return gunName;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        net.minecraft.world.item.Item item = id == null ? null
                : net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            return itemId;
        }
        return new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
    }
}
