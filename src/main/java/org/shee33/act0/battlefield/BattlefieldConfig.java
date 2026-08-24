package org.shee33.act0.battlefield;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server-side configuration for ACT0-Battlefield, generated as a TOML file by Forge.
 *
 * <p>All gameplay constants previously hardcoded in {@code ConquestMatch} are now server-configurable
 * via {@code config/act0_battlefield-server.toml}. Defaults match the original hardcoded values exactly.
 */
public final class BattlefieldConfig {

    public static final ForgeConfigSpec SPEC;

    // ── gameplay ──────────────────────────────────────────
    public static final ForgeConfigSpec.IntValue CAPTURE_INTERVAL;
    public static final ForgeConfigSpec.IntValue HUD_INTERVAL;
    public static final ForgeConfigSpec.IntValue SQUAD_SIZE;
    public static final ForgeConfigSpec.IntValue START_COUNTDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue REDEPLOY_DELAY_TICKS;
    public static final ForgeConfigSpec.IntValue SPAWN_PROTECTION_TICKS;
    public static final ForgeConfigSpec.DoubleValue SQUAD_DEPLOY_ENEMY_BLOCK_RADIUS;
    public static final ForgeConfigSpec.IntValue IFF_SYNC_INTERVAL;
    public static final ForgeConfigSpec.DoubleValue ENEMY_MARK_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue ENEMY_MARK_VIEW_DOT;
    public static final ForgeConfigSpec.IntValue BREATH_HEAL_DELAY_TICKS;
    public static final ForgeConfigSpec.IntValue ESCAPE_BOUNDARY_TICKS;
    public static final ForgeConfigSpec.IntValue DOWNED_DURATION_TICKS;
    public static final ForgeConfigSpec.IntValue REVIVE_DURATION_TICKS;
    public static final ForgeConfigSpec.DoubleValue TICKET_PER_DEATH;
    public static final ForgeConfigSpec.IntValue MIN_PLAYERS_TO_START;
    public static final ForgeConfigSpec.IntValue MAX_PLAYERS;
    public static final ForgeConfigSpec.BooleanValue MINIMAP_NORTH_UP;

    // ── matchReturn ────────────────────────────────────────
    public static final ForgeConfigSpec.BooleanValue MATCH_RETURN_USE_CUSTOM_COORDS;
    public static final ForgeConfigSpec.DoubleValue MATCH_RETURN_X;
    public static final ForgeConfigSpec.DoubleValue MATCH_RETURN_Y;
    public static final ForgeConfigSpec.DoubleValue MATCH_RETURN_Z;
    public static final ForgeConfigSpec.DoubleValue MATCH_RETURN_YAW;
    public static final ForgeConfigSpec.DoubleValue MATCH_RETURN_PITCH;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("gameplay");
        CAPTURE_INTERVAL = builder
                .comment("Tick interval between capture progress updates (10 = 0.5s)")
                .defineInRange("captureIntervalTicks", 10, 1, 100);
        SQUAD_SIZE = builder
                .comment("Maximum players per squad (hard-capped at 4 by SquadManager.MAX_SQUAD_SIZE)")
                .defineInRange("squadSize", 4, 1, 16);
        START_COUNTDOWN_TICKS = builder
                .comment("Ticks before match starts after begin() (100 = 5 seconds)")
                .defineInRange("startCountdownTicks", 100, 20, 600);
        REDEPLOY_DELAY_TICKS = builder
                .comment("Ticks before a dead player can redeploy (100 = 5 seconds)")
                .defineInRange("redeployDelayTicks", 100, 0, 600);
        SPAWN_PROTECTION_TICKS = builder
                .comment("Ticks of spawn protection after deployment (60 = 3 seconds)")
                .defineInRange("spawnProtectionTicks", 60, 0, 600);
        SQUAD_DEPLOY_ENEMY_BLOCK_RADIUS = builder
                .comment("Block radius to scan for nearby enemies when squad-deploying")
                .defineInRange("squadDeployEnemyBlockRadius", 12.0, 0.0, 256.0);
        ENEMY_MARK_DISTANCE = builder
                .comment("Maximum distance (blocks) at which enemies are visible via IFF glow")
                .defineInRange("enemyMarkDistance", 96.0, 1.0, 512.0);
        ENEMY_MARK_VIEW_DOT = builder
                .comment("Minimum view-direction dot product for enemy mark (1.0 = direct look, 0.0 = any angle)")
                .defineInRange("enemyMarkViewDot", 0.30, 0.0, 1.0);
        BREATH_HEAL_DELAY_TICKS = builder
                .comment("Ticks after last hurt before breath-healing resumes (100 = 5 seconds)")
                .defineInRange("breathHealDelayTicks", 100, 0, 600);
        ESCAPE_BOUNDARY_TICKS = builder
                .comment("Ticks without damage before a player is considered 'escaped' from combat (200 = 10 seconds)")
                .defineInRange("escapeBoundaryTicks", 200, 20, 1200);
        DOWNED_DURATION_TICKS = builder
                .comment("Ticks a player remains downed before bleeding out (300 = 15 seconds)")
                .defineInRange("downedDurationTicks", 300, 20, 1200);
        REVIVE_DURATION_TICKS = builder
                .comment("Ticks required to revive a downed teammate (60 = 3 seconds)")
                .defineInRange("reviveDurationTicks", 60, 10, 600);
        TICKET_PER_DEATH = builder
                .comment("Tickets lost per player death (also configurable via ConquestRules)")
                .defineInRange("ticketPerDeath", 1.0, 0.0, 100.0);
        MIN_PLAYERS_TO_START = builder
                .comment("Default auto-start threshold (combined ALPHA+BRAVO lobby size) for worlds "
                        + "that have not set their own via '/aew1 map minplayers'. Match "
                        + "capacity is a separate setting, see maxPlayers.")
                .defineInRange("minPlayersToStart", 8, 2, 64);
        MAX_PLAYERS = builder
                .comment("Default match capacity (ALPHA+BRAVO combined) for worlds that have not "
                        + "set their own via '/aew1 map maxplayers'. Joining is refused once "
                        + "this many players are in the lobby or match.")
                .defineInRange("maxPlayers", 32, 2, 128);
        MINIMAP_NORTH_UP = builder
                .comment("Minimap orientation. false (default) = rotating map, forward is always up. "
                        + "true = fixed north-up map with a rotating player arrow.")
                .define("minimapNorthUp", false);
        builder.pop();

        builder.push("performance");
        IFF_SYNC_INTERVAL = builder
                .comment("Tick interval for IFF (Identify Friend/Foe) glow updates. Recommendation: 10 ticks (0.5s) provides a good balance of responsiveness and server load when combined with spatial-indexed ray tracing.")
                .defineInRange("iffSyncIntervalTicks", 2, 1, 40);
        HUD_INTERVAL = builder
                .comment("Tick interval for HUD scoreboard broadcast")
                .defineInRange("hudIntervalTicks", 10, 1, 100);
        builder.pop();

        builder.push("matchReturn");
        MATCH_RETURN_USE_CUSTOM_COORDS = builder
                .comment("Return location after a match ends or when leaving mid-match. "
                        + "true = teleport players to the custom coordinates below in the overworld; "
                        + "false (default) = teleport to the overworld world spawn.")
                .define("useCustomCoords", false);
        MATCH_RETURN_X = builder
                .comment("Custom return X (overworld). Only used when useCustomCoords = true.")
                .defineInRange("returnX", 0.0, -30000000.0, 30000000.0);
        MATCH_RETURN_Y = builder
                .comment("Custom return Y (overworld). Only used when useCustomCoords = true.")
                .defineInRange("returnY", 64.0, -64.0, 320.0);
        MATCH_RETURN_Z = builder
                .comment("Custom return Z (overworld). Only used when useCustomCoords = true.")
                .defineInRange("returnZ", 0.0, -30000000.0, 30000000.0);
        MATCH_RETURN_YAW = builder
                .comment("Custom return yaw (degrees). Only used when useCustomCoords = true.")
                .defineInRange("returnYaw", 0.0, -180.0, 180.0);
        MATCH_RETURN_PITCH = builder
                .comment("Custom return pitch (degrees). Only used when useCustomCoords = true.")
                .defineInRange("returnPitch", 0.0, -90.0, 90.0);
        builder.pop();

        SPEC = builder.build();
    }

    private BattlefieldConfig() {
    }
}
