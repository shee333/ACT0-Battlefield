package org.shee33.act0.battlefield.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * 断开服务器连接时的客户端状态兜底清空入口。
 *
 * <p>本模组大量客户端渲染/输入状态用静态字段承载（HUD 缓存、动画状态机、开火锁、倒地反馈等），
 * 这些字段此前没有任何统一的断线清理路径——玩家在对局中途掉线/崩服/强制退出后重连任意世界
 * （包括原版单机），会看到上一局遗留的幽灵动画，甚至因为 {@code ClientDownedFeedback.downed}/
 * {@code ClientFireLock.locked} 残留为 {@code true} 而导致在新世界完全无法跳跃/攻击/使用物品。
 * 参照 ACT0-Arcade 的 {@code ClientHotZoneOverlay#onLoggingOut} 同款写法补齐。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientLifecycleHandler {

    private ClientLifecycleHandler() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientDownedFeedback.clear();
        ClientFireLock.setLocked(false);
        ClientMatchStartFx.reset();
        ClientDeployFx.reset();
        DeployConfirmFx.reset();
        ClientCapturePointEvent.reset();
        CaptureFocusAnimator.reset();
        BreakthroughFocusAnimator.reset();
        BreakthroughSectorAnimator.reset();
        ClientBattleHud.clear();
        ClientMinimapEvents.clear();
        ClientBreakthroughHud.clear();
        ClientDeployStatus.clear();
        ClientDeployLoadout.clear();
        ClientDeployables.clear();
        ClientTabFocus.reset();
        ClientSquadSpectate.clear();
        BattlefieldClientInput.reset();
    }
}
