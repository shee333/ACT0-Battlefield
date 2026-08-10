package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.network.DeployableDto;

import java.util.List;

/** 客户端侧已部署补给物快照，供地面提示圆渲染读取。 */
public final class ClientDeployables {

    private static volatile List<DeployableDto> deployables = List.of();

    public static void accept(List<DeployableDto> list) {
        deployables = list == null ? List.of() : List.copyOf(list);
    }

    public static List<DeployableDto> get() {
        return deployables;
    }

    public static void clear() {
        deployables = List.of();
    }

    private ClientDeployables() {
    }
}
