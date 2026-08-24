package org.shee33.act0.battlefield.loadout;

import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog;
import org.shee33.act0.battlefield.core.arena.ClassLoadouts;
import org.shee33.act0.battlefield.core.arena.LoadoutPreset;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.PlayerArenaLoadout;
import org.shee33.act0.battlefield.core.arena.PlayerMapLoadout;
import org.shee33.act0.battlefield.data.ArenaCatalogStore;
import org.shee33.act0.battlefield.data.ArenaKey;
import org.shee33.act0.battlefield.data.PlayerLoadoutStore;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.ClassLoadoutDto;
import org.shee33.act0.battlefield.network.DeploySlotOptionsDto;
import org.shee33.act0.battlefield.network.LoadoutConfigDto;
import org.shee33.act0.battlefield.network.LoadoutPresetDto;
import org.shee33.act0.battlefield.network.LoadoutSlotPickDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配装界面（ESC 菜单 → 配装）的服务端侧。
 *
 * <p>与 {@link BattlefieldLoadoutService} 的分工：那个服务只管"这一命发什么装"，因此始终围绕
 * <b>当前对局所在地图</b>；这个服务服务于<b>对局外</b>的赛前配装，地图由玩家在界面上选，
 * 因此每个入口都显式带 {@code mapName} 而不依赖任何对局状态。
 */
public final class LoadoutConfigService {

    private LoadoutConfigService() {
    }

    /**
     * 组装一屏配装数据。
     *
     * @param requestedMap 客户端请求的地图；为空或解析不到时回落到第一张已知地图，
     *                     让界面首次打开无需客户端先猜一个名字
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
        ArenaCatalog catalog = ArenaCatalogStore.get(player.server).view(mapName);
        PlayerLoadoutStore store = PlayerLoadoutStore.get(player.server);
        PlayerMapLoadout mapLoadout = store.mapLoadout(player.getUUID(), mapName);
        List<ClassLoadoutDto> classes = new ArrayList<>(SoldierClass.values().length);
        for (SoldierClass soldierClass : SoldierClass.values()) {
            classes.add(classDto(catalog, mapLoadout, soldierClass));
        }
        return new LoadoutConfigDto(mapNames, mapName, mapLoadout.selected().id(), classes);
    }

    /** 一个兵种 → 网格 DTO：激活序号 + 4 套（名字+各槽位选择）+ 全套槽位可选项。 */
    private static ClassLoadoutDto classDto(ArenaCatalog catalog, PlayerMapLoadout mapLoadout,
                                            SoldierClass soldierClass) {
        ClassLoadouts group = mapLoadout.classLoadouts(soldierClass);
        List<LoadoutPresetDto> presets = new ArrayList<>(ClassLoadouts.PRESET_COUNT);
        for (int i = 0; i < ClassLoadouts.PRESET_COUNT; i++) {
            LoadoutPreset p = group.preset(i);
            presets.add(new LoadoutPresetDto(p.name(), pickDtos(p.loadout(), catalog)));
        }
        return new ClassLoadoutDto(soldierClass.id(), group.activeIndex(), presets,
                slotOptionsFor(catalog, group.active()));
    }

    /** 一套配装的槽位选择 → 生效值的扁平 DTO。 */
    private static List<LoadoutSlotPickDto> pickDtos(PlayerArenaLoadout loadout, ArenaCatalog catalog) {
        List<LoadoutSlotPickDto> out = new ArrayList<>();
        for (Map.Entry<LoadoutSlot, String> e : loadout.resolve(catalog).entrySet()) {
            out.add(new LoadoutSlotPickDto(e.getKey().hotbarIndex(), e.getValue()));
        }
        return out;
    }

    /** 一套配装的槽位可选项（当前选中 = 该套对应槽位的生效值）。 */
    private static List<DeploySlotOptionsDto> slotOptionsFor(ArenaCatalog catalog, PlayerArenaLoadout loadout) {
        Map<LoadoutSlot, String> resolved = loadout.resolve(catalog);
        List<DeploySlotOptionsDto> slots = new ArrayList<>();
        for (Map.Entry<LoadoutSlot, String> e : resolved.entrySet()) {
            LoadoutSlot slot = e.getKey();
            slots.add(new DeploySlotOptionsDto(slot.hotbarIndex(), slot.displayName(), e.getValue(),
                    BattlefieldLoadoutService.optionsForSlot(catalog, slot)));
        }
        return slots;
    }

    /** 改某兵种某套配装的一个槽位，随后回发整屏快照让客户端确认或回滚。 */
    public static void edit(ServerPlayer player, @Nullable String mapName, @Nullable String classId,
                            int presetIndex, int slotIndex, @Nullable String itemId) {
        if (player == null) {
            return;
        }
        String resolvedMap = ArenaKey.resolve(player.server, mapName);
        SoldierClass soldierClass = SoldierClass.byId(classId);
        LoadoutSlot slot = LoadoutSlot.byHotbarIndex(slotIndex);
        if (resolvedMap != null && soldierClass != null && slot != null
                && ArenaCatalogStore.get(player.server).view(resolvedMap).hasOption(slot, itemId)) {
            PlayerLoadoutStore.get(player.server)
                    .setPresetPick(player.getUUID(), resolvedMap, soldierClass, presetIndex, slot, itemId);
        }
        BattlefieldNetwork.sendLoadoutConfig(player, snapshot(player, resolvedMap != null ? resolvedMap : mapName));
    }

    /** 给某兵种某套配装命名（空串恢复默认名），随后回发整屏快照。 */
    public static void rename(ServerPlayer player, @Nullable String mapName, @Nullable String classId,
                              int presetIndex, @Nullable String name) {
        if (player == null) {
            return;
        }
        String resolvedMap = ArenaKey.resolve(player.server, mapName);
        SoldierClass soldierClass = SoldierClass.byId(classId);
        if (resolvedMap != null && soldierClass != null) {
            PlayerLoadoutStore.get(player.server)
                    .setPresetName(player.getUUID(), resolvedMap, soldierClass, presetIndex, name);
        }
        BattlefieldNetwork.sendLoadoutConfig(player, snapshot(player, resolvedMap != null ? resolvedMap : mapName));
    }

    /** 点击网格：选定兵种并把该格设为激活套，随后回发整屏快照。 */
    public static void selectPreset(ServerPlayer player, @Nullable String mapName, @Nullable String classId,
                                    int presetIndex) {
        if (player == null) {
            return;
        }
        String resolvedMap = ArenaKey.resolve(player.server, mapName);
        SoldierClass soldierClass = SoldierClass.byId(classId);
        if (resolvedMap != null && soldierClass != null) {
            PlayerLoadoutStore store = PlayerLoadoutStore.get(player.server);
            store.setSelectedClass(player.getUUID(), resolvedMap, soldierClass);
            store.setActivePreset(player.getUUID(), resolvedMap, soldierClass, presetIndex);
        }
        BattlefieldNetwork.sendLoadoutConfig(player, snapshot(player, resolvedMap != null ? resolvedMap : mapName));
    }

    /** 切换某张图上的兵种（保留各套配装与激活序号），随后回发整屏快照。 */
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

    public static void sendSnapshot(ServerPlayer player, @Nullable String mapName) {
        if (player != null) {
            BattlefieldNetwork.sendLoadoutConfig(player, snapshot(player, mapName));
        }
    }
}
