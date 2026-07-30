package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import java.util.ArrayList;
import java.util.List;

/**
 * 部署界面显示的配装数据快照。从服务器反射读取 Arcade 配装后发送给客户端。
 */
public record DeployLoadoutDto(String className, List<String> slotNames, List<String> itemNames) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(className);
        buf.writeInt(slotNames.size());
        for (int i = 0; i < slotNames.size(); i++) {
            buf.writeUtf(slotNames.get(i));
            buf.writeUtf(itemNames.get(i));
        }
    }

    public static DeployLoadoutDto decode(FriendlyByteBuf buf) {
        String cn = buf.readUtf();
        int size = buf.readInt();
        List<String> slots = new ArrayList<>(size);
        List<String> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            slots.add(buf.readUtf());
            items.add(buf.readUtf());
        }
        return new DeployLoadoutDto(cn, slots, items);
    }

    public static DeployLoadoutDto empty() {
        return new DeployLoadoutDto("", List.of(), List.of());
    }
}
