package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.shee33.act0.battlefield.client.BattlefieldRoomBrowserAnimator;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.RequestBattlefieldRoomListPacket;

/**
 * 对局浏览器界面（《游戏浏览器动效双版本规格文档》§1 + §3「浏览器 B · 战地模式 · 对局浏览器」）。
 *
 * <p>入口：战地终端物品右键 / {@code /battlefield ui|browse|browser} → 服务端发
 * {@code OpenBattlefieldBrowserPacket} → 客户端 {@code setScreen(new BattlefieldRoomBrowserScreen())}。
 *
 * <p><b>本类刻意保持很薄</b>：视觉与动效全在 {@link BattlefieldRoomBrowserAnimator} 里，因为补间引擎
 * {@code client.Tween} 是 {@code client} 包的包私有类型，子包 {@code client.screen} 无法引用。
 * 分工与本仓库既有的 {@code DeployMapPanel} + {@link BattlefieldDeployScreen} 完全一致：
 * Screen 只做「数据轮询 / 输入转发 / 加入命令下发」，跨包边界只传基础类型与
 * {@link BattlefieldRoomBrowserAnimator.JoinRequest}。
 */
public final class BattlefieldRoomBrowserScreen extends Screen {

    /** 房间列表轮询间隔（40 tick = 2 秒，与文档「1~2s 一次低频摘要广播」一致）。 */
    private static final int POLL_INTERVAL_TICKS = 40;

    private final BattlefieldRoomBrowserAnimator animator = new BattlefieldRoomBrowserAnimator();
    private int pollTicks;

    public BattlefieldRoomBrowserScreen() {
        super(Component.literal("对局浏览器"));
    }

    @Override
    protected void init() {
        pollTicks = 0;
        requestRoomList();
    }

    /** 由 {@code ClientBattlefieldRoomList#accept} 在新快照到达时调用。 */
    public void onRoomsUpdated() {
        animator.onRoomsUpdated();
    }

    @Override
    public void tick() {
        if (++pollTicks >= POLL_INTERVAL_TICKS) {
            pollTicks = 0;
            requestRoomList();
        }
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        animator.render(gg, font, width, height, mouseX, mouseY);
        BattlefieldRoomBrowserAnimator.JoinRequest join = animator.pollPendingJoin();
        if (join != null) {
            dispatchJoin(join);
        }
    }

    /**
     * 真正下发加入命令 —— 只会在三段式加入转场<b>播完之后</b>被调用（{@code pollPendingJoin} 在
     * 转场结束那一帧才返回非空），否则玩家永远看不到自己触发的反馈动效。
     */
    private void dispatchJoin(BattlefieldRoomBrowserAnimator.JoinRequest join) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            String root = join.breakthrough() ? "breakthrough" : "battlefield";
            player.connection.sendCommand(root + " quickjoin \"" + join.roomKey() + "\"");
        }
        onClose();
    }

    private static void requestRoomList() {
        BattlefieldNetwork.CHANNEL.sendToServer(new RequestBattlefieldRoomListPacket());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && animator.handleClick(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * 滚轮滚动对局列表。Minecraft 1.20.1 的签名是三参数 {@code (mouseX, mouseY, delta)} ——
     * 1.20.2+ 才拆成 {@code (mouseX, mouseY, scrollX, scrollY)}；这里必须用三参数版，否则不构成
     * 覆写、滚动会静默失效。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (animator.isInListRegion(mouseX, mouseY) && animator.handleScroll(delta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
