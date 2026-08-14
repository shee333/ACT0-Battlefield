package org.shee33.act0.battlefield.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.shee33.act0.battlefield.bot.mc.BotSpawner;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.DeploySlotOptionsDto;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 与 {@code act0_arcade} 的配装系统软集成桥。
 *
 * <p>大战场是独立模组，但产品目标是与街机共用同一套配装/解锁/改装。为了避免把两个工程强行改成
 * Gradle multi-project，本类用反射软依赖：
 * <ul>
 *   <li>若服务器装了 {@code act0_arcade}：读取玩家当前激活的 {@code LoadoutSet.active()}，按
 *   {@code LoadoutRuleset.FULL} 发放完整战地配装（含装置槽），并复用 TaCZ 改装安装。</li>
 *   <li>若没装：安静降级，部署流程仍可跑，只是不接管装备。</li>
 * </ul>
 *
 * <p>所有反射都只发生在服务端部署时；失败只记录一次，避免刷屏。
 */
public final class ArcadeLoadoutBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean warnedMissing = false;
    private static boolean warnedFailure = false;
    private static boolean warnedOverrideFailure = false;

    /** 兵种名（{@code PlayerClassType} 的枚举名）缓存，见 {@link #classNameOf}。 */
    private static final Map<UUID, String> CLASS_CACHE = new ConcurrentHashMap<>();

    /** 支援兵兵种名，对应 Arcade {@code PlayerClassType.SUPPORT}。 */
    public static final String CLASS_SUPPORT = "SUPPORT";

    private ArcadeLoadoutBridge() {
    }

    /**
     * 玩家当前兵种名（如 {@code "SUPPORT"} / {@code "ENGINEER"}）；Arcade 不在场或读取失败返回空串。
     *
     * <p><b>带缓存</b>：救援权限与倒地高亮都要查兵种，前者随救援心跳约 5 Hz 触发、后者每 2 tick
     * 对每名玩家各查一次。整条反射链有六跳，几十人的对局下每 tick 会产生上百次反射调用，而兵种
     * 在一条命之内根本不会变——缓存到 {@link #apply} 发放配装时才失效，那正是兵种唯一可能改变
     * 的时刻（改兵种要重新部署才生效，这也是战地的既有行为）。
     */
    public static String classNameOf(ServerPlayer player) {
        if (player == null) {
            return "";
        }
        return CLASS_CACHE.computeIfAbsent(player.getUUID(), id -> readClassName(player));
    }

    /** 该玩家是否为支援兵。 */
    public static boolean isSupport(ServerPlayer player) {
        return CLASS_SUPPORT.equals(classNameOf(player));
    }

    /** 丢弃某玩家的兵种缓存（玩家退出对局/下线时调用，避免缓存无界增长）。 */
    public static void forgetClass(UUID playerId) {
        if (playerId != null) {
            CLASS_CACHE.remove(playerId);
        }
    }

    @SuppressWarnings("unchecked")
    private static String readClassName(ServerPlayer player) {
        try {
            Object services = Class.forName("org.shee33.act0.arcade.Act0Arcade").getMethod("services").invoke(null);
            Object registry = services.getClass().getMethod("registry").invoke(services);
            Class<?> registryClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutRegistry");
            Object fallback = Class.forName("org.shee33.act0.arcade.loadout.DefaultLoadoutCatalog")
                    .getMethod("defaultLoadout", registryClass).invoke(null, registry);
            Class<?> storeClass = Class.forName("org.shee33.act0.arcade.storage.ArcadeLoadoutStore");
            Object store = storeClass.getMethod("get", MinecraftServer.class).invoke(null, player.server);
            // ArcadeLoadoutStore 同时有 getOrCreate → Loadout 与 getOrCreateSet → LoadoutSet。
            // 这里取的是前者，它内部已经是 getOrCreateSet(...).active()，因此不能再调一次 active()：
            // Loadout 上没有该方法，多调只会抛 NoSuchMethodException 被 catch 吞掉，让兵种名静默
            // 恒为空串——isSupport() 因此永远是 false，支援兵跨小队救援的门控形同不存在。
            Object active = storeClass.getMethod("getOrCreate", UUID.class,
                            Class.forName("org.shee33.act0.arcade.loadout.Loadout"))
                    .invoke(store, player.getUUID(), fallback);
            if (active == null) {
                return "";
            }
            Object classType = active.getClass().getMethod("classType").invoke(active);
            return classType == null ? "" : classType.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /**
     * 对玩家应用 ACT0-Arcade 当前激活配装。
     *
     * @return {@code true} 表示成功调用了 Arcade 配装系统；{@code false} 表示 Arcade 不存在或调用失败。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean apply(ServerPlayer player) {
        try {
            MinecraftServer server = player.server;
            UUID playerId = player.getUUID();
            // 发放配装 = 兵种唯一可能改变的时刻，缓存必须在此失效。
            CLASS_CACHE.remove(playerId);

            Class<?> act0ArcadeClass = Class.forName("org.shee33.act0.arcade.Act0Arcade");
            Object services = act0ArcadeClass.getMethod("services").invoke(null);

            Object registry = services.getClass().getMethod("registry").invoke(services);
            Object applier = services.getClass().getMethod("applier").invoke(services);

            Class<?> registryClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutRegistry");
            Class<?> loadoutClass = Class.forName("org.shee33.act0.arcade.loadout.Loadout");
            Class<?> rulesetClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutRuleset");

            Class<?> defaultCatalogClass = Class.forName("org.shee33.act0.arcade.loadout.DefaultLoadoutCatalog");
            Object fallback = defaultCatalogClass.getMethod("defaultLoadout", registryClass).invoke(null, registry);

            Class<?> storeClass = Class.forName("org.shee33.act0.arcade.storage.ArcadeLoadoutStore");
            Object store = storeClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Object loadout = storeClass.getMethod("getOrCreate", UUID.class, loadoutClass)
                    .invoke(store, playerId, fallback);

            Class<?> unlocksClass = Class.forName("org.shee33.act0.arcade.storage.ArcadePlayerUnlocks");
            Object unlocksStore = unlocksClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Object unlocked = unlocksClass.getMethod("unlocked", UUID.class).invoke(unlocksStore, playerId);

            Object fullRuleset = Enum.valueOf((Class<Enum>) rulesetClass.asSubclass(Enum.class), "FULL");
            Class<?> applierClass = Class.forName("org.shee33.act0.arcade.loadout.mc.LoadoutApplier");
            Method apply = applierClass.getMethod("apply", ServerPlayer.class, loadoutClass, rulesetClass,
                    Set.class, boolean.class);
            apply.invoke(applier, player, loadout, fullRuleset, unlocked, true);
                applySharedApparel(server, player, playerId, services, applier, applierClass, unlocked);
            return true;
        } catch (ClassNotFoundException e) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOGGER.info("[ACT/0/Battlefield] act0_arcade not present; battlefield loadout integration disabled");
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedFailure) {
                warnedFailure = true;
                LOGGER.warn("[ACT/0/Battlefield] failed to apply shared ACT0-Arcade loadout: {}", e.toString());
            }
            return false;
        }
    }

    private static void applySharedApparel(MinecraftServer server, ServerPlayer player, UUID playerId,
                                           Object services, Object applier, Class<?> applierClass, Object unlocked) {
        try {
            Object apparel = services.getClass().getMethod("apparel").invoke(services);
            Class<?> apparelRegistryClass = Class.forName("org.shee33.act0.arcade.loadout.ApparelRegistry");
            Class<?> selectionClass = Class.forName("org.shee33.act0.arcade.loadout.ApparelSelection");
            Class<?> apparelStoreClass = Class.forName("org.shee33.act0.arcade.storage.ArcadeApparelStore");
            Method applyApparel = applierClass.getMethod("applyApparel", ServerPlayer.class,
                    selectionClass, apparelRegistryClass, Set.class);

            // AI 士兵在 Arcade 侧既没有服饰选择也没有解锁记录，读存档只会得到空选择、穿不上任何东西。
            // 改为按身份随机搭配，并把目录全量 key 当作已解锁集——既让 bot 能穿全目录，
            // 又完全不触碰真人的解锁记录。
            if (BotSpawner.isBot(player)) {
                Object botSelection = randomApparelSelection(apparel, selectionClass, playerId);
                if (botSelection == null) {
                    return;
                }
                Object allKeys = apparelRegistryClass.getMethod("all").invoke(apparel);
                Object botUnlocked = allKeys.getClass().getMethod("keySet").invoke(allKeys);
                applyApparel.invoke(applier, player, botSelection, apparel, botUnlocked);
                return;
            }

            Object apparelStore = apparelStoreClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Object selection = apparelStoreClass.getMethod("getOrCreate", UUID.class).invoke(apparelStore, playerId);
            applyApparel.invoke(applier, player, selection, apparel, unlocked);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }


    /**
     * 为 AI 士兵逐槽随机抽一件护甲，返回 Arcade 的 {@code ApparelSelection} 实例。
     *
     * <p><b>随机源按 bot 身份播种</b>：同一个 bot 的造型恒定（每个士兵有自己的装具，而不是每次
     * 复活换一身），且因为结果只由 UUID 决定而<b>幂等</b>——本模组每次部署都会重跑一遍配装发放，
     * 幂等意味着这些重跑不会把护甲换掉。
     *
     * <p><b>逐槽独立抽取而非整套抽取</b>，得到的是"杂牌军"观感；按套抽会让同一批 bot 呈现出几种
     * 一眼可辨的固定造型，反而更假。
     *
     * <p>目录为空或某个槽位无货一律静默跳过——Arcade 的护甲目录默认不写种子模板，全靠管理员录入，
     * 空目录是正常运营状态而非错误。
     *
     * @return 选择实例；目录完全为空时返回 {@code null}
     */
    @Nullable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object randomApparelSelection(Object apparelRegistry, Class<?> selectionClass,
                                                 UUID botId) throws ReflectiveOperationException {
        Class<?> slotClass = Class.forName("org.shee33.act0.arcade.loadout.ApparelSlot");
        Class<?> itemClass = Class.forName("org.shee33.act0.arcade.loadout.ApparelItem");
        Method itemsForSlot = apparelRegistry.getClass().getMethod("itemsForSlot", slotClass);
        Method keyOf = itemClass.getMethod("key");
        Method set = selectionClass.getMethod("set", slotClass, String.class);

        Object selection = selectionClass.getDeclaredConstructor().newInstance();
        java.util.Random rng = new java.util.Random(botId.hashCode());
        boolean any = false;
        for (Object slot : slotClass.getEnumConstants()) {
            List<?> pool = (List<?>) itemsForSlot.invoke(apparelRegistry, slot);
            if (pool == null || pool.isEmpty()) {
                continue;
            }
            Object picked = pool.get(rng.nextInt(pool.size()));
            set.invoke(selection, slot, keyOf.invoke(picked));
            any = true;
        }
        return any ? selection : null;
    }

    /**
     * 反射读取玩家当前配装，用于部署界面显示。若 Arcade 未安装则返回空 DTO。
     *
     * <p>每个已选中的槽位除了当前物品 key，还携带该槽位对玩家当前职业、按已解锁集合过滤后的
     * 全部可选项（{@code LoadoutRegistry.availableItems(LoadoutSlot, PlayerClassType)} +
     * {@code LoadoutItem.isUnlockedBy(Set)}），供底部武器更换面板展示、也供
     * {@code RedeployService} 校验玩家提交的槽位覆盖是否合法。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DeployLoadoutDto readDeployLoadout(ServerPlayer player) {
        try {
            MinecraftServer server = player.server;
            UUID playerId = player.getUUID();
            Object services = Class.forName("org.shee33.act0.arcade.Act0Arcade").getMethod("services").invoke(null);
            Object registry = services.getClass().getMethod("registry").invoke(services);

            Class<?> storeClass = Class.forName("org.shee33.act0.arcade.storage.ArcadeLoadoutStore");
            Object store = storeClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Class<?> loadoutClass = Class.forName("org.shee33.act0.arcade.loadout.Loadout");
            // 同 readClassName：getOrCreate 返回的已经是激活的 Loadout，不是 LoadoutSet。
            // 原先把它交给 LoadoutSet.active() 反射调用，抛 IllegalArgumentException
            //（object is not an instance of declaring class）被 catch 吞掉，导致本方法恒返回空
            // DTO——底部武器更换面板因此永远是 0 个槽位。
            Object active = storeClass.getMethod("getOrCreate", UUID.class, loadoutClass).invoke(store, playerId,
                    Class.forName("org.shee33.act0.arcade.loadout.DefaultLoadoutCatalog")
                            .getMethod("defaultLoadout", Class.forName("org.shee33.act0.arcade.loadout.LoadoutRegistry"))
                            .invoke(null, registry));
            if (active == null) return DeployLoadoutDto.empty();

            Class<?> playerClassTypeClass = Class.forName("org.shee33.act0.arcade.loadout.PlayerClassType");
            Object classType = active.getClass().getMethod("classType").invoke(active);
            String className = classType != null ? classType.toString() : "";

            Class<?> slotEnumClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutSlot");
            Object[] allSlots = slotEnumClass.getEnumConstants();
            // 注意：Loadout 上是 slotItemKeys()，不是 slots()——这里必须对准真实方法名，
            // 反射签名不对只会在运行时静默降级为空 DTO，编译期不会有任何提示。
            Map slotItemKeys = (Map) active.getClass().getMethod("slotItemKeys").invoke(active);

            Class<?> registryClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutRegistry");
            Class<?> itemClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutItem");
            Method availableItemsM = registryClass.getMethod("availableItems", slotEnumClass, playerClassTypeClass);
            Method itemKeyM = itemClass.getMethod("key");
            Method isUnlockedByM = itemClass.getMethod("isUnlockedBy", Set.class);
            Method hotbarIndexM = slotEnumClass.getMethod("hotbarIndex");

            Class<?> unlocksClass = Class.forName("org.shee33.act0.arcade.storage.ArcadePlayerUnlocks");
            Object unlocksStore = unlocksClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Object unlocked = unlocksClass.getMethod("unlocked", UUID.class).invoke(unlocksStore, playerId);

            List<DeploySlotOptionsDto> slots = new ArrayList<>();
            for (Object slot : allSlots) {
                String key = (String) slotItemKeys.get(slot);
                if (key == null || key.isBlank()) {
                    continue;
                }
                List<?> items = (List<?>) availableItemsM.invoke(registry, slot, classType);
                List<String> availableNames = new ArrayList<>();
                for (Object item : items) {
                    if (Boolean.TRUE.equals(isUnlockedByM.invoke(item, unlocked))) {
                        availableNames.add((String) itemKeyM.invoke(item));
                    }
                }
                // P1-1 修复：不再把"当前选中项"强制塞进 availableNames——这个列表同时被
                // DeployLoadoutDto#isValidOverride（校验换装覆盖是否合法）和武器更换面板
                // （展示可选项）共用，强塞一个未解锁的当前项会让它在校验眼中变成合法可选项，
                // 使已被撤销解锁的物品能通过部署面板重新拿到。currentItemName（即此处的 key）
                // 是独立字段，展示当前装备名不需要靠"塞进可选列表"来实现。
                int hotbarIndex = (Integer) hotbarIndexM.invoke(slot);
                slots.add(new DeploySlotOptionsDto(hotbarIndex, slot.toString(), key, availableNames));
            }
            return new DeployLoadoutDto(className, slots);
        } catch (Exception e) {
            return DeployLoadoutDto.empty();
        }
    }

    /**
     * 应用本次重生会话覆盖（见 {@code RedeployService#handleSlotOverride}）的槽位物品。
     *
     * <p>把"槽位序号（= {@code LoadoutSlot.hotbarIndex()}）→ 覆盖后物品 key"通过 Arcade 装备
     * 注册表解析为真实 {@link ItemStack}，复用与 {@code LoadoutApplier#apply} 同款的 SNBT 优先/
     * 编程式工厂兜底转换逻辑 + 初始虚拟弹药 + 玩家已保存改装安装，直接写入玩家背包对应快捷栏位。
     *
     * <p>只应在 {@link #apply(ServerPlayer)} 之后调用：未被覆盖的槽位维持 {@code apply()} 已
     * 发放的 Arcade 默认物品，本方法只覆盖玩家显式选择过的槽位。若 Arcade 不存在或反射失败，
     * 安静降级为不覆盖（玩家仍拿到 {@code apply()} 发放的原始配装，不影响主部署流程）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void applyOverrides(ServerPlayer player, @Nullable Map<Integer, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        try {
            Object services = Class.forName("org.shee33.act0.arcade.Act0Arcade").getMethod("services").invoke(null);
            Object registry = services.getClass().getMethod("registry").invoke(services);
            Object gunMod = services.getClass().getMethod("gunMod").invoke(services);

            Class<?> registryClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutRegistry");
            Class<?> itemClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutItem");
            Class<?> applierClass = Class.forName("org.shee33.act0.arcade.loadout.mc.LoadoutApplier");
            Class<?> gunModClass = Class.forName("org.shee33.act0.arcade.loadout.mc.GunModService");

            Method findM = registryClass.getMethod("find", String.class);
            Method itemSnbtM = itemClass.getMethod("itemSnbt");
            Method createItemM = itemClass.getMethod("createItem");
            Method initialAmmoM = itemClass.getMethod("initialAmmo");
            Method itemKeyM = itemClass.getMethod("key");
            Method fromSnbtM = applierClass.getMethod("fromSnbt", String.class);
            Method setDummyAmmoM = applierClass.getMethod("setDummyAmmo", ItemStack.class, int.class);
            Method installSelectionsM = gunModClass.getMethod("installPlayerSelections",
                    ServerPlayer.class, String.class, ItemStack.class);

            boolean changed = false;
            for (Map.Entry<Integer, String> entry : overrides.entrySet()) {
                String key = entry.getValue();
                if (key == null || key.isBlank()) {
                    continue;
                }
                Optional found = (Optional) findM.invoke(registry, key);
                if (found.isEmpty()) {
                    continue;
                }
                Object item = found.get();
                ItemStack stack = buildOverrideStack(item, itemSnbtM, createItemM, fromSnbtM);
                if (stack.isEmpty()) {
                    continue;
                }
                installSelectionsM.invoke(gunMod, player, itemKeyM.invoke(item), stack);
                int ammo = (Integer) initialAmmoM.invoke(item);
                if (ammo > 0) {
                    setDummyAmmoM.invoke(null, stack, ammo);
                }
                int slotIndex = entry.getKey();
                if (slotIndex >= 0 && slotIndex < player.getInventory().items.size()) {
                    player.getInventory().setItem(slotIndex, stack);
                    changed = true;
                }
            }
            if (changed) {
                player.getInventory().setChanged();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedOverrideFailure) {
                warnedOverrideFailure = true;
                LOGGER.warn("[ACT/0/Battlefield] failed to apply battlefield loadout slot overrides: {}", e.toString());
            }
        }
    }

    /**
     * 镜像 {@code LoadoutApplier} 内部 {@code buildStack} 的转换顺序：优先用 SNBT 反序列化
     * （TaCZ 枪械整枪连配件都在 NBT 内），失败/缺省则回退编程式工厂。只调用 Arcade 的公开方法，
     * 不触碰其私有实现。
     */
    private static ItemStack buildOverrideStack(Object item, Method itemSnbtM, Method createItemM, Method fromSnbtM)
            throws ReflectiveOperationException {
        String snbt = (String) itemSnbtM.invoke(item);
        if (snbt != null && !snbt.isBlank()) {
            ItemStack parsed = (ItemStack) fromSnbtM.invoke(null, snbt);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        }
        Object created = createItemM.invoke(item);
        return created instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }
}
