package org.shee33.act0.battlefield.loadout;

import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.PlayerArenaLoadout;
import org.shee33.act0.battlefield.data.ArenaCatalogStore;
import org.shee33.act0.battlefield.data.ArenaKey;
import org.shee33.act0.battlefield.data.PlayerLoadoutStore;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.ClassLoadoutDto;
import org.shee33.act0.battlefield.network.DeploySlotOptionsDto;
import org.shee33.act0.battlefield.network.LoadoutConfigDto;

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
        List<ClassLoadoutDto> classes = new ArrayList<>(SoldierClass.values().length);
        for (SoldierClass soldierClass : SoldierClass.values()) {
            classes.add(new ClassLoadoutDto(soldierClass.id(),
                    slotsFor(catalog, store.loadout(player.getUUID(), mapName, soldierClass))));
        }
        return new LoadoutConfigDto(mapNames, mapName,
                store.selectedClass(player.getUUID(), mapName).id(), classes);
    }

    private static List<DeploySlotOptionsDto> slotsFor(ArenaCatalog catalog, PlayerArenaLoadout picks) {
        Map<LoadoutSlot, String> resolved = picks.resolve(catalog);
        List<DeploySlotOptionsDto> slots = new ArrayList<>();
        for (Map.Entry<LoadoutSlot, String> e : resolved.entrySet()) {
            LoadoutSlot slot = e.getKey();
            slots.add(new DeploySlotOptionsDto(slot.hotbarIndex(), slot.displayName(), e.getValue(),
                    BattlefieldLoadoutService.optionsForSlot(catalog, slot)));
        }
        return slots;
    }

    /** 改一个槽位，随后回发整屏快照让客户端确认或回滚。 */
    public static void edit(ServerPlayer player, @Nullable String mapName, @Nullable String classId,
                            int slotIndex, @Nullable String itemId) {
        if (player == null) {
            return;
        }
        String resolvedMap = ArenaKey.resolve(player.server, mapName);
        SoldierClass soldierClass = SoldierClass.byId(classId);
        LoadoutSlot slot = LoadoutSlot.byHotbarIndex(slotIndex);
        if (resolvedMap != null && soldierClass != null && slot != null
                && ArenaCatalogStore.get(player.server).view(resolvedMap).hasOption(slot, itemId)) {
            PlayerLoadoutStore.get(player.server)
                    .setPick(player.getUUID(), resolvedMap, soldierClass, slot, itemId);
        }
        BattlefieldNetwork.sendLoadoutConfig(player, snapshot(player, resolvedMap != null ? resolvedMap : mapName));
    }

    /** 切换某张图上的兵种，随后回发整屏快照。 */
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
