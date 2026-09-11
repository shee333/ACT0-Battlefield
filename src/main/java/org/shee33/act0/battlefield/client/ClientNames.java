package org.shee33.act0.battlefield.client;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.shee33.act0.battlefield.integration.TaczGunBridge;

import javax.annotation.Nullable;

/**
 * 客户端物品显示名的二次解析。
 *
 * <p>地图军械库/配装里的显示名是在<b>服务器端</b>抓取的，服务器没有资源包语言文件，对用
 * {@link Component#translatable(String)} 显示名的物品只能拿到原始 key；而<b>某些模组物品的名字
 * 由 NBT 决定</b>（如 lrtactical 的近战：所有近战共用一个 {@code lrtactical:melee} 物品，具体是
 * 匕首/棒球棍/卡兰比特由 NBT 的 {@code MeleeWeaponId} 决定，裸物品只会回退到无翻译的通用 key
 * {@code item.lrtactical.melee}）。因此服务端上架时会把物品的<b>描述键</b>（
 * {@code ItemStack} 经 NBT 解析后的 {@code item.lrtactical.dagger} 之类）一并快照下来，客户端
 * 用它本地化，就能正确显示具体武器名。
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

    /** 物品注册 ID → 客户端本地化显示名（无服务端描述键时的回退路径）。 */
    public static String itemName(@Nullable String itemId) {
        return itemName(itemId, null);
    }

    /**
     * 物品注册 ID + 服务端描述键 → 客户端本地化显示名。
     *
     * <p>优先级：TaCZ 枪械索引（所有枪共用一个物品，裸 hover 名无意义）→ 服务端描述键
     * （含 NBT 驱动的具体键）→ 裸物品的 hover 名回退。
     */
    public static String itemName(@Nullable String itemId, @Nullable String displayKey) {
        if (itemId == null || itemId.isEmpty()) {
            return "空槽位";
        }
        String gunName = TaczGunBridge.clientGunDisplayName(itemId);
        if (gunName != null) {
            return gunName;
        }
        if (displayKey != null && !displayKey.isBlank()) {
            return Component.translatable(displayKey).getString();
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