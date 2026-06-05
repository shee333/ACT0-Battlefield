package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import org.shee33.act0.battlefield.client.screen.BattlefieldJoinScreen;
import org.shee33.act0.battlefield.network.BattlefieldStatusDto;

import javax.annotation.Nullable;

/**
 * 客户端大战场状态持有者：缓存服务端下发的状态快照，供 {@link BattlefieldJoinScreen} 渲染。
 *
 * <p>{@code open=true}（玩家主动开屏）且界面未打开时才打开界面；{@code open=false} 仅刷新已打开的界面，
 * 不弹窗（避免广播刷新打扰未在看界面的玩家）。仅在客户端调用。
 */
public final class ClientBattlefieldStatus {

    @Nullable
    private static volatile BattlefieldStatusDto status;

    private ClientBattlefieldStatus() {
    }

    public static void accept(boolean open, BattlefieldStatusDto dto) {
        status = dto;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof BattlefieldJoinScreen screen) {
            screen.onStatusUpdated();
        } else if (open) {
            mc.setScreen(new BattlefieldJoinScreen());
        }
    }

    @Nullable
    public static BattlefieldStatusDto status() {
        return status;
    }
}
