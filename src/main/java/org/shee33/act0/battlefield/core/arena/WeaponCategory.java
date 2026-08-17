package org.shee33.act0.battlefield.core.arena;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 武器类别。每张地图的武器池按类别分桶配置：{@code /aew1 arena <map> weapon <category> add|list|remove}。
 *
 * <p><b>为什么是固定枚举而不是自由字符串</b>：类别是玩法平衡的维度，不是自由标签。固定枚举换来
 * 命令补全、录入即校验、以及"类别拼错立刻报错"——自由字符串下 {@code snipper} 会静默生成一个
 * 永远没人取用的新桶，这种错误在服务器上极难发现。
 *
 * <p><b>为什么类别自带槽位映射</b>：管理员录入武器时只说类别（这是他关心的），不该再手填
 * "这把枪属于第几格"。类别决定槽位，槽位决定发到快捷栏哪一格，一条链路一个真相源。
 */
public enum WeaponCategory {

    /** 手枪，进副武器槽。 */
    PISTOL("pistol", "手枪", LoadoutSlot.SECONDARY),
    /** 冲锋枪。 */
    SMG("smg", "冲锋枪", LoadoutSlot.PRIMARY),
    /** 步枪。 */
    RIFLE("rifle", "步枪", LoadoutSlot.PRIMARY),
    /** 机枪。 */
    MACHINEGUN("machinegun", "机枪", LoadoutSlot.PRIMARY),
    /** 狙击枪。 */
    SNIPER("sniper", "狙击枪", LoadoutSlot.PRIMARY),
    /** 霰弹枪。 */
    SHOTGUN("shotgun", "霰弹枪", LoadoutSlot.PRIMARY),
    /** 发射器（火箭筒/榴弹）。 */
    LAUNCHER("launcher", "发射器", LoadoutSlot.PRIMARY),
    /** 近战武器，进近战槽。 */
    MELEE("melee", "近战", LoadoutSlot.MELEE);

    private final String id;
    private final String displayName;
    private final LoadoutSlot slot;

    WeaponCategory(String id, String displayName, LoadoutSlot slot) {
        this.id = id;
        this.displayName = displayName;
        this.slot = slot;
    }

    /** 命令里使用的小写字面量。 */
    public String id() {
        return id;
    }

    /** 命令回显用的中文名。 */
    public String displayName() {
        return displayName;
    }

    /** 该类别的武器发到哪个槽位。 */
    public LoadoutSlot slot() {
        return slot;
    }

    /** 按命令字面量解析类别；无匹配返回 {@code null}。 */
    @Nullable
    public static WeaponCategory byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (WeaponCategory c : values()) {
            if (c.id.equalsIgnoreCase(id)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 会进入指定槽位的所有类别，按枚举声明顺序。
     *
     * <p>部署界面某个槽位的可选列表 = 该槽位下所有类别的武器拼接，顺序即此方法的返回顺序，
     * 因此枚举声明顺序就是玩家在面板里看到的顺序（手枪在副武器槽只有一个类别，主武器槽
     * 则按 冲锋枪→步枪→机枪→狙击→霰弹→发射器 排列）。
     */
    public static List<WeaponCategory> forSlot(LoadoutSlot slot) {
        List<WeaponCategory> out = new ArrayList<>();
        for (WeaponCategory c : values()) {
            if (c.slot == slot) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }
}
