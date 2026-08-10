package org.shee33.act0.battlefield.reg;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.block.ControlPointBlock;
import org.shee33.act0.battlefield.item.AmmoBoxItem;
import org.shee33.act0.battlefield.item.BattlefieldTerminalItem;
import org.shee33.act0.battlefield.item.MedicBoxItem;
import org.shee33.act0.battlefield.item.MedicSyringeItem;

/**
 * 模组注册中心：据点标记方块、其 BlockItem 与一个创造物品栏分类。
 *
 * <p>据点标记方块为管理员布场用的"功能块"——硬度设为仅创造模式可破坏（类似命令方块），
 * 放置即在该维度登记一个据点，破坏即移除（见 {@link ControlPointBlock}）。
 */
public final class BattlefieldRegistry {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, Act0Battlefield.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, Act0Battlefield.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Act0Battlefield.MODID);

    /** 据点标记方块：管理员放置以界定一个据点。 */
    public static final RegistryObject<Block> CONTROL_POINT = BLOCKS.register("control_point",
            () -> new ControlPointBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(-1.0F, 3600000.0F) // 仅创造模式可破坏，防止战斗中被打掉
                    .lightLevel(s -> 7)
                    .noLootTable()));

    public static final RegistryObject<Item> CONTROL_POINT_ITEM = ITEMS.register("control_point",
            () -> new BlockItem(CONTROL_POINT.get(), new Item.Properties()));

    /** 战地终端：普通玩家右键打开大战场 GUI。 */
    public static final RegistryObject<Item> BATTLEFIELD_TERMINAL = ITEMS.register("battlefield_terminal",
            () -> new BattlefieldTerminalItem(new Item.Properties().stacksTo(1)));

    /** 工程兵弹药箱：右键部署，为范围内同阵营玩家补给主副武器备弹。 */
    public static final RegistryObject<Item> AMMO_BOX = ITEMS.register("ammo_box",
            () -> new AmmoBoxItem(new Item.Properties().stacksTo(1)));

    /** 支援兵医疗箱：右键部署，为范围内同阵营玩家回复血量。 */
    public static final RegistryObject<Item> MEDIC_BOX = ITEMS.register("medic_box",
            () -> new MedicBoxItem(new Item.Properties().stacksTo(1)));

    /** 医疗针：点击倒地的同阵营玩家，以 3 倍速将其救起。 */
    public static final RegistryObject<Item> MEDIC_SYRINGE = ITEMS.register("medic_syringe",
            () -> new MedicSyringeItem(new Item.Properties().stacksTo(1)));

    /** 创造物品栏分类，便于管理员取出据点标记方块。 */
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("ACT0 大战场"))
                    .icon(() -> CONTROL_POINT_ITEM.get().getDefaultInstance())
                                        .displayItems((params, output) -> {
                                                output.accept(BATTLEFIELD_TERMINAL.get());
                                                output.accept(CONTROL_POINT_ITEM.get());
                                                output.accept(AMMO_BOX.get());
                                                output.accept(MEDIC_BOX.get());
                                                output.accept(MEDIC_SYRINGE.get());
                                        })
                    .build());

    private BattlefieldRegistry() {
    }

    /** 在模组构造期把三个 DeferredRegister 挂到模组事件总线。 */
    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        TABS.register(modEventBus);
    }
}
