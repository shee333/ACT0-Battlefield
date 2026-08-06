package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import org.shee33.act0.battlefield.client.screen.BattlefieldDeployScreen;
import org.shee33.act0.battlefield.network.DeployStatusDto;

import javax.annotation.Nullable;

/** 客户端部署状态缓存：死亡后由服务端推送，open=true 时打开/保持部署界面。 */
public final class ClientDeployStatus {

    @Nullable
    private static volatile DeployStatusDto status;

    private ClientDeployStatus() {
    }

    public static void accept(boolean open, DeployStatusDto dto) {
        status = dto;
        Minecraft mc = Minecraft.getInstance();
        if (!dto.active()) {
            ClientSquadSpectate.clear();
            if (mc.screen instanceof BattlefieldDeployScreen) {
                mc.setScreen(null);
            }
            return;
        }
        if (mc.screen instanceof BattlefieldDeployScreen screen) {
            screen.onDeployUpdated();
        } else if (open) {
            mc.setScreen(new BattlefieldDeployScreen());
        }
    }

    @Nullable
    public static DeployStatusDto status() {
        return status;
    }

    /** 断开服务器连接时兜底清空，防止暂停菜单"退出对局"按钮等 UI 在下一个世界/服务器里误留。 */
    public static void clear() {
        status = null;
    }
}
