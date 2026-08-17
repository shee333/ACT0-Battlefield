package org.shee33.act0.battlefield.core.arena;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一名玩家在某一张地图上的配装选择（槽位 → 选中的武器/道具 ID）。按玩家×地图持久化。
 *
 * <p><b>为什么只存"选择"而不存完整配装</b>：武器池的真相源是地图目录，玩家这边只记"我在这张图
 * 选了哪几项"。管理员从目录里删掉一把枪之后，所有选了它的玩家会自动回落到目录首项
 * （见 {@link #resolve}），不需要遍历改写任何玩家存档——存快照就必须做这件事，且漏改一处就会
 * 让玩家出生时拿到一把已下架的枪。
 *
 * @param picks 槽位 → 选中项 ID；没做过选择的槽位不出现在 map 里
 */
public record PlayerArenaLoadout(Map<LoadoutSlot, String> picks) {

    /** 没做过任何选择的空配装。 */
    public static final PlayerArenaLoadout EMPTY = new PlayerArenaLoadout(Map.of());

    public PlayerArenaLoadout {
        Objects.requireNonNull(picks, "picks must not be null");
        Map<LoadoutSlot, String> copy = new EnumMap<>(LoadoutSlot.class);
        for (Map.Entry<LoadoutSlot, String> e : picks.entrySet()) {
            Objects.requireNonNull(e.getKey(), "slot must not be null");
            String id = e.getValue();
            if (id != null && !id.isBlank()) {
                copy.put(e.getKey(), id);
            }
        }
        picks = Collections.unmodifiableMap(copy);
    }

    /** 该槽位选中的 ID；没选过返回 {@code null}。 */
    @Nullable
    public String pick(LoadoutSlot slot) {
        return picks.get(slot);
    }

    /** 返回把某个槽位改成指定 ID 的新实例；传空 ID 等同于清除该槽位的选择。 */
    public PlayerArenaLoadout with(LoadoutSlot slot, @Nullable String id) {
        Objects.requireNonNull(slot, "slot must not be null");
        Map<LoadoutSlot, String> next = new EnumMap<>(LoadoutSlot.class);
        next.putAll(picks);
        if (id == null || id.isBlank()) {
            next.remove(slot);
        } else {
            next.put(slot, id);
        }
        return new PlayerArenaLoadout(next);
    }

    /**
     * 丢掉在当前目录里已不合法的选择，返回新实例。
     *
     * <p>用于目录变更后收敛玩家存档，避免存档里长期堆积早已下架的 ID。与 {@link #resolve} 的分工：
     * 这个方法负责<b>写回存档</b>时的清理，那个方法负责<b>读取生效值</b>时的回落，
     * 后者不改存档，因此即便管理员误删又加回来，玩家的原选择也还在。
     */
    public PlayerArenaLoadout sanitize(ArenaCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Map<LoadoutSlot, String> next = new EnumMap<>(LoadoutSlot.class);
        for (Map.Entry<LoadoutSlot, String> e : picks.entrySet()) {
            if (catalog.hasOption(e.getKey(), e.getValue())) {
                next.put(e.getKey(), e.getValue());
            }
        }
        return next.size() == picks.size() ? this : new PlayerArenaLoadout(next);
    }

    /**
     * 解析出实际生效的配装：每个槽位优先用玩家的选择，选择失效或没选过则用目录首项。
     *
     * @return 槽位 → 生效 ID，按 {@link LoadoutSlot} 声明顺序；目录里该槽位一项都没配则该槽位缺席
     */
    public Map<LoadoutSlot, String> resolve(ArenaCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Map<LoadoutSlot, String> out = new LinkedHashMap<>();
        for (LoadoutSlot slot : LoadoutSlot.values()) {
            String picked = picks.get(slot);
            String effective = catalog.hasOption(slot, picked) ? picked : catalog.defaultOptionForSlot(slot);
            if (effective != null) {
                out.put(slot, effective);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 是否一个槽位都没选过。 */
    public boolean isEmpty() {
        return picks.isEmpty();
    }
}
