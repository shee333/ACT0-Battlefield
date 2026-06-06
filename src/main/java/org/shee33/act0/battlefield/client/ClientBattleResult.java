package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import org.shee33.act0.battlefield.client.screen.BattleResultScreen;
import org.shee33.act0.battlefield.network.BattleResultDto;

/** 客户端战报入口。 */
public final class ClientBattleResult {

    private ClientBattleResult() {
    }

    public static void open(BattleResultDto result) {
        Minecraft.getInstance().setScreen(new BattleResultScreen(result));
    }
}
