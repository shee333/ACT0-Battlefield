package org.shee33.act0.battlefield.loadout;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.LoadoutPresetDef;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.data.ArenaKey;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.PlayerLoadoutStore;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.ClassPresetsDto;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.DeploySlotDto;
import org.shee33.act0.battlefield.network.FactionPresetsDto;
import org.shee33.act0.battlefield.network.LoadoutConfigDto;
import org.shee33.act0.battlefield.network.LoadoutPresetPreviewDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 配装界面（ESC 菜单 → 配装）的服务端侧：只读预设 + 记录玩家选择。
 *
 * <p>配装内容全部由管理员在 {@link BattlefieldData} 里预设，玩家在本服务里只做<b>选择</b>。
 * 与 {@link BattlefieldLoadoutService} 的分工：那个服务管"这一命发什么装"（对局内），
 * 这个服务服务于对局外的赛前选择界面。
 */
public final class LoadoutConfigService {

    private LoadoutConfigService() {
    }

    /**
     * 组装一屏配装数据：两个阵营 × 四个兵种的全部预设 + 玩家选择。
     */
    public static LoadoutConfigDto snapshot(ServerPlayer player, @Nullable String requestedMap) {
        if (player == null) {
            return LoadoutConfigDto.empty();
        }
        List<String> mapNames = ArenaKey.knownNames(player.server);
        if (mapNames.isEmpty()) {
            return LoadoutConfigDto.empty();
        }
        String mapName = ArenaKey.resolve(player.server, requestedMap);
        if (mapName == null) {
            mapName = mapNames.get(0);
        }
        ServerLevel level = ArenaKey.levelFor(player.server, mapName);
        if (level == null) {
            return LoadoutConfigDto.empty();
        }
        BattlefieldData data = BattlefieldData.get(level);
        PlayerLoadoutStore.PlayerMapSelection sel =
                PlayerLoadoutStore.get(player.server).selection(player.getUUID(), mapName);

        List<FactionPresetsDto> factions = new ArrayList<>(Faction.values().length);
        for (Faction faction : Faction.values()) {
            List<ClassPresetsDto> classes = new ArrayList<>(SoldierClass.values().length);
            for (SoldierClass soldierClass : SoldierClass.values()) {
                classes.add(classPresets(data, sel, faction, soldierClass));
            }
            factions.add(new FactionPresetsDto(faction.name(), classes));
        }
        return new LoadoutConfigDto(mapNames, mapName, sel.selected().id(), Faction.ALPHA.name(), factions);
    }

    private static ClassPresetsDto classPresets(BattlefieldData data, PlayerLoadoutStore.PlayerMapSelection sel,
                                                Faction faction, SoldierClass soldierClass) {
        List<LoadoutPresetDef> defs = data.presetsFor(faction, soldierClass);
        List<LoadoutPresetPreviewDto> previews = new ArrayList<>(defs.size());
        for (LoadoutPresetDef def : defs) {
            previews.add(toPreview(def));
        }
        String selected = sel.presetId(faction, soldierClass);
        return new ClassPresetsDto(soldierClass.id(), selected != null ? selected : "", previews);
    }

    private static LoadoutPresetPreviewDto toPreview(LoadoutPresetDef def) {
        List<DeploySlotDto> slots = new ArrayList<>();
        for (LoadoutSlot slot : LoadoutPresetDef.PRESET_SLOTS) {
            String itemId = def.slot(slot);
            if (itemId != null && !itemId.isBlank()) {
                slots.add(new DeploySlotDto(slot.hotbarIndex(), itemId, def.ammoOf(slot)));
            }
        }
        LoadoutPresetDef.ArmorSet armor = def.armor();
        return new LoadoutPresetPreviewDto(def.id(), def.displayName(), slots,
                List.of(nz(armor.helmet()), nz(armor.chest()), nz(armor.legs()), nz(armor.boots())));
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** 玩家为某阵营某兵种选中一套配装，随后回发整屏快照。 */
    public static void selectPreset(ServerPlayer player, @Nullable String mapName, @Nullable String factionId,
                                    @Nullable String classId, @Nullable String presetId) {
        if (player == null) {
            return;
        }
        String resolvedMap = ArenaKey.resolve(player.server, mapName);
        Faction faction = parseFaction(factionId);
        SoldierClass soldierClass = SoldierClass.byId(classId);
        if (resolvedMap != null && faction != null && soldierClass != null) {
            if (BattlefieldLoadoutService.setPresetSelection(player, resolvedMap, faction, soldierClass, presetId)) {
                PlayerLoadoutStore.get(player.server)
                        .setSelectedClass(player.getUUID(), resolvedMap, soldierClass);
            }
        }
        BattlefieldNetwork.sendLoadoutConfig(player, snapshot(player, resolvedMap != null ? resolvedMap : mapName));
        // 如果玩家正在对局内部署，追加推一份最新的 DeployLoadoutDto 供下拉即时反馈。
        DeployLoadoutDto deployLoadout =
                BattlefieldLoadoutService.readDeployLoadout(player, resolvedMap, faction);
                BattlefieldLoadoutService.readDeployLoadout(player, resolvedMap, faction);
        if (deployLoadout != null) {
            BattlefieldNetwork.sendDeployLoadout(player, deployLoadout);
    }
    }

    /** 切换某张图上的兵种（各阵营各兵种的选择保留），随后回发整屏快照。 */

    /** 切换某张图上的兵种（各阵营各兵种的选择保留），随后回发整屏快照。 */
    public static void selectClass(ServerPlayer player, @Nullable String mapName, @Nullable String classId) {
        if (player == null) {
            return;
        }
        String resolvedMap = ArenaKey.resolve(player.server, mapName);
        SoldierClass soldierClass = SoldierClass.byId(classId);
        if (resolvedMap != null && soldierClass != null) {
            PlayerLoadoutStore.get(player.server)
                    .setSelectedClass(player.getUUID(), resolvedMap, soldierClass);
        }
        BattlefieldNetwork.sendLoadoutConfig(player, snapshot(player, resolvedMap != null ? resolvedMap : mapName));
    }

    @Nullable
    private static Faction parseFaction(@Nullable String factionId) {
        if (factionId == null || factionId.isBlank()) {
            return null;
        }
        try {
            return Faction.valueOf(factionId.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void sendSnapshot(ServerPlayer player, @Nullable String mapName) {
        if (player != null) {
            BattlefieldNetwork.sendLoadoutConfig(player, snapshot(player, mapName));
        }
    }
}
