package org.shee33.act0.battlefield.deployable;

/** 可部署补给物类型。序号即网络编码值，调整顺序等于改协议。 */
public enum DeployableKind {

    /** 工程兵弹药箱：范围内自动补给主副武器备弹。 */
    AMMO,

    /** 支援兵医疗箱：范围内延迟起效并回满血量。 */
    MEDIC;

    private static final DeployableKind[] VALUES = values();

    public static DeployableKind byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : AMMO;
    }
}
