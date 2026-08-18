package org.shee33.act0.battlefield.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog;
import org.shee33.act0.battlefield.core.arena.ArenaCatalog.EditResult;
import org.shee33.act0.battlefield.core.arena.ArenaItemEntry;
import org.shee33.act0.battlefield.core.arena.ArenaWeaponEntry;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.core.arena.WeaponCategory;
import org.shee33.act0.battlefield.data.ArenaCatalogStore;
import org.shee33.act0.battlefield.data.ArenaKey;
import org.shee33.act0.battlefield.integration.TaczGunBridge;

import javax.annotation.Nullable;

/**
 * {@code /aew1 arena <地图名> ...}：逐图配置武器与道具池。
 *
 * <p><b>录入方式是"把手上的枪上架"</b>而不是手打 ID：管理员本来就要拿着枪确认手感，让他再去
 * 别处抄一串 {@code tacz:xxx} 只会抄错。录入时只取枪械 ID 与显示名，<b>不</b>保存整个物品——
 * 否则管理员那把枪身上的配件、弹药、耐久都会被复制给每一个出生的玩家。
 *
 * <p>地图名必须是已知地图（已加载世界的名字或已配置过的目录键），打错直接报错而不是静默新建一套
 * 没有任何世界会去读的武器池。
 */
public final class ArenaCommand {

    private ArenaCommand() {
    }

    /** 不带 {@code dummy_ammo} 参数时传入的哨兵值，表示"按这把枪的弹匣容量推导"。 */
    private static final int DERIVE_RESERVE = -1;

    /**
     * 推导默认备弹时给几个备用弹匣。
     *
     * <p>取 3 是战地系列的惯例手感：出生时枪里一个满弹匣，身上三个备用。写死一个绝对数字
     * （比如 120）对狙击枪是二十多个弹匣、对机枪却不到一个，必然有一边荒谬。
     */
    private static final int DEFAULT_SPARE_MAGAZINES = 3;

    /**
     * 地图名补全。
     *
     * <p>必须走 {@link StringArgumentType#escapeIfRequired}：未命名地图的兜底键是维度 ID
     * （形如 {@code minecraft:overworld}），其中的冒号不在 Brigadier 无引号词的合法字符集内。
     * 直接把裸串塞进补全，玩家一按 Tab 得到的就是一条<b>解析不了</b>的命令
     * （"Expected whitespace to end one argument"），而错误信息完全不会提示"要加引号"。
     */
    private static final SuggestionProvider<CommandSourceStack> MAP_NAMES =
            (ctx, builder) -> SharedSuggestionProvider.suggest(
                    ArenaKey.knownNames(ctx.getSource().getServer()).stream()
                            .map(StringArgumentType::escapeIfRequired)
                            .toList(),
                    builder);

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("arena")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("list").executes(ArenaCommand::listArenas))
                .then(Commands.argument("map", StringArgumentType.string()).suggests(MAP_NAMES)
                        .then(Commands.literal("info").executes(ArenaCommand::info))
                        .then(weaponBranch())
                        .then(itemBranch()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> weaponBranch() {
        LiteralArgumentBuilder<CommandSourceStack> weapon = Commands.literal("weapon");
        for (WeaponCategory category : WeaponCategory.values()) {
            weapon.then(Commands.literal(category.id())
                    .then(Commands.literal("add")
                            .executes(c -> addWeapon(c, category, DERIVE_RESERVE))
                            .then(Commands.argument("dummy_ammo",
                                            IntegerArgumentType.integer(0, ArenaWeaponEntry.MAX_DUMMY_AMMO))
                                    .executes(c -> addWeapon(c, category,
                                            IntegerArgumentType.getInteger(c, "dummy_ammo")))))
                    .then(Commands.literal("list").executes(c -> listWeapons(c, category)))
                    .then(Commands.literal("remove")
                            .then(Commands.argument("gun_id", StringArgumentType.greedyString())
                                    .executes(c -> removeWeapon(c, category,
                                            StringArgumentType.getString(c, "gun_id"))))));
        }
        return weapon;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> itemBranch() {
        LiteralArgumentBuilder<CommandSourceStack> item = Commands.literal("item");
        for (LoadoutSlot slot : LoadoutSlot.gadgetSlots()) {
            item.then(Commands.literal(slot.id())
                    .then(Commands.literal("add")
                            .executes(c -> addItem(c, slot, 1))
                            .then(Commands.argument("count",
                                            IntegerArgumentType.integer(1, ArenaItemEntry.MAX_COUNT))
                                    .executes(c -> addItem(c, slot,
                                            IntegerArgumentType.getInteger(c, "count")))))
                    .then(Commands.literal("list").executes(c -> listItems(c, slot)))
                    .then(Commands.literal("remove")
                            .then(Commands.argument("item_id", StringArgumentType.greedyString())
                                    .executes(c -> removeItem(c, slot,
                                            StringArgumentType.getString(c, "item_id"))))));
        }
        return item;
    }

    // ---- 处理器 ----

    private static int listArenas(CommandContext<CommandSourceStack> c) {
        ArenaCatalogStore store = ArenaCatalogStore.get(c.getSource().getServer());
        BattlefieldCommand.feedback(c, "§b已知地图：");
        for (String name : ArenaKey.knownNames(c.getSource().getServer())) {
            int n = store.view(name).totalEntries();
            BattlefieldCommand.feedback(c, "  §f" + name + " §7— " + (n == 0 ? "未配置军械库" : n + " 项装备"));
        }
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        String map = requireMap(c);
        if (map == null) {
            return 0;
        }
        ArenaCatalog catalog = ArenaCatalogStore.get(c.getSource().getServer()).view(map);
        BattlefieldCommand.feedback(c, "§b【" + map + "】军械库 §7共 " + catalog.totalEntries() + " 项");
        for (WeaponCategory category : WeaponCategory.values()) {
            int n = catalog.weapons(category).size();
            if (n > 0) {
                BattlefieldCommand.feedback(c, "  §f" + category.displayName() + " §7× " + n);
            }
        }
        for (LoadoutSlot slot : LoadoutSlot.gadgetSlots()) {
            int n = catalog.items(slot).size();
            if (n > 0) {
                BattlefieldCommand.feedback(c, "  §f" + slot.displayName() + " §7× " + n);
            }
        }
        return 1;
    }

    private static int addWeapon(CommandContext<CommandSourceStack> c, WeaponCategory category, int dummyAmmo)
            throws CommandSyntaxException {
        String map = requireMap(c);
        if (map == null) {
            return 0;
        }
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(c, "§c主手是空的——请手持要上架的枪械再执行。");
        }
        String gunId = TaczGunBridge.gunId(held);
        if (gunId == null) {
            return fail(c, "§c主手不是 TaCZ 枪械，读不出枪械 ID。");
        }
        int reserve = dummyAmmo >= 0 ? dummyAmmo : deriveReserve(gunId);
        ArenaCatalogStore store = ArenaCatalogStore.get(c.getSource().getServer());
        EditResult result = store.addWeapon(map, category,
                new ArenaWeaponEntry(gunId, held.getHoverName().getString(), reserve));
        return switch (result) {
            case OK -> ok(c, "§a已上架 §f" + gunId + " §7→ 【" + map + "】" + category.displayName()
                    + describeReserve(reserve, dummyAmmo < 0));
            case DUPLICATE -> fail(c, "§c这把枪已在本图的 §f"
                    + describeCategory(store.view(map).categoryOf(gunId)) + " §c池里。");
            case FULL -> fail(c, "§c【" + map + "】" + category.slot().displayName()
                    + "可选项已满（上限 " + ArenaCatalog.MAX_PER_SLOT + "）。");
            default -> fail(c, "§c上架失败。");
        };
    }

    private static int listWeapons(CommandContext<CommandSourceStack> c, WeaponCategory category) {
        String map = requireMap(c);
        if (map == null) {
            return 0;
        }
        var entries = ArenaCatalogStore.get(c.getSource().getServer()).view(map).weapons(category);
        if (entries.isEmpty()) {
            return ok(c, "§7【" + map + "】" + category.displayName() + "池是空的。");
        }
        BattlefieldCommand.feedback(c, "§b【" + map + "】" + category.displayName() + "池：");
        for (ArenaWeaponEntry e : entries) {
            BattlefieldCommand.feedback(c, "  §f" + e.gunId() + " §7" + e.displayName()
                    + (e.usesDummyAmmo() ? " §8备弹 " + e.dummyAmmo() : " §8背包弹药"));
        }
        return 1;
    }

    private static int removeWeapon(CommandContext<CommandSourceStack> c, WeaponCategory category, String gunId) {
        String map = requireMap(c);
        if (map == null) {
            return 0;
        }
        EditResult result = ArenaCatalogStore.get(c.getSource().getServer())
                .removeWeapon(map, category, gunId.trim());
        return result == EditResult.OK
                ? ok(c, "§a已从【" + map + "】" + category.displayName() + "池下架 §f" + gunId)
                : fail(c, "§c【" + map + "】" + category.displayName() + "池里没有 §f" + gunId);
    }

    private static int addItem(CommandContext<CommandSourceStack> c, LoadoutSlot slot, int count)
            throws CommandSyntaxException {
        String map = requireMap(c);
        if (map == null) {
            return 0;
        }
        ServerPlayer player = c.getSource().getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return fail(c, "§c主手是空的——请手持要上架的道具再执行。");
        }
        if (TaczGunBridge.gunId(held) != null) {
            return fail(c, "§c枪械请用 §fweapon §c子命令上架，道具槽只放普通物品。");
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(held.getItem());
        if (id == null) {
            return fail(c, "§c读不出该物品的注册 ID。");
        }
        EditResult result = ArenaCatalogStore.get(c.getSource().getServer()).addItem(map, slot,
                new ArenaItemEntry(id.toString(), held.getHoverName().getString(), count));
        return switch (result) {
            case OK -> ok(c, "§a已上架 §f" + id + " §7×" + count + " → 【" + map + "】" + slot.displayName());
            case DUPLICATE -> fail(c, "§c【" + map + "】" + slot.displayName() + "里已经有这件道具。");
            case FULL -> fail(c, "§c【" + map + "】" + slot.displayName()
                    + "可选项已满（上限 " + ArenaCatalog.MAX_PER_SLOT + "）。");
            default -> fail(c, "§c上架失败。");
        };
    }

    private static int listItems(CommandContext<CommandSourceStack> c, LoadoutSlot slot) {
        String map = requireMap(c);
        if (map == null) {
            return 0;
        }
        var entries = ArenaCatalogStore.get(c.getSource().getServer()).view(map).items(slot);
        if (entries.isEmpty()) {
            return ok(c, "§7【" + map + "】" + slot.displayName() + "是空的。");
        }
        BattlefieldCommand.feedback(c, "§b【" + map + "】" + slot.displayName() + "：");
        for (ArenaItemEntry e : entries) {
            BattlefieldCommand.feedback(c, "  §f" + e.itemId() + " §7" + e.displayName() + " §8×" + e.count());
        }
        return 1;
    }

    private static int removeItem(CommandContext<CommandSourceStack> c, LoadoutSlot slot, String itemId) {
        String map = requireMap(c);
        if (map == null) {
            return 0;
        }
        EditResult result = ArenaCatalogStore.get(c.getSource().getServer())
                .removeItem(map, slot, itemId.trim());
        return result == EditResult.OK
                ? ok(c, "§a已从【" + map + "】" + slot.displayName() + "下架 §f" + itemId)
                : fail(c, "§c【" + map + "】" + slot.displayName() + "里没有 §f" + itemId);
    }

    // ---- 工具 ----

    @Nullable
    private static String requireMap(CommandContext<CommandSourceStack> c) {
        String input = StringArgumentType.getString(c, "map");
        String resolved = ArenaKey.resolve(c.getSource().getServer(), input);
        if (resolved == null) {
            c.getSource().sendFailure(Component.literal(
                    "§c没有名为 §f" + input + " §c的地图。用 §f/aew1 arena list §c查看已知地图。"));
        }
        return resolved;
    }

    /**
     * 未指定备弹时按弹匣容量推导；查不到弹匣容量则退回 0。
     *
     * <p>退回 0 意味着这把枪只有出厂的那一个弹匣、打完无弹可换，因此
     * {@link #describeReserve} 会在这种情况下明确提示管理员手动指定。
     */
    private static int deriveReserve(String gunId) {
        int magazine = TaczGunBridge.magazineSize(gunId);
        return magazine > 0
                ? Math.min(magazine * DEFAULT_SPARE_MAGAZINES, ArenaWeaponEntry.MAX_DUMMY_AMMO)
                : 0;
    }

    private static String describeReserve(int reserve, boolean derived) {
        if (reserve > 0) {
            return "，备弹 " + reserve + (derived ? "（按 " + DEFAULT_SPARE_MAGAZINES + " 个备用弹匣推导）" : "");
        }
        return derived
                ? " §e备弹 0：读不到该枪弹匣容量，请用 §fadd <备弹数> §e手动指定，否则打完一匣就没子弹了"
                : "，备弹 0（打完出厂弹匣即无弹可换）";
    }

    private static String describeCategory(@Nullable WeaponCategory category) {
        return category == null ? "未知" : category.displayName();
    }

    private static int ok(CommandContext<CommandSourceStack> c, String msg) {
        BattlefieldCommand.feedback(c, msg);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendFailure(Component.literal(msg));
        return 0;
    }
}
