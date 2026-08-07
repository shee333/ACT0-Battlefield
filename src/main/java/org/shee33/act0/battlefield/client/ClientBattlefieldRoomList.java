package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import org.shee33.act0.battlefield.client.screen.BattlefieldRoomBrowserScreen;
import org.shee33.act0.battlefield.network.BattlefieldRoomDto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端对局浏览器房间列表缓存：持有服务端最近一次下发的快照，并维护"收藏"星标
 * （纯客户端本地状态，不落盘/不同步——关闭游戏后清空是可接受的已知限制）。
 */
public final class ClientBattlefieldRoomList {

    private static volatile List<BattlefieldRoomDto> rooms = List.of();
    private static final Set<String> favorites = new LinkedHashSet<>();

    private ClientBattlefieldRoomList() {
    }

    public static void accept(List<BattlefieldRoomDto> newRooms) {
        rooms = newRooms;
        if (Minecraft.getInstance().screen instanceof BattlefieldRoomBrowserScreen screen) {
            screen.onRoomsUpdated();
        }
    }

    public static List<BattlefieldRoomDto> rooms() {
        return rooms;
    }

    public static boolean isFavorite(String roomKey) {
        return favorites.contains(roomKey);
    }

    public static void toggleFavorite(String roomKey) {
        if (!favorites.remove(roomKey)) {
            favorites.add(roomKey);
        }
    }
}
