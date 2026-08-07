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

    /**
     * 打开对局浏览器；已经打开时不重新构造（避免重播开场级联动效）。
     *
     * <p>这段开屏逻辑刻意放在 {@code client} 包而不是网络包里：网络包类在<b>服务端</b>也会被
     * 加载并做字节码校验，一旦包内直接出现 {@code Minecraft}/{@code Screen} 子类的类型引用
     * （例如 {@code instanceof BattlefieldRoomBrowserScreen}），专用服务端就会因为
     * {@code NoClassDefFoundError: net/minecraft/client/gui/screens/Screen} 拒绝加载整个模组。
     * 与 {@code SyncDeployPacket} → {@code ClientDeployStatus} 等既有 S2C 包同一套惯例：
     * 网络包只经 {@code DistExecutor} 调用本包静态方法，绝不自己触碰客户端类型。
     */
    public static void openBrowser() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof BattlefieldRoomBrowserScreen)) {
            mc.setScreen(new BattlefieldRoomBrowserScreen());
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
