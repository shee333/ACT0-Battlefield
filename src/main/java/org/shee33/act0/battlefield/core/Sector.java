package org.shee33.act0.battlefield.core;

import java.util.List;
import java.util.Objects;

/**
 * 突破模式的一个防御区域。每个区域包含 1-3 个据点，按顺序解锁。
 *
 * @param id          区域编号（从 0 开始递增）
 * @param pointIds    该区域包含的据点 ID 列表
 * @param displayName 区域显示名称
 */
public record Sector(int id, List<Integer> pointIds, String displayName) {
    public Sector {
        if (id < 0) throw new IllegalArgumentException("id must be >= 0: " + id);
        Objects.requireNonNull(pointIds, "pointIds must not be null");
        if (pointIds.isEmpty()) throw new IllegalArgumentException("pointIds must not be empty");
        Objects.requireNonNull(displayName, "displayName must not be null");
        if (displayName.isBlank()) throw new IllegalArgumentException("displayName must not be blank");
    }

    /** 该区域是否包含指定据点 ID。 */
    public boolean containsPoint(int pointId) {
        return pointIds.contains(pointId);
    }
}
