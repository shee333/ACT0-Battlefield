package org.shee33.act0.battlefield.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.LoadoutPresetDef;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.integration.TaczGunBridge;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * {@code /aew1 loadout}：管理员预设配装（玩家只选不编）。
 *
 * <p>流程：create 创建空配装 → slot 手持物品上架到槽位（枪械槽自动校验 TaCZ 枪 + 可选虚拟弹药）→
 * armor 上架身穿服装 → rename 改名；配装 id 创建时生成、之后不变。
 *
 * <p>配装按 地图(当前世界) × 阵营 × 兵种 分组，存在 {@link BattlefieldData} 里随地图落盘。
 */
public final class LoadoutCommand {

    private LoadoutCommand() {
    }

    private static final SuggestionProvider<CommandSourceStack> FACTIONS =
            (c, b) -> SharedSuggestionProvider.suggest(new String[]{"ALPHA", "BRAVO"}, b);
    private static final SuggestionProvider<CommandSourceStack> CLASSES =
            (c, b) -> SharedSuggestionProvider.suggest(
                    java.util.Arrays.stream(SoldierClass.values()).map(SoldierClass::id).toList(), b);

    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("loadout")
                .then(Commands.literal("create")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests(FACTIONS)
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .suggests(CLASSES)
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(LoadoutCommand::create)))))
                .then(Commands.literal("slot")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests(FACTIONS)
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .suggests(CLASSES)
                                        .then(Commands.argument("preset", StringArgumentType.word())
                                                .then(Commands.argument("slot", StringArgumentType.word())
                                                        .executes(c -> slot(c, 0))
                                                        .then(Commands.argument("ammo", IntegerArgumentType.integer(0, 999))
                                                                .executes(c -> slot(c, IntegerArgumentType.getInteger(c, "ammo")))))))))
                .then(Commands.literal("armor")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests(FACTIONS)
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .suggests(CLASSES)
                                        .then(Commands.argument("preset", StringArgumentType.word())
                                                .executes(LoadoutCommand::armor)))))
                .then(Commands.literal("rename")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests(FACTIONS)
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .suggests(CLASSES)
                                        .then(Commands.argument("preset", StringArgumentType.word())
                                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(LoadoutCommand::rename))))))
                .then(Commands.literal("delete")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests(FACTIONS)
                                .then(Commands.argument("class", StringArgumentType.word())
                                        .suggests(CLASSES)
                                        .then(Commands.argument("preset", StringArgumentType.word())
                                                .executes(LoadoutCommand::delete)))))
                .then(Commands.literal("list")
                        .executes(LoadoutCommand::listAll));
    }

    private static int create(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        Faction faction = parseFaction(c, "faction");
        SoldierClass soldierClass = SoldierClass.byId(StringArgumentType.getString(c, "class"));
        if (faction == null || soldierClass == null) {
            BattlefieldCommand.feedback(c, "§c阵营或兵种无效。");
            return 0;
        }
        String name = StringArgumentType.getString(c, "name").trim();
        if (name.isEmpty()) {
            BattlefieldCommand.feedback(c, "§c配装名不能为空。");
            return 0;
        }
        LoadoutPresetDef def = BattlefieldData.get(level).createPreset(faction, soldierClass, name);
        BattlefieldCommand.feedback(c, "§a已创建 §f" + faction.name() + "§7/§f" + soldierClass.displayName()
                + "§a 配装 §e" + name + "§a，id=§f" + def.id());
        return 1;
    }

    private static int slot(CommandContext<CommandSourceStack> c, int ammo) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Faction faction = parseFaction(c, "faction");
        SoldierClass soldierClass = SoldierClass.byId(StringArgumentType.getString(c, "class"));
        String presetId = StringArgumentType.getString(c, "preset");
        LoadoutSlot slot = parseSlot(StringArgumentType.getString(c, "slot"));
        if (faction == null || soldierClass == null || slot == null) {
            BattlefieldCommand.feedback(c, "§c阵营/兵种/槽位无效（槽位：主武器/副武器/道具/投掷物）。");
            return 0;
        }
        BattlefieldData data = BattlefieldData.get(level);
        LoadoutPresetDef def = data.preset(faction, soldierClass, presetId);
        if (def == null) {
            BattlefieldCommand.feedback(c, "§c未找到配装 §f" + presetId);
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            BattlefieldCommand.feedback(c, "§c请手持要上架的物品。");
            return 0;
        }
        String itemId;
        if (LoadoutPresetDef.isWeaponSlot(slot)) {
            if (!TaczGunBridge.isGun(held)) {
                BattlefieldCommand.feedback(c, "§c武器槽只能上架 TaCZ 枪械（当前手持不是枪）。");
                return 0;
            }
            // 枪械槽存"枪械 ID"而不是物品注册 ID：TaCZ 所有枪共用一个 base item
            // （tacz:modern_kinetic_gun），存物品 ID 会把枪的真实型号丢掉——玩家出生拿不到枪，
            // 部署/配装界面也只会显示 item.tacz.modern_kinetic_gun。
            itemId = TaczGunBridge.gunId(held);
            if (itemId == null) {
                BattlefieldCommand.feedback(c, "§c无法读取当前枪械的 ID，无法上架。");
                return 0;
            }
        } else {
            net.minecraft.resources.ResourceLocation key = ForgeRegistries.ITEMS.getKey(held.getItem());
            itemId = key == null ? null : key.toString();
            if (itemId == null) {
                BattlefieldCommand.feedback(c, "§c手持物品无注册 ID，无法上架。");
                return 0;
            }
        }
        LoadoutPresetDef next = def.withSlot(slot, itemId);
        if (LoadoutPresetDef.isWeaponSlot(slot) && ammo > 0) {
            next = next.withAmmo(slot, ammo);
        }
        data.savePresetDef(faction, soldierClass, next);
        BattlefieldCommand.feedback(c, "§a已将 §f" + slot.displayName() + "§a 设为 §e" + itemId
                + (ammo > 0 ? "§a（虚拟弹药 §f" + ammo + "§a）" : ""));
        return 1;
    }

    private static int armor(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Faction faction = parseFaction(c, "faction");
        SoldierClass soldierClass = SoldierClass.byId(StringArgumentType.getString(c, "class"));
        String presetId = StringArgumentType.getString(c, "preset");
        if (faction == null || soldierClass == null) {
            BattlefieldCommand.feedback(c, "§c阵营或兵种无效。");
            return 0;
        }
        BattlefieldData data = BattlefieldData.get(level);
        LoadoutPresetDef def = data.preset(faction, soldierClass, presetId);
        if (def == null) {
            BattlefieldCommand.feedback(c, "§c未找到配装 §f" + presetId);
            return 0;
        }
        LoadoutPresetDef.ArmorSet armor = new LoadoutPresetDef.ArmorSet(
                armorId(player, EquipmentSlot.HEAD), armorId(player, EquipmentSlot.CHEST),
                armorId(player, EquipmentSlot.LEGS), armorId(player, EquipmentSlot.FEET));
        data.savePresetDef(faction, soldierClass, def.withArmor(armor));
        BattlefieldCommand.feedback(c, "§a已上架当前身穿服装（头/胸/腿/脚）。");
        return 1;
    }

    @Nullable
    private static String armorId(ServerPlayer player, EquipmentSlot slot) {
        ItemStack stack = player.getItemBySlot(slot);
        net.minecraft.resources.ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return stack.isEmpty() || key == null ? null : key.toString();
    }

    private static int rename(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        Faction faction = parseFaction(c, "faction");
        SoldierClass soldierClass = SoldierClass.byId(StringArgumentType.getString(c, "class"));
        String presetId = StringArgumentType.getString(c, "preset");
        String name = StringArgumentType.getString(c, "name").trim();
        if (faction == null || soldierClass == null) {
            BattlefieldCommand.feedback(c, "§c阵营或兵种无效。");
            return 0;
        }
        BattlefieldData data = BattlefieldData.get(level);
        LoadoutPresetDef def = data.preset(faction, soldierClass, presetId);
        if (def == null) {
            BattlefieldCommand.feedback(c, "§c未找到配装 §f" + presetId);
            return 0;
        }
        data.savePresetDef(faction, soldierClass, def.withDisplayName(name));
        BattlefieldCommand.feedback(c, "§a配装 §f" + presetId + "§a 已改名为 §e" + name);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        Faction faction = parseFaction(c, "faction");
        SoldierClass soldierClass = SoldierClass.byId(StringArgumentType.getString(c, "class"));
        String presetId = StringArgumentType.getString(c, "preset");
        if (faction == null || soldierClass == null) {
            BattlefieldCommand.feedback(c, "§c阵营或兵种无效。");
            return 0;
        }
        boolean removed = BattlefieldData.get(level).deletePresetDef(faction, soldierClass, presetId);
        BattlefieldCommand.feedback(c, removed
                ? "§a已删除配装 §f" + presetId
                : "§c未找到配装 §f" + presetId);
        return removed ? 1 : 0;
    }

    private static int listAll(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = c.getSource().getPlayerOrException().serverLevel();
        BattlefieldData data = BattlefieldData.get(level);
        boolean any = false;
        for (Faction faction : Faction.values()) {
            for (SoldierClass soldierClass : SoldierClass.values()) {
                List<LoadoutPresetDef> presets = data.presetsFor(faction, soldierClass);
                if (presets.isEmpty()) {
                    continue;
                }
                any = true;
                BattlefieldCommand.feedback(c, "§7—— §f" + faction.name() + "§7/§f" + soldierClass.displayName() + "§7 ——");
                for (LoadoutPresetDef def : presets) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("§e").append(def.displayName()).append("§7 id=§f").append(def.id());
                    for (LoadoutSlot slot : LoadoutPresetDef.PRESET_SLOTS) {
                        String itemId = def.slot(slot);
                        if (itemId != null) {
                            sb.append(" §7| ").append(slot.displayName()).append(":§f").append(itemId);
                            if (def.ammoOf(slot) > 0) {
                                sb.append("§8(弹").append(def.ammoOf(slot)).append(")");
                            }
                        }
                    }
                    BattlefieldCommand.feedback(c, sb.toString());
                }
            }
        }
        if (!any) {
            BattlefieldCommand.feedback(c, "§7本图还没有任何配装预设。用 §f/aew1 loadout create §7开始。");
        }
        return 1;
    }

    @Nullable
    private static Faction parseFaction(CommandContext<CommandSourceStack> c, String arg) {
        try {
            return Faction.valueOf(StringArgumentType.getString(c, arg).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 槽位解析：支持英文 id 与中文别名（主武器/副武器/道具/投掷物）。 */
    @Nullable
    private static LoadoutSlot parseSlot(String raw) {
        LoadoutSlot byId = LoadoutSlot.byId(raw);
        if (byId != null) {
            return byId;
        }
        switch (raw.trim()) {
            case "主武器":
                return LoadoutSlot.PRIMARY;
            case "副武器":
                return LoadoutSlot.SECONDARY;
            case "道具":
                return LoadoutSlot.GADGET_1;
            case "投掷物":
                return LoadoutSlot.GADGET_2;
            default:
                return null;
        }
    }

    /** 注册时供 Aew1Command 引用的入口。 */
    public static void attachTo(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(tree());
    }
}
