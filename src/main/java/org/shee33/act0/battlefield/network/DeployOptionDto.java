package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 部署界面武器更换面板里的一个可选项：稳定标识 + 给玩家看的名字。
 *
 * <p><b>为什么两者必须成对下发</b>：{@code id} 是地图目录里的注册 ID（形如 {@code tacz:ak47}），
 * 换装选择、合法性校验、出生发装全部按它走，不能替换成显示名——两把枪完全可能同名。而玩家面板上
 * 需要看到的是"AK-47"，直接显示 ID 既丑也读不出是什么枪。
 *
 * <p>成对放进同一个记录而不是两条平行列表，是为了让它们不可能错位：平行列表一旦某处过滤了一边，
 * 显示名就会整体串位，而这种错误在界面上表现为"名字对不上枪"，极难联想到是网络层的问题。
 *
 * @param id          地图目录里的注册 ID
 * @param displayName 玩家可读名称
 */
public record DeployOptionDto(String id, String displayName) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(displayName);
    }

    public static DeployOptionDto decode(FriendlyByteBuf buf) {
        return new DeployOptionDto(buf.readUtf(), buf.readUtf());
    }

    /** 显示名缺失时的兜底：退回 ID 本身，至少还能看出选的是哪一项。 */
    public static String fallbackName(String id) {
        return id == null ? "" : id.replace('_', ' ');
    }
}
