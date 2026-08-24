package org.shee33.act0.battlefield.core;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 四个兵种。MC-free 纯枚举。
 *
 * <p>兵种<b>不限制可选武器</b>：军械库按地图配置一份，四个兵种都能选同一批枪，玩家每个兵种
 * 各自记住一套配装（见 {@code PlayerMapLoadout}）。差异体现在能力上，而不是"这把枪你不许拿"。
 * 这是 BF2042 的路子，也避免了"想玩狙就必须放弃医疗针"这种把玩法锁死的设计。
 *
 * <p>每个兵种的能力都挂在既有机制上，不引入新系统——见各常量的注释。
 */
public enum SoldierClass {

    /** 突击兵：交火后呼吸回血启动更快，用于顶在最前面持续作战。 */
    ASSAULT("assault", "突击兵", "脱离交火后回血启动快一倍"),

    /** 支援兵：唯一能不靠医疗针就跨小队救人的兵种。 */
    MEDIC("medic", "支援兵", "无需手持医疗针即可扶起任意友军"),

    /** 工程兵：本模组没有载具，改由补给工事体现——自己部署的补给箱存活更久。 */
    ENGINEER("engineer", "工程兵", "自己部署的补给箱存活时间翻倍"),

    /** 侦查兵：情报向，自己点亮的敌人持续更久。 */
    RECON("recon", "侦查兵", "自己标记的敌人持续时间翻倍");

    /** 突击兵的呼吸回血启动时间除以此值。 */
    public static final int ASSAULT_HEAL_DELAY_DIVISOR = 2;

    /** 工程兵部署物的存活时间乘以此值。 */
    public static final int ENGINEER_DEPLOYABLE_LIFETIME_MULTIPLIER = 2;

    /** 侦查兵标记的持续时间乘以此值。 */
    public static final int RECON_SPOT_DURATION_MULTIPLIER = 2;

    /** 没选过兵种时的默认值，也是旧存档迁移的落点。 */
    public static final SoldierClass DEFAULT = ASSAULT;

    private final String id;
    private final String displayName;
    private final String abilityBrief;

    SoldierClass(String id, String displayName, String abilityBrief) {
        this.id = id;
        this.displayName = displayName;
        this.abilityBrief = abilityBrief;
    }

    /** 存档与网络传输用的稳定标识；不要用 {@link #ordinal()} 持久化。 */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 一句话能力说明，供部署界面与配装界面展示。 */
    public String abilityBrief() {
        return abilityBrief;
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
