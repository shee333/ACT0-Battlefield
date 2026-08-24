package org.shee33.act0.battlefield.core;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 四个兵种。MC-free 纯枚举。
 *
 * <p>兵种<b>不限制可选武器</b>：军械库按地图配置一份，四个兵种都能选同一批枪。兵种在此前有各自的
 * 能力差异，现已<b>全部移除</b>——兵种退化为纯配装分类（每兵种下挂多套具名配装），不再提供任何
 * 战斗数值加成，只承担组织配装与展示的分组角色。
 */
public enum SoldierClass {

    /** 突击兵。 */
    ASSAULT("assault", "突击兵"),

    /** 侦查兵。 */
    RECON("recon", "侦查兵"),

    /** 支援兵。 */
    MEDIC("medic", "支援兵"),

    /** 工程兵。 */
    ENGINEER("engineer", "工程兵");

    /** 没选过兵种时的默认值，也是旧存档迁移的落点。 */
    public static final SoldierClass DEFAULT = ASSAULT;

    private final String id;
    private final String displayName;

    SoldierClass(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** 存档与网络传输用的稳定标识；不要用 {@link #ordinal()} 持久化。 */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 按 {@link #id()} 解析，大小写不敏感；无匹配返回 {@code null}。 */
    @Nullable
    public static SoldierClass byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (SoldierClass c : values()) {
            if (c.id.equals(key)) {
                return c;
            }
        }
        return null;
    }

    /** 按 {@link #id()} 解析，无匹配回落 {@link #DEFAULT}。用于读档与解包，绝不因脏数据失败。 */
    public static SoldierClass byIdOrDefault(@Nullable String id) {
        SoldierClass parsed = byId(id);
        return parsed != null ? parsed : DEFAULT;
    }
}
