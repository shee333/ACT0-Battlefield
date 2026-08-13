package org.shee33.act0.battlefield.bot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 小队情报共享板：队友看见的敌人，以<b>限时 + 限精度</b>的形式共享给全队。MC-free，可单测。
 *
 * <p><b>这个功能天然有"变相透视"的风险，两道限制就是为此存在的。</b>如果队友的视野被无损共享，
 * 玩家会立刻感到"我明明只被一个人看到，全队却都精准朝我开枪"——那是作弊感，不是协同感。因此：
 * <ul>
 *   <li><b>限时</b>：情报 {@value #TTL_TICKS} tick（2 秒）后过期。玩家断掉视线躲两秒，
 *       全队就重新失去你的位置，"绕后"因此仍然有效。</li>
 *   <li><b>限精度</b>：坐标按 {@value #POSITION_GRID_BLOCKS} 格网格取整后再存。收到情报的队友
 *       只知道"你大概在那一带"，会走过去搜，而不会隔墙把枪口预瞄到你脑袋上。</li>
 * </ul>
 *
 * <p><b>共享情报只用于"去哪找"，不用于"往哪开枪"。</b>开火目标必须由 bot 自己的视线判定产生
 * （见 {@code BotPerception}）；本板提供的坐标只喂给行军去向。这条分工是上面两道限制之外的第三道
 * 保险：即便情报再精确，也不可能变成穿墙射击。
 */
public final class SquadIntel {

    /** 情报存活时长（tick）：2 秒。断视线两秒即从全队消失。 */
    public static final int TTL_TICKS = 40;

    /**
     * 位置量化网格边长（格）。
     *
     * <p>取 8 格：足够让接收方走对方向（竞技场尺度下 8 格是"同一个房间/同一个路口"的量级），
     * 又足以让接收方仍需自己搜索最后这段距离。取 2~3 格会精确到近乎报点，取 16 格以上则
     * 情报失去指向价值。
     */
    public static final double POSITION_GRID_BLOCKS = 8.0D;

    /**
     * 一条共享接触情报。
     *
     * @param enemyId       被发现的敌人
     * @param x             量化后的 X（非精确坐标）
     * @param y             量化后的 Y
     * @param z             量化后的 Z
     * @param expiresAtTick 过期的游戏刻（含义为"到达此刻即失效"）
     */
    public record Contact(UUID enemyId, double x, double y, double z, long expiresAtTick) {

        public boolean isActive(long tick) {
            return tick < expiresAtTick;
        }
    }

    private final Map<UUID, Contact> contacts = new HashMap<>();

    /**
     * 登记一次目视接触；同一敌人的旧情报被覆盖（最新的一次目视最有价值）。
     *
     * <p>坐标在存入前就被量化，而不是在读取时才模糊化——存的就是粗坐标，精确值从此不存在于板上，
     * 后续任何调用方都无法绕过这道限制取到原始位置。
     */
    public void report(UUID enemyId, double x, double y, double z, long tick) {
        if (enemyId == null) {
            return;
        }
        contacts.put(enemyId, new Contact(enemyId,
                snapToGrid(x), snapToGrid(y), snapToGrid(z), tick + TTL_TICKS));
    }

    /** 查某个敌人的未过期情报。 */
    public Optional<Contact> lookup(UUID enemyId, long tick) {
        Contact contact = contacts.get(enemyId);
        return contact != null && contact.isActive(tick) ? Optional.of(contact) : Optional.empty();
    }

    /** 全部未过期情报，顺序不保证。 */
    public List<Contact> active(long tick) {
        List<Contact> out = new ArrayList<>();
        for (Contact contact : contacts.values()) {
            if (contact.isActive(tick)) {
                out.add(contact);
            }
        }
        return out;
    }

    /**
     * 清理过期条目。
     *
     * <p>必须被周期性调用，否则一整局下来 map 会累积所有交战过的敌人——虽然 {@link #lookup} 会
     * 过滤掉过期项、行为正确，但条目本身不会消失，这是一处会随对局时长线性增长的泄漏。
     *
     * @return 清掉的条目数
     */
    public int pruneExpired(long tick) {
        int before = contacts.size();
        contacts.entrySet().removeIf(e -> !e.getValue().isActive(tick));
        return before - contacts.size();
    }

    /** 当前持有的条目数（含已过期未清理者），供测试与调试观察。 */
    public int size() {
        return contacts.size();
    }

    public void clear() {
        contacts.clear();
    }

    /**
     * 把一个坐标分量吸附到 {@value #POSITION_GRID_BLOCKS} 格网格的中心。
     *
     * <p>取网格<b>中心</b>而非下界：吸到下界会让情报系统性地偏向坐标轴负方向，一整局里接收方
     * 总是往同一侧偏。取中心则误差在网格内对称分布，最坏偏差是半个网格（4 格）。
     */
    public static double snapToGrid(double value) {
        return Math.floor(value / POSITION_GRID_BLOCKS) * POSITION_GRID_BLOCKS
                + POSITION_GRID_BLOCKS / 2.0D;
    }
}
