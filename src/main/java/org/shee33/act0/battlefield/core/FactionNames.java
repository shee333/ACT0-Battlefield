package org.shee33.act0.battlefield.core;

/**
 * 一张地图的两个阵营名称。MC-free 值对象。
 *
 * <p>阵营名称是地图的一部分而不是模组的固定设定——凡尔登的双方不该叫着和某座现代城市地图
 * 一样的名字。因此名称由建图时指定并随地图存储（{@code BattlefieldData}），
 * {@link Faction} 枚举只保留身份与颜色。
 *
 * <p>颜色刻意不可配：蓝({@link Faction#ALPHA})/红({@link Faction#BRAVO}) 是全套 HUD、
 * 小地图标记、据点归属色赖以区分敌我的视觉基线，允许改色等于允许把敌我涂成一个颜色。
 */
public record FactionNames(String alpha, String bravo) {

    /**
     * 单个名称的字符数上限。
     *
     * <p>TAB 面板把两个阵营并排放在两个等宽列里，房间浏览器一行要塞下两个名称加分隔符——
     * 放宽到几十个字符不会报错，只会让 CJK 名称在这两处被截断成看不懂的残句。
     */
    public static final int MAX_LENGTH = 16;

    /**
     * 0.2.7 及更早的存档里没有阵营名字段，读档时回落到当初硬编码的这一对。
     *
     * <p>只用于兼容既有存档：新建地图必须显式指定，不会落到这里。
     */
    public static final FactionNames LEGACY = new FactionNames("北大西洋公约", "无邦军团");

    /** 校验结论。{@link #OK} 之外的每一项都对应一条给玩家看的具体报错。 */
    public enum Problem {
        /** 通过。 */
        OK,
        /** 空或全空白。 */
        BLANK,
        /** 超出 {@link #MAX_LENGTH}。 */
        TOO_LONG,
        /** 含 § 格式码或控制字符。 */
        ILLEGAL_CHAR,
        /** 两个阵营重名。 */
        DUPLICATE
    }

    public FactionNames {
        Problem problem = check(alpha, bravo);
        if (problem != Problem.OK) {
            throw new IllegalArgumentException("非法阵营名 (" + problem + "): " + alpha + " / " + bravo);
        }
        alpha = alpha.trim();
        bravo = bravo.trim();
    }

    /**
     * 校验单个名称。
     *
     * <p>拒绝 § 是因为名称会直接拼进聊天与 HUD 的着色串：放行格式码等于让建图者
     * 覆盖阵营色、甚至用 §k 把整行搅乱。控制字符同理会破坏单行渲染。
     */
    public static Problem checkName(String name) {
        if (name == null || name.isBlank()) {
            return Problem.BLANK;
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_LENGTH) {
            return Problem.TOO_LONG;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\u00a7' || c < ' ' || c == '\u007f') {
                return Problem.ILLEGAL_CHAR;
            }
        }
        return Problem.OK;
    }

    /** 校验一对名称：先逐个校验（ALPHA 优先报错），再查重名。 */
    public static Problem check(String alpha, String bravo) {
        Problem a = checkName(alpha);
        if (a != Problem.OK) {
            return a;
        }
        Problem b = checkName(bravo);
        if (b != Problem.OK) {
            return b;
        }
        return alpha.trim().equalsIgnoreCase(bravo.trim()) ? Problem.DUPLICATE : Problem.OK;
    }

    /** 读档用：任一名称不合法就整对回落到 {@link #LEGACY}，绝不因存档脏数据让地图打不开。 */
    public static FactionNames sanitize(String alpha, String bravo) {
        return check(alpha, bravo) == Problem.OK ? new FactionNames(alpha, bravo) : LEGACY;
    }

    /** 指定阵营的名称。 */
    public String name(Faction faction) {
        return faction == Faction.ALPHA ? alpha : bravo;
    }

    /** 指定阵营的着色名，如 {@code §9凡尔登守备军}。 */
    public String colored(Faction faction) {
        return faction.colorCode() + name(faction);
    }
}
