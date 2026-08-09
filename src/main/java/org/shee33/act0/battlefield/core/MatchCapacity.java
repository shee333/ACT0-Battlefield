package org.shee33.act0.battlefield.core;

/**
 * 对局人数规则的纯逻辑：把"按地图自定义值"与"全局默认值"解析成实际生效值，并由总人数上限
 * 推导每阵营上限。MC-free，可直接单测。
 *
 * <p><b>覆盖语义</b>：按地图字段用 {@code 0} 表示"未设置，跟随全局配置"。选 0 而不是 -1，
 * 是因为 NBT 里读不到该键时默认就是 0，"没写过"与"显式跟随全局"天然同义，省掉一个哨兵值。
 */
public final class MatchCapacity {

    /** 无人数上限时对外暴露的容量值。 */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private MatchCapacity() {
    }

    /**
     * 解析实际生效的人数值。
     *
     * @param perMapValue   该地图的自定义值；{@code <= 0} 表示未设置
     * @param globalDefault 全局配置默认值
     * @return 生效值；地图设过就用地图的，否则用全局的
     */
    public static int resolve(int perMapValue, int globalDefault) {
        return perMapValue > 0 ? perMapValue : globalDefault;
    }

    /**
     * 由总人数上限推导每阵营上限，向上取整。
     *
     * <p>向上取整是为了让奇数上限能被填满：上限 15 时两边各自允许 8 人，最终 8/7 恰好 15；
     * 若向下取整成 7/7 就只能进 14 人，白白浪费一个名额。总人数上限由调用方在分配阵营<b>之前</b>
     * 单独校验，因此不存在两边都取到 8 而超出 15 的可能。
     *
     * @param maxPlayers 总人数上限；{@code <= 0} 视为无上限
     */
    public static int perSideCap(int maxPlayers) {
        if (maxPlayers <= 0) {
            return UNLIMITED;
        }
        return (maxPlayers + 1) / 2;
    }

    /**
     * 是否还能再容纳一名玩家。
     *
     * @param currentCount 当前人数
     * @param maxPlayers   总人数上限；{@code <= 0} 视为无上限
     */
    public static boolean hasRoom(int currentCount, int maxPlayers) {
        return maxPlayers <= 0 || currentCount < maxPlayers;
    }

    /**
     * 距离自动开局还差几人。
     *
     * @return 已达开局人数时返回 0
     */
    public static int shortfall(int currentCount, int minPlayersToStart) {
        return Math.max(0, minPlayersToStart - currentCount);
    }

    /**
     * 校验一对（开局人数, 人数上限）配置是否自洽。
     *
     * <p>开局人数大于人数上限时对局永远无法自动开始——玩家会看到"还差 N 人"却永远凑不满，
     * 属于必须在设置指令处就拦下的配置错误。
     *
     * @return 错误描述；{@code null} 表示配置合法
     */
    public static String validate(int minPlayersToStart, int maxPlayers) {
        if (minPlayersToStart <= 0) {
            return "自动开始人数必须大于 0";
        }
        if (maxPlayers > 0 && minPlayersToStart > maxPlayers) {
            return "自动开始人数(" + minPlayersToStart + ")不能大于人数上限(" + maxPlayers + ")，否则永远无法开局";
        }
        return null;
    }
}
