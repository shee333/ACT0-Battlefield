package org.shee33.act0.battlefield.loadout;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.shee33.act0.battlefield.bot.mc.BotSpawner;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog;
import org.shee33.act0.battlefield.core.arena.ArenaItemEntry;
import org.shee33.act0.battlefield.core.arena.ArenaWeaponEntry;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.PlayerArenaLoadout;
import org.shee33.act0.battlefield.data.ArenaCatalogStore;
import org.shee33.act0.battlefield.data.PlayerLoadoutStore;
import org.shee33.act0.battlefield.integration.TaczGunBridge;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.DeployOptionDto;
import org.shee33.act0.battlefield.network.DeploySlotOptionsDto;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大战场自有的配装发放服务，取代原先反射 ACT0-Arcade 的 {@code ArcadeLoadoutBridge}。
 *
 * <p>真相源是<b>地图目录</b>（{@link ArenaCatalogStore}），玩家存档（{@link PlayerLoadoutStore}）
 * 只记"我在这张图选了哪几项"。因此管理员下架一把枪后，选了它的玩家在下一次读取时自动回落到
 * 目录首项，不需要遍历改写任何玩家数据。
 *
 * <p><b>没有兵种、没有解锁、没有护甲外观</b>：这三样随 Arcade 一起去掉了。跨小队救援的门控
 * 改为"手里有没有医疗针"（{@code holdsSyringe}），那本来就是并列的判定条件之一；发放什么装备
 * 完全由地图目录决定，不再有跨图的玩家进度概念。
 */
public final class BattlefieldLoadoutService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 已经报告过发放失败的条目 ID。
     *
     * <p>发装在每次重生都会跑，不去重会刷屏；而"某把枪造不出来"是必须被看见的——玩家会因此
     * 少一件装备却收不到任何提示，这正是最难排查的那类故障。
     */
    private static final Set<String> REPORTED_BAD_ENTRIES = ConcurrentHashMap.newKeySet();

    /** 已经报告过"目录为空"的地图键，避免每次出生刷屏。 */
    private static final Set<String> REPORTED_EMPTY_ARENAS = ConcurrentHashMap.newKeySet();

    private BattlefieldLoadoutService() {
    }

    /**
     * 部署界面用的配装快照：槽位与可选项来自地图目录，当前选中来自玩家存档（失效项已回落）。
     *
     * <p>目录里一项都没配的槽位不出现在 DTO 里，面板因此不会画出空槽。
     */
    public static DeployLoadoutDto readDeployLoadout(ServerPlayer player, @Nullable String arenaKey) {
        if (player == null) {
            return DeployLoadoutDto.empty();
        }
        ArenaCatalog catalog = catalogOf(player.server, arenaKey);
        Map<LoadoutSlot, String> resolved = loadoutOf(player, arenaKey, catalog).resolve(catalog);
        List<DeploySlotOptionsDto> slots = new ArrayList<>();
        for (Map.Entry<LoadoutSlot, String> e : resolved.entrySet()) {
            LoadoutSlot slot = e.getKey();
            slots.add(new DeploySlotOptionsDto(slot.hotbarIndex(), slot.displayName(), e.getValue(),
                    optionsForSlot(catalog, slot)));
        }
        return new DeployLoadoutDto(slots);
    }

    /**
     * 把目录里该槽位的可选项转成"注册 ID + 显示名"成对下发。
     *
     * <p>显示名取录入时管理员手上那件物品的名字，因此中文资源包下天然是中文——服务端不需要
     * 也无法在运行时把 {@code tacz:ak47} 翻译成玩家语言（枪械名来自 TaCZ 的资源包，只有客户端有）。
     */
    private static List<DeployOptionDto> optionsForSlot(ArenaCatalog catalog, LoadoutSlot slot) {
        List<DeployOptionDto> out = new ArrayList<>();
        if (slot.isGadget()) {
            for (ArenaItemEntry entry : catalog.items(slot)) {
                out.add(new DeployOptionDto(entry.itemId(), entry.displayName()));
            }
        } else {
            for (ArenaWeaponEntry entry : catalog.weaponsForSlot(slot)) {
                out.add(new DeployOptionDto(entry.gunId(), entry.displayName()));
            }
        }
        return out;
    }

    /**
     * 记录玩家在部署界面对某个槽位的选择。
     *
     * <p>AI 士兵没有存档、也不会发这个包，因此这里只服务真人。
     *
     * @return 该选择是否被接受（槽位存在且该项在本图目录里）
     */
    public static boolean setPick(ServerPlayer player, @Nullable String arenaKey, int slotIndex,
                                  @Nullable String id) {
        LoadoutSlot slot = LoadoutSlot.byHotbarIndex(slotIndex);
        if (player == null || slot == null || arenaKey == null) {
            return false;
        }
        ArenaCatalog catalog = catalogOf(player.server, arenaKey);
        if (!catalog.hasOption(slot, id)) {
            return false;
        }
        PlayerLoadoutStore.get(player.server).setPick(player.getUUID(), arenaKey, slot, id);
        return true;
    }

    /**
     * 出生发装：清空背包，按解析结果把装备写进对应快捷栏格子。
     *
     * <p><b>先清空是刻意的</b>：不清空就会让上一条命捡到的东西、以及被换掉的旧武器残留下来，
     * 玩家很快能囤出一整背包。清空同时也清掉护甲栏——大战场不再有护甲外观系统。
     */
    public static void apply(ServerPlayer player, @Nullable String arenaKey) {
        if (player == null) {
            return;
        }
        ArenaCatalog catalog = catalogOf(player.server, arenaKey);
        Map<LoadoutSlot, String> resolved = loadoutOf(player, arenaKey, catalog).resolve(catalog);
        if (resolved.isEmpty()) {
            reportEmptyCatalog(player, arenaKey);
        }
        player.getInventory().clearContent();
        for (Map.Entry<LoadoutSlot, String> e : resolved.entrySet()) {
            ItemStack stack = stackFor(catalog, e.getKey(), e.getValue());
            if (!stack.isEmpty()) {
                player.getInventory().setItem(e.getKey().hotbarIndex(), stack);
            }
        }
        player.getInventory().setChanged();
    }

    /**
     * 目录为空时的告警。
     *
     * <p>没有这条提示，"本图没配军械库"与"配装系统坏了"在玩家眼里完全一样：都是清空背包后
     * 一件装备都没发，没有日志、没有报错。管理员唯一的线索只有去敲 {@code /aew1 arena list}。
     * 这里把实际用于查询的 {@code arenaKey} 一并说出来——地图改名导致目录被孤立时，
     * 这个键与管理员以为的图名不一致，正是唯一能看出问题的地方。
     */
    private static void reportEmptyCatalog(ServerPlayer player, @Nullable String arenaKey) {
        String key = arenaKey == null ? "" : arenaKey;
        if (REPORTED_EMPTY_ARENAS.add(key)) {
            LOGGER.warn("[ACT/0/Battlefield] 地图\"{}\"没有配置军械库，该图所有出生都将不发任何装备"
                    + "（用 /aew1 arena list 检查键名是否与实际地图名一致）", key);
        }
        if (!BotSpawner.isBot(player)) {
            player.sendSystemMessage(Component.literal(
                    "§c本图（§f" + key + "§c）尚未配置军械库，未发放任何装备。请管理员执行 §f/aew1 arena list §c检查。"));
        }
    }

    private static ArenaCatalog catalogOf(MinecraftServer server, @Nullable String arenaKey) {
        return ArenaCatalogStore.get(server).view(arenaKey);
    }

    private static PlayerArenaLoadout loadoutOf(ServerPlayer player, @Nullable String arenaKey,
                                                ArenaCatalog catalog) {
        if (BotSpawner.isBot(player)) {
            return botLoadout(player.getUUID(), catalog);
        }
        return PlayerLoadoutStore.get(player.server).loadout(player.getUUID(), arenaKey);
    }

    /**
     * 为 AI 士兵逐槽随机抽一件装备。
     *
     * <p>随机数按 bot 的 UUID 播种，因此同一个士兵的枪械<b>恒定且幂等</b>——每次重新部署都会
     * 重跑一遍发装，幂等意味着这些重跑不会让 bot 中途换枪。逐槽独立抽取而不是整套抽取，
     * 得到的是"杂牌军"观感；按套抽会让同一批 bot 呈现几种一眼可辨的固定造型，反而更假。
     */
    private static PlayerArenaLoadout botLoadout(UUID botId, ArenaCatalog catalog) {
        Random rng = new Random(botId.hashCode());
        Map<LoadoutSlot, String> picks = new EnumMap<>(LoadoutSlot.class);
        for (LoadoutSlot slot : LoadoutSlot.values()) {
            List<String> pool = catalog.optionIdsForSlot(slot);
            if (!pool.isEmpty()) {
                picks.put(slot, pool.get(rng.nextInt(pool.size())));
            }
        }
        return picks.isEmpty() ? PlayerArenaLoadout.EMPTY : new PlayerArenaLoadout(picks);
    }

    private static ItemStack stackFor(ArenaCatalog catalog, LoadoutSlot slot, String id) {
        return slot.isGadget() ? itemStack(catalog.findItem(slot, id)) : gunStack(catalog.findWeapon(id));
    }

    private static ItemStack gunStack(@Nullable ArenaWeaponEntry entry) {
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = TaczGunBridge.createGun(entry.gunId());
        if (stack.isEmpty()) {
            reportBadEntry(entry.gunId(), TaczGunBridge.isAvailable()
                    ? "TaCZ 里没有这把枪，可能是资源包变更后目录未同步"
                    : "服务器未安装 TaCZ，无法发放枪械");
            return ItemStack.EMPTY;
        }
        if (entry.usesDummyAmmo()) {
            TaczGunBridge.setDummyAmmo(stack, entry.dummyAmmo());
        }
        return stack;
    }

    private static ItemStack itemStack(@Nullable ArenaItemEntry entry) {
        if (entry == null) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(entry.itemId());
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            reportBadEntry(entry.itemId(), "物品注册表里没有这个 ID");
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, entry.count());
    }

    private static void reportBadEntry(String id, String reason) {
        if (REPORTED_BAD_ENTRIES.add(id)) {
            LOGGER.warn("[ACT/0/Battlefield] 地图目录条目发放失败：{} —— {}（同一条目后续不再记录）",
                    id, reason);
        }
    }
}
