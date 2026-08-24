package org.shee33.act0.battlefield.loadout;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.shee33.act0.battlefield.bot.mc.BotSpawner;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.LoadoutPresetDef;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.data.ArenaKey;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.PlayerLoadoutStore;
import org.shee33.act0.battlefield.integration.TaczGunBridge;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.DeploySlotDto;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配装发放服务：按<b>管理员预设</b>给玩家发装。
 *
 * <p>真相源是 {@link BattlefieldData} 里每张地图的配装预设（阵营 × 兵种 → 配装列表），
 * 玩家存档（{@link PlayerLoadoutStore}）只记"每个（阵营,兵种）选了哪套"。玩家不编辑配装内容。
 *
 * <p><b>没有兵种、没有解锁、没有护甲外观</b>：这三样和 Arcade 一起去掉了。发放什么装备完全由
 * 管理员预设决定，不再有跨图的玩家进度概念。
 */
public final class BattlefieldLoadoutService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 已经报告过发放失败的条目 ID。 */
    private static final Set<String> REPORTED_BAD_ENTRIES = ConcurrentHashMap.newKeySet();

    /** 已经报告过"该兵种无配装"的（地图,兵种）键，避免每次出生刷屏。 */
    private static final Set<String> REPORTED_NO_PRESET = ConcurrentHashMap.newKeySet();

    private BattlefieldLoadoutService() {
    }

    /** 清空"已告警过"的记录，随服务器停止调用（跨世界复用同一 JVM 时防止告警永久闭嘴）。 */
    public static void resetDiagnostics() {
        REPORTED_BAD_ENTRIES.clear();
        REPORTED_NO_PRESET.clear();
    }

    /** 该玩家在该图、该阵营该兵种实际生效的配装：优先玩家选择，未选或失效回落第一套；无预设返回 {@code null}。 */
    @Nullable
    public static LoadoutPresetDef effectivePreset(ServerPlayer player, @Nullable String arenaKey,
                                                   Faction faction, SoldierClass soldierClass) {
        if (player == null || faction == null || soldierClass == null) {
            return null;
        }
        List<LoadoutPresetDef> presets = presetData(player, arenaKey)
                .presetsFor(faction, soldierClass);
        if (presets.isEmpty()) {
            return null;
        }
        String selected = PlayerLoadoutStore.get(player.server)
                .selectedPresetId(player.getUUID(), arenaKey, faction, soldierClass);
        if (selected != null) {
            for (LoadoutPresetDef p : presets) {
                if (p.id().equals(selected)) {
                    return p;
                }
            }
        }
        return presets.get(0);
    }

    /** 部署界面用的配装预览：玩家当前兵种所选那套（或第一套）的槽位快照。 */
    public static DeployLoadoutDto readDeployLoadout(ServerPlayer player, @Nullable String arenaKey,
                                                     Faction faction) {
        if (player == null) {
            return DeployLoadoutDto.empty();
        }
        SoldierClass soldierClass = classOf(player, arenaKey);
        LoadoutPresetDef preset = effectivePreset(player, arenaKey, faction, soldierClass);
        if (preset == null) {
            return new DeployLoadoutDto(soldierClass.id(), "", "", List.of());
        }
        List<DeploySlotDto> slots = new ArrayList<>();
        for (LoadoutSlot slot : LoadoutPresetDef.PRESET_SLOTS) {
            String itemId = preset.slot(slot);
            if (itemId != null && !itemId.isBlank()) {
                slots.add(new DeploySlotDto(slot.hotbarIndex(), itemId, preset.ammoOf(slot)));
            }
        }
        return new DeployLoadoutDto(soldierClass.id(), preset.id(), preset.displayName(), slots);
    }

    /** 记录玩家为某阵营某兵种选中的配装；id 不存在时拒绝（客户端拼错时静默忽略）。 */
    public static boolean setPresetSelection(ServerPlayer player, @Nullable String arenaKey, Faction faction,
                                             SoldierClass soldierClass, @Nullable String presetId) {
        if (player == null || faction == null || soldierClass == null || presetId == null) {
            return false;
        }
        if (presetData(player, arenaKey).preset(faction, soldierClass, presetId) == null) {
            return false;
        }
        PlayerLoadoutStore.get(player.server)
                .setPresetSelection(player.getUUID(), arenaKey, faction, soldierClass, presetId);
        return true;
    }

    /** 记录玩家切换兵种。 */
    public static boolean setSelectedClass(ServerPlayer player, @Nullable String arenaKey,
                                           @Nullable String classId) {
        SoldierClass soldierClass = SoldierClass.byId(classId);
        if (player == null || arenaKey == null || soldierClass == null) {
            return false;
        }
        PlayerLoadoutStore.get(player.server).setSelectedClass(player.getUUID(), arenaKey, soldierClass);
        return true;
    }

    /** 该玩家在该图上生效的兵种，供对局侧解析配装。 */
    public static SoldierClass classOf(ServerPlayer player, @Nullable String arenaKey) {
        if (player == null) {
            return SoldierClass.DEFAULT;
        }
        if (BotSpawner.isBot(player)) {
            return botClass(player.getUUID());
        }
        return PlayerLoadoutStore.get(player.server).selectedClass(player.getUUID(), arenaKey);
    }

    private static SoldierClass botClass(java.util.UUID botId) {
        SoldierClass[] all = SoldierClass.values();
        return all[Math.floorMod(botId.hashCode(), all.length)];
    }

    /**
     * 出生发装：清空背包与护甲，按生效配装写装备。
     *
     * <p><b>先清空是刻意的</b>：不留上一条命捡到的东西。护甲也清空再写入配装服装。
     */
    public static void apply(ServerPlayer player, @Nullable String arenaKey, Faction faction) {
        if (player == null) {
            return;
        }
        SoldierClass soldierClass = classOf(player, arenaKey);
        LoadoutPresetDef preset = effectivePreset(player, arenaKey, faction, soldierClass);
        player.getInventory().clearContent();
        player.getInventory().armor.clear();
        if (preset == null) {
            reportNoPreset(player, arenaKey, faction, soldierClass);
        } else {
            for (LoadoutSlot slot : LoadoutPresetDef.PRESET_SLOTS) {
                String itemId = preset.slot(slot);
                if (itemId == null || itemId.isBlank()) {
                    continue;
                }
                ItemStack stack = stackFor(slot, itemId, preset.ammoOf(slot));
                if (!stack.isEmpty()) {
                    player.getInventory().setItem(slot.hotbarIndex(), stack);
                }
            }
            applyArmor(player, preset.armor());
        }
        player.getInventory().setChanged();
    }

    private static void applyArmor(ServerPlayer player, LoadoutPresetDef.ArmorSet armor) {
        setArmor(player, EquipmentSlot.HEAD, armor.helmet());
        setArmor(player, EquipmentSlot.CHEST, armor.chest());
        setArmor(player, EquipmentSlot.LEGS, armor.legs());
        setArmor(player, EquipmentSlot.FEET, armor.boots());
    }

    private static void setArmor(ServerPlayer player, EquipmentSlot eq, @Nullable String itemId) {
        if (itemId == null) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            reportBadEntry(itemId, "物品注册表里没有这个 ID");
            return;
        }
        player.getInventory().armor.set(eq.getIndex(), new ItemStack(item));
    }

    private static ItemStack stackFor(LoadoutSlot slot, String itemId, int ammo) {
        if (LoadoutPresetDef.isWeaponSlot(slot)) {
            ItemStack gun = TaczGunBridge.createGun(itemId);
            if (gun.isEmpty()) {
                reportBadEntry(itemId, TaczGunBridge.isAvailable()
                        ? "TaCZ 里没有这把枪，可能是资源包变更后配装未更新"
                        : "服务器未安装 TaCZ，无法发放枪械");
                return ItemStack.EMPTY;
            }
            if (ammo > 0) {
                TaczGunBridge.setDummyAmmo(gun, ammo);
            }
            return gun;
        }
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        Item item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            reportBadEntry(itemId, "物品注册表里没有这个 ID");
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }

    private static void reportNoPreset(ServerPlayer player, @Nullable String arenaKey,
                                       Faction faction, SoldierClass soldierClass) {
        String key = (arenaKey == null ? "" : arenaKey) + "#" + faction.name() + "#" + soldierClass.id();
        if (REPORTED_NO_PRESET.add(key)) {
            LOGGER.warn("[ACT/0/Battlefield] 地图\"{}\"阵营{}兵种{}没有配置配装预设，出生未发任何装备",
                    arenaKey, faction.name(), soldierClass.displayName());
        }
        if (!BotSpawner.isBot(player)) {
            player.sendSystemMessage(Component.literal(
                    "§c本图当前阵营的 §f" + soldierClass.displayName() + "§c 尚未配置配装，未发放装备。请管理员用 §f/aew1 loadout §c配置。"));
        }
    }

    private static void reportBadEntry(String id, String reason) {
        if (REPORTED_BAD_ENTRIES.add(id)) {
            LOGGER.warn("[ACT/0/Battlefield] 配装条目发放失败：{} — {}（同一条目后续不再记录）", id, reason);
        }
    }

    /** 配装预设所在的地图数据：按主键反查世界；找不到时回落玩家所在世界。 */
    private static BattlefieldData presetData(ServerPlayer player, @Nullable String arenaKey) {
        ServerLevel level = ArenaKey.levelFor(player.server, arenaKey);
        if (level == null) {
            level = player.serverLevel();
        }
        return BattlefieldData.get(level);
    }

    /** 供对局侧取玩家本命兵种的一套随机预设（bot 用），保底不空手。 */
    @Nullable
    public static LoadoutPresetDef botPreset(ServerPlayer bot, @Nullable String arenaKey, Faction faction) {
        if (bot == null || faction == null) {
            return null;
        }
        List<LoadoutPresetDef> presets = presetData(bot, arenaKey).presetsFor(faction, classOf(bot, arenaKey));
        if (presets.isEmpty()) {
            return null;
        }
        return presets.get(new Random(bot.getUUID().hashCode()).nextInt(presets.size()));
    }
}
