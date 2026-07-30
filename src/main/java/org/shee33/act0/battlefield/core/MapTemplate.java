package org.shee33.act0.battlefield.core;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 地图模板元数据。MC-free 数据类。
 *
 * @param name        模板名称（仅允许字母数字和下划线）
 * @param regionPath  模板 region 文件目录路径
 * @param createdAt   模板创建时间
 */
public record MapTemplate(String name, Path regionPath, Instant createdAt) {
    public MapTemplate {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(regionPath, "regionPath must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (!name.matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("name must be alphanumeric+underscore: " + name);
    }

    /** 用于列表显示的简短描述。 */
    @Override
    public String toString() {
        return name + " (" + createdAt + ")";
    }
}