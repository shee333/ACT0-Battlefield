package org.shee33.act0.battlefield.hologram;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Locale;

/** 管理大战场大厅中可右键打开界面的悬空字入口。 */
public final class BattlefieldEntranceHolograms {
    public static final BattlefieldEntranceHolograms INSTANCE = new BattlefieldEntranceHolograms();

    private static final String ROOT_TAG = "Act0BattlefieldHologramEntry";
    private static final String TAG_PREFIX = "Act0BattlefieldHologramEntry:";

    private BattlefieldEntranceHolograms() {
    }

    public static int create(ServerPlayer player, EntryType type) {
        ServerLevel level = player.serverLevel();
        ArmorStand stand = new ArmorStand(level, player.getX(), player.getY(), player.getZ());
        stand.setYRot(player.getYRot());
        stand.setCustomName(Component.literal(type.title()));
        stand.setCustomNameVisible(true);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.setNoBasePlate(true);
        stand.addTag(ROOT_TAG);
        stand.addTag(TAG_PREFIX + type.id());
        level.addFreshEntity(stand);
        player.sendSystemMessage(Component.literal("§a已创建悬空字入口：§f" + type.plainTitle()
                + " §7→ §e/" + type.command()));
        return 1;
    }

    public static int createAll(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double baseX = player.getX();
        double baseY = player.getY();
        double baseZ = player.getZ();
        float yaw = player.getYRot();
        int created = 0;
        for (EntryType type : EntryType.values()) {
            double offset = created * 0.55D;
            ArmorStand stand = new ArmorStand(level, baseX, baseY + offset, baseZ);
            stand.setYRot(yaw);
            stand.setCustomName(Component.literal(type.title()));
            stand.setCustomNameVisible(true);
            stand.setInvisible(true);
            stand.setInvulnerable(true);
            stand.setNoGravity(true);
            stand.setSilent(true);
            stand.setNoBasePlate(true);
            stand.addTag(ROOT_TAG);
            stand.addTag(TAG_PREFIX + type.id());
            level.addFreshEntity(stand);
            created++;
        }
        player.sendSystemMessage(Component.literal("§a已创建 §e" + created + " §a个 ACT0 悬空字入口。"));
        return created;
    }

    public static int clear(ServerPlayer player, double radius) {
        ServerLevel level = player.serverLevel();
        AABB box = player.getBoundingBox().inflate(radius);
        int[] removed = {0};
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, box,
                entity -> entity.getTags().contains(ROOT_TAG))) {
            stand.discard();
            removed[0]++;
        }
        player.sendSystemMessage(Component.literal("§a已清理附近 ACT0 悬空字入口 §e" + removed[0] + " §a个。"));
        return removed[0];
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EntryType type = entryType(event.getTarget());
        if (type == null) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), "/" + type.command());
    }

    private static EntryType entryType(Entity entity) {
        if (!(entity instanceof ArmorStand) || !entity.getTags().contains(ROOT_TAG)) {
            return null;
        }
        for (String tag : entity.getTags()) {
            if (tag.startsWith(TAG_PREFIX)) {
                return EntryType.byId(tag.substring(TAG_PREFIX.length()));
            }
        }
        return null;
    }

    public enum EntryType {
        BROWSER("browser", "§e§l打开游戏浏览器", "打开游戏浏览器", "arcade browse"),
        LOADOUT("loadout", "§a§l打开配装", "打开配装", "arcade loadout edit"),
        BATTLEFIELD("battlefield", "§b§l打开大战场模式界面", "打开大战场模式界面", "battlefield ui");

        private final String id;
        private final String title;
        private final String plainTitle;
        private final String command;

        EntryType(String id, String title, String plainTitle, String command) {
            this.id = id;
            this.title = title;
            this.plainTitle = plainTitle;
            this.command = command;
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        public String plainTitle() {
            return plainTitle;
        }

        public String command() {
            return command;
        }

        public static EntryType byId(String id) {
            String normalized = id.toLowerCase(Locale.ROOT);
            for (EntryType type : values()) {
                if (type.id.equals(normalized)) {
                    return type;
                }
            }
            return null;
        }
    }
}
