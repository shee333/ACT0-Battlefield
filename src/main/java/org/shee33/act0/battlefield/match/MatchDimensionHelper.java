package org.shee33.act0.battlefield.match;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;

/**
 * Manages the pre-registered battlefield dimension via region-file swap.
 *
 * <p>The dimension {@code act0_battlefield:battlefield} is defined in the mod's built-in
 * datapack ({@code data/act0_battlefield/dimension/} and {@code dimension_type/}).
 * Before a match, {@link #prepareMatchDimension} copies template {@code .mca} files into
 * the dimension's region directory. After the match, {@link #cleanupMatchDimension}
 * deletes those files to reclaim disk space.
 *
 * <p>MVP limitation: the dimension's ServerLevel is obtained via
 * {@link MinecraftServer#getLevel(ResourceKey)}. If the dimension has not been created
 * yet (returns {@code null}), the caller should use a fallback dimension such as the
 * overworld. Full dynamic ServerLevel creation requires deeper Forge integration planned
 * for a later iteration.
 */
public final class MatchDimensionHelper {

    @SuppressWarnings("removal")
    public static final ResourceKey<Level> MATCH_DIMENSION =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation("act0_battlefield:battlefield"));

    /**
     * Copy template region files into the match dimension directory, replacing
     * any existing terrain. Call this <b>before</b> starting a match to get a
     * clean slate.
     *
     * @param server            the Minecraft server instance
     * @param templateRegionDir path to a directory containing template {@code .mca} files
     * @throws IOException if copying fails
     */
    public static void prepareMatchDimension(MinecraftServer server, Path templateRegionDir) throws IOException {
        Path matchRegion = resolveRegionPath(server);
        Files.createDirectories(matchRegion);

        deleteRegionFiles(matchRegion);

        if (Files.exists(templateRegionDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(templateRegionDir, "*.mca")) {
                for (Path file : stream) {
                    Files.copy(file, matchRegion.resolve(file.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Get the match dimension ServerLevel.
     *
     * @return the ServerLevel, or {@code null} if the dimension has not been
     *         created yet (caller should use the overworld as fallback)
     */
    @Nullable
    public static ServerLevel getMatchLevel(MinecraftServer server) {
        return server.getLevel(MATCH_DIMENSION);
    }

    /**
     * Configure the match dimension for gameplay: Peaceful difficulty prevents
     * hostile mobs from interfering with player-versus-player combat.
     */
    public static void configureMatchLevel(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
    }

    /**
     * Delete region files from the match dimension directory to reclaim disk
     * space after a match ends.
     *
     * @param server the Minecraft server instance
     * @throws IOException if file deletion fails
     */
    public static void cleanupMatchDimension(MinecraftServer server) throws IOException {
        Path matchRegion = resolveRegionPath(server);
        if (Files.exists(matchRegion)) {
            deleteRegionFiles(matchRegion);
        }
    }

    private static Path resolveRegionPath(MinecraftServer server) {
        return server.getWorldPath(
                new LevelResource("dimensions/act0_battlefield/battlefield/region"));
    }

    private static void deleteRegionFiles(Path regionDir) throws IOException {
        if (!Files.exists(regionDir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
            for (Path file : stream) {
                Files.delete(file);
            }
        }
    }

    private MatchDimensionHelper() {}
}
