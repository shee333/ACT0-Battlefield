package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.network.LoadoutConfigDto;

import javax.annotation.Nullable;

/**
 * 配装界面的客户端数据持有者。
 *
 * <p>{@code volatile} 而非加锁：网络线程写、渲染线程读，写的是一个整体替换的不可变快照，
 * 读到的要么是上一屏要么是新一屏，不存在读到半个包的情况。
 */
public final class ClientLoadoutConfig {

    @Nullable
    private static volatile LoadoutConfigDto config;

    public static void accept(LoadoutConfigDto dto) {
        config = dto;
    }

    @Nullable
    public static LoadoutConfigDto get() {
        return config;
    }

    public static void clear() {
        config = null;
    }

    private ClientLoadoutConfig() {
    }
}
