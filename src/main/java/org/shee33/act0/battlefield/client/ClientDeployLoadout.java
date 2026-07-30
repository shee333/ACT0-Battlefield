package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.network.DeployLoadoutDto;

import javax.annotation.Nullable;

public final class ClientDeployLoadout {

    @Nullable
    private static volatile DeployLoadoutDto loadout;

    public static void accept(DeployLoadoutDto dto) {
        loadout = dto;
    }

    @Nullable
    public static DeployLoadoutDto get() {
        return loadout;
    }

    public static void clear() {
        loadout = null;
    }

    private ClientDeployLoadout() {}
}
