package org.shee33.act0.battlefield.match;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.shee33.act0.battlefield.core.BattleArea;
import org.shee33.act0.battlefield.core.MapTemplate;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 地图模板文件管理。负责模板的保存、列表、删除和路径解析。
 *
 * <p>模板以目录形式存储于 {@code config/act0_battlefield/templates/} 下，
 * 每个模板目录包含一个 {@code region/} 子目录，存放 Minecraft Anvil {@code .mca} 区域文件。
 *
 * <p>文件操作基于 Java NIO，不依赖 Minecraft 内部文件 API。
 */
public final class MapTemplateManager {

    /** 默认模板根路径：{@code config/act0_battlefield/templates} */
    public static final Path DEFAULT_BASE_PATH = Path.of("config", "act0_battlefield", "templates");

    private MapTemplateManager() {}

    /**
     * 将 ServerLevel 中指定战斗区域内的 region 文件保存为模板。
     *
     * <p>转换 BattleArea 方块坐标到 region 坐标（block >> 9），
     * 仅复制与区域相交的 {@code .mca} 文件。
     *
     * @param name     模板名称（字母数字+下划线）
     * @param level    源 ServerLevel
     * @param area     战斗区域（必须已设置）
     * @param basePath 模板根路径
     * @throws IOException              文件操作失败时抛出
     * @throws IllegalArgumentException 参数无效时抛出
     */
    public static void saveTemplate(String name, ServerLevel level, BattleArea area, Path basePath) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(area, "area must not be null");
        Objects.requireNonNull(basePath, "basePath must not be null");
        if (!area.isSet()) {
            throw new IllegalArgumentException("BattleArea must be set (non-empty)");
        }

        Path targetRegion = templateRegionPath(name, basePath);
        Files.createDirectories(targetRegion);

        Path sourceRegion = resolveLevelRegionPath(level);
        if (!Files.isDirectory(sourceRegion)) {
            return;
        }

        int minRegionX = blockToRegion(area.minX());
        int minRegionZ = blockToRegion(area.minZ());
        int maxRegionX = blockToRegion(area.maxX());
        int maxRegionZ = blockToRegion(area.maxZ());

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sourceRegion, "r.*.*.mca")) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                int[] coords = parseRegionCoords(fileName);
                if (coords == null) {
                    continue;
                }
                int rx = coords[0];
                int rz = coords[1];
                if (rx >= minRegionX && rx <= maxRegionX && rz >= minRegionZ && rz <= maxRegionZ) {
                    Files.copy(file, targetRegion.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 列出 {@code basePath} 下所有模板。
     *
     * <p>仅返回包含 {@code region/} 子目录的条目，按名称排序。
     * 如果 {@code basePath} 不存在则返回空列表。
     *
     * @param basePath 模板根路径
     * @return 模板列表（按名称字母排序）
     * @throws IOException 目录遍历失败时抛出
     */
    public static List<MapTemplate> listTemplates(Path basePath) throws IOException {
        Objects.requireNonNull(basePath, "basePath must not be null");
        List<MapTemplate> templates = new ArrayList<>();

        if (!Files.isDirectory(basePath)) {
            return templates;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(basePath, Files::isDirectory)) {
            for (Path dir : stream) {
                Path regionDir = dir.resolve("region");
                if (!Files.isDirectory(regionDir)) {
                    continue;
                }
                String name = dir.getFileName().toString();
                Instant createdAt = readModificationTime(regionDir);
                templates.add(new MapTemplate(name, regionDir, createdAt));
            }
        }

        templates.sort(Comparator.comparing(MapTemplate::name));
        return templates;
    }

    /**
     * 删除指定名称的模板目录（递归删除全部内容）。
     *
     * <p>如果模板目录不存在则静默返回。
     *
     * @param name     模板名称
     * @param basePath 模板根路径
     * @throws IOException 删除失败时抛出
     */
    public static void deleteTemplate(String name, Path basePath) throws IOException {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(basePath, "basePath must not be null");

        Path templateDir = basePath.resolve(name);
        if (!Files.exists(templateDir)) {
            return;
        }
        deleteRecursively(templateDir);
    }

    /**
     * 返回模板的 region 目录路径。
     *
     * @param name     模板名称
     * @param basePath 模板根路径
     * @return {@code basePath/<name>/region}
     */
    public static Path templateRegionPath(String name, Path basePath) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(basePath, "basePath must not be null");
        return basePath.resolve(name).resolve("region");
    }

    // ── private helpers ────────────────────────────────────────────

    /** 方块坐标 → region 坐标（block >> 9），正确处���负坐标。 */
    private static int blockToRegion(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), 512);
    }

    /**
     * 解析 region 文件名中的坐标。
     *
     * @param fileName 文件名，格式 {@code r.<x>.<z>.mca}
     * @return {@code [regionX, regionZ]} 或 {@code null}（格式不匹配）
     */
    @Nullable
    private static int[] parseRegionCoords(String fileName) {
        if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) {
            return null;
        }
        String core = fileName.substring(2, fileName.length() - 4);
        int dotIdx = core.indexOf('.');
        if (dotIdx < 0) {
            return null;
        }
        try {
            int rx = Integer.parseInt(core.substring(0, dotIdx));
            int rz = Integer.parseInt(core.substring(dotIdx + 1));
            return new int[]{rx, rz};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 根据 ServerLevel 的维度计算其 region 文件目录路径。
     *
     * <p>遵循 Minecraft 世界存档规范：
     * <ul>
     *   <li>主世界 → {@code <save>/region}</li>
     *   <li>下界   → {@code <save>/DIM-1/region}</li>
     *   <li>末地   → {@code <save>/DIM1/region}</li>
     *   <li>自定义维度 → {@code <save>/dimensions/<namespace>/<id>/region}</li>
     * </ul>
     */
    private static Path resolveLevelRegionPath(ServerLevel level) {
        ResourceLocation dimLoc = level.dimension().location();
        String dimPath;
        if (dimLoc.equals(Level.OVERWORLD.location())) {
            dimPath = "region";
        } else if (dimLoc.equals(Level.NETHER.location())) {
            dimPath = "DIM-1/region";
        } else if (dimLoc.equals(Level.END.location())) {
            dimPath = "DIM1/region";
        } else {
            dimPath = "dimensions/" + dimLoc.getNamespace() + "/" + dimLoc.getPath() + "/region";
        }
        return level.getServer().getWorldPath(new LevelResource(dimPath));
    }

    /** 读取目录的最后修改时间，失败时返回 {@link Instant#EPOCH}。 */
    private static Instant readModificationTime(Path dir) {
        try {
            FileTime fileTime = Files.getLastModifiedTime(dir);
            return fileTime.toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /** 递归删除目录及其全部内容。 */
    private static void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(path -> {
                      try {
                          Files.delete(path);
                      } catch (IOException e) {
                          throw new UncheckedIOException(e);
                      }
                  });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
