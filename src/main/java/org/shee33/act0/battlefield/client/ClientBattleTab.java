package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.network.BattleTabDto;

import javax.annotation.Nullable;

/** 客户端自定义 TAB 战绩面板缓存。 */
public final class ClientBattleTab {

    private static volatile boolean shown = false;
    @Nullable
    private static volatile BattleTabDto tab;

    private ClientBattleTab() {
    }

    public static void accept(boolean show, BattleTabDto dto) {
        shown = show;
        tab = show ? dto : null;
    }

    public static boolean isShown() {
        return shown && tab != null;
    }

    @Nullable
    public static BattleTabDto tab() {
        return tab;
    }
}
