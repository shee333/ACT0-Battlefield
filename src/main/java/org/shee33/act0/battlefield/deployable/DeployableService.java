package org.shee33.act0.battlefield.deployable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.shee33.act0.battlefield.core.SupplyRules;
import org.shee33.act0.battlefield.network.DeployableDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * 一场对局内所有已部署补给物（弹药箱 / 医疗箱）的服务端权威状态。
 *
 * <p>部署物本体刻意复用原版 {@link ItemEntity} 而非自注册实体类型：原版掉落物自带抛物线飞行、
 * 落地静置与掉落物模型渲染，正好覆盖"像按 Q 丢弃那样抛出、中心显示一个箱子掉落物模型"这两条
 * 需求；把 {@code pickUpDelay} 设为永不可拾即得到"无法捡起"。这也与本仓库既有做法一致——入口
 * 全息标识同样是直接用原版 {@code ArmorStand}/{@code Interaction}，没有注册任何自定义实体。
 *
 * <p>补弹走纯 NBT（写 TaCZ 自己读取的 {@code DummyAmmo} 标签），与 ACT0-Arcade 死亡补给箱同款
 * 机制——TaCZ 的 {@code IGun} 并没有公开的备弹写入接口，反射私有字段在 1.0.x/1.1.x 之间并不稳定。
 */
public final class DeployableService {

    /** 部署物实体标签：对局结束时按标签兜底清扫，防止追踪表意外丢失导致箱子永久残留。 */
    public static final String ENTITY_TAG = "Act0BfDeployable";

    private static final String UNIQUE_KEY = "Act0BfDeployId";
    private static final String DUMMY_AMMO_KEY = "DummyAmmo";
    private static final String AMMO_CAP_KEY = "Act0AmmoCap";
    private static final String GUN_ID_KEY = "GunId";

    /** 主武器与副武器的快捷栏位（对应 Arcade {@code LoadoutSlot.PRIMARY/SECONDARY_WEAPON}）。 */
    private static final int[] WEAPON_SLOTS = {0, 1};

    /** 血量比较容差，吸收 {@code setHealth} 的浮点舍入，避免把舍入误差当成中弹。 */
    private static final float HEALTH_EPSILON = 0.05F;

    private final List<Entry> entries = new ArrayList<>();
    private final Map<UUID, Long> medicTriggeredAt = new HashMap<>();
    private final Map<UUID, HealSession> healing = new HashMap<>();

    private static final class Entry {
        final UUID entityId;
        final DeployableKind kind;
        final UUID ownerId;
        final String ownerName;
        final long deployTick;
        final Set<UUID> supplied = new HashSet<>();
        double x;
        double y;
        double z;

        Entry(UUID entityId, DeployableKind kind, UUID ownerId, String ownerName, long deployTick) {
            this.entityId = entityId;
            this.kind = kind;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.deployTick = deployTick;
        }
    }

    private static final class HealSession {
        final long startTick;
        boolean healingBegun;
        float healFrom;
        float lastSetHealth;

        HealSession(long startTick) {
            this.startTick = startTick;
        }
    }

    /**
     * 在玩家视线前方抛出一个部署物。
     *
     * @return 部署出的实体；世界不可用时返回 {@code null}
     */
    public ItemEntity deploy(ServerLevel level, ServerPlayer owner, DeployableKind kind, ItemStack display,
                             long now) {
        Vec3 look = owner.getLookAngle();
        Vec3 spawn = owner.getEyePosition().add(look.x * 0.4D, -0.2D, look.z * 0.4D);
        // 打上唯一标记，否则两个箱子挨得近时会被原版掉落物合并逻辑并成一个实体（同物品同 NBT
        // 且未满堆叠即可合并），其中一份部署会凭空消失。破坏"NBT 相同"这个前提最省事。
        ItemStack shown = display.copy();
        shown.getOrCreateTag().putUUID(UNIQUE_KEY, UUID.randomUUID());
        ItemEntity entity = new ItemEntity(level, spawn.x, spawn.y, spawn.z, shown);
        entity.setDeltaMovement(look.x * 0.45D, look.y * 0.45D + 0.16D, look.z * 0.45D);
        // 32767 是原版用来表示"永不可拾取"的哨兵值（见 ItemEntity#setNeverPickUp）。
        entity.setNeverPickUp();
        entity.setUnlimitedLifetime();
        entity.setInvulnerable(true);
        entity.addTag(ENTITY_TAG);
        level.addFreshEntity(entity);

        entries.add(new Entry(entity.getUUID(), kind, owner.getUUID(),
                owner.getGameProfile().getName(), now));
        return entity;
    }

    /** 该玩家在本场对局已部署的某类补给物数量。 */
    public int countOwned(UUID ownerId, DeployableKind kind) {
        int n = 0;
        for (Entry e : entries) {
            if (e.kind == kind && e.ownerId.equals(ownerId)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 每 tick 推进：到期回收、范围内补给 / 治疗。
     *
     * @param sameFaction 判定两名玩家是否同阵营——补给物只服务己方，否则敌人踩着你的弹药箱补满弹再来打你
     * @param isDowned    判定玩家是否倒地；倒地者需要的是救援而非补给，跳过
     */
    public void tick(ServerLevel level, long now, BiPredicate<UUID, UUID> sameFaction, Predicate<UUID> isDowned) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            Entity entity = level.getEntity(entry.entityId);
            if (entity == null || !entity.isAlive()) {
                it.remove();
                continue;
            }
            if (SupplyRules.expired(now, entry.deployTick, SupplyRules.LIFETIME_TICKS)) {
                entity.discard();
                it.remove();
                continue;
            }
            entry.x = entity.getX();
            entry.y = entity.getY();
            entry.z = entity.getZ();
            serve(level, entry, now, sameFaction, isDowned);
        }
        expireHealSessions(level, now);
    }

    private void serve(ServerLevel level, Entry entry, long now,
                       BiPredicate<UUID, UUID> sameFaction, Predicate<UUID> isDowned) {
        double r = SupplyRules.RADIUS;
        AABB box = new AABB(entry.x - r, entry.y - r, entry.z - r, entry.x + r, entry.y + r, entry.z + r);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class, box)) {
            UUID id = player.getUUID();
            if (!player.isAlive() || player.isSpectator() || isDowned.test(id)
                    || !sameFaction.test(entry.ownerId, id)) {
                continue;
            }
            if (!SupplyRules.inRange(player.getX() - entry.x, player.getY() - entry.y,
                    player.getZ() - entry.z, r)) {
                continue;
            }
            if (entry.kind == DeployableKind.AMMO) {
                serveAmmo(entry, player, id);
            } else {
                serveMedic(entry, player, id, now);
            }
        }
    }

    private void serveAmmo(Entry entry, ServerPlayer player, UUID id) {
        if (entry.supplied.contains(id)) {
            return;
        }
        if (refillWeapons(player) == 0) {
            // 弹药已满等无可补给的情形不消耗补给资格、也不提示，玩家打空后回来仍能补。
            return;
        }
        entry.supplied.add(id);
        player.displayClientMessage(Component.literal("§e由 " + entry.ownerName + " §e提供了弹药补给"), true);
        player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.7F, 1.4F);
    }

    private static int refillWeapons(ServerPlayer player) {
        int refilled = 0;
        for (int slot : WEAPON_SLOTS) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !stack.hasTag() || !stack.getTag().contains(GUN_ID_KEY)) {
                continue;
            }
            CompoundTag tag = stack.getOrCreateTag();
            int current = tag.getInt(DUMMY_AMMO_KEY);
            int next = SupplyRules.refilledAmmo(current, tag.getInt(AMMO_CAP_KEY), SupplyRules.AMMO_GRANT);
            if (next == current) {
                continue;
            }
            tag.putInt(DUMMY_AMMO_KEY, next);
            refilled++;
        }
        if (refilled > 0) {
            player.getInventory().setChanged();
        }
        return refilled;
    }

    private void serveMedic(Entry entry, ServerPlayer player, UUID id, long now) {
        if (healing.containsKey(id)) {
            return;
        }
        Long last = medicTriggeredAt.get(id);
        if (last != null && SupplyRules.onRetriggerCooldown(now, last, SupplyRules.MEDIC_RETRIGGER_TICKS)) {
            return;
        }
        if (player.getHealth() >= player.getMaxHealth() - HEALTH_EPSILON) {
            return;
        }
        medicTriggeredAt.put(id, now);
        healing.put(id, new HealSession(now));
        player.displayClientMessage(Component.literal("§a由 " + entry.ownerName + " §a提供了医疗补给"), true);
    }

    private void expireHealSessions(ServerLevel level, long now) {
        for (Iterator<Map.Entry<UUID, HealSession>> it = healing.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, HealSession> e = it.next();
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(e.getKey());
            HealSession session = e.getValue();
            if (player == null || !player.isAlive() || player.isSpectator()) {
                it.remove();
                continue;
            }
            long elapsed = now - session.startTick;
            SupplyRules.HealPhase phase = SupplyRules.healPhase(elapsed,
                    SupplyRules.MEDIC_DELAY_TICKS, SupplyRules.MEDIC_HEAL_TICKS);
            if (phase == SupplyRules.HealPhase.DELAY) {
                continue;
            }
            if (phase == SupplyRules.HealPhase.DONE) {
                if (session.healingBegun && !damaged(player, session)) {
                    player.setHealth(player.getMaxHealth());
                }
                it.remove();
                continue;
            }
            if (!session.healingBegun) {
                session.healingBegun = true;
                session.healFrom = player.getHealth();
                session.lastSetHealth = session.healFrom;
            } else if (damaged(player, session)) {
                player.displayClientMessage(Component.literal("§c治疗被中断"), true);
                it.remove();
                continue;
            }
            float target = SupplyRules.healthAt(session.healFrom, player.getMaxHealth(),
                    SupplyRules.healProgress(elapsed, SupplyRules.MEDIC_DELAY_TICKS, SupplyRules.MEDIC_HEAL_TICKS));
            if (target > player.getHealth()) {
                player.setHealth(target);
            }
            session.lastSetHealth = player.getHealth();
        }
    }

    private static boolean damaged(ServerPlayer player, HealSession session) {
        return SupplyRules.damagedDuringHeal(player.getHealth(), session.lastSetHealth, HEALTH_EPSILON);
    }

    /**
     * 客户端同步快照：地面提示圆需要位置、类型与剩余时长。
     *
     * @param ownerVisible 按部署者过滤——地面提示圆同时也是"这里有人在补给"的战术信息，敌方不该看到
     */
    public List<DeployableDto> snapshotFor(long now, Predicate<UUID> ownerVisible) {
        List<DeployableDto> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (!ownerVisible.test(e.ownerId)) {
                continue;
            }
            out.add(new DeployableDto(e.kind.ordinal(), e.x, e.y, e.z,
                    SupplyRules.remainingTicks(now, e.deployTick, SupplyRules.LIFETIME_TICKS)));
        }
        return out;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 对局结束时清空所有部署物。
     *
     * <p>除了按追踪表逐个回收，还会按 {@link #ENTITY_TAG} 全世界兜底清扫一遍：追踪表只存在于内存，
     * 服务器在对局中途重启后表就空了，而箱子作为实体已经存进存档——只清追踪表会让它们永久留在
     * 世界里，且再没有任何代码路径能碰到它们。
     */
    public void clearAll(ServerLevel level) {
        for (Entry e : entries) {
            Entity entity = level.getEntity(e.entityId);
            if (entity != null) {
                entity.discard();
            }
        }
        entries.clear();
        medicTriggeredAt.clear();
        healing.clear();
        for (ItemEntity stray : level.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                entity -> entity.getTags().contains(ENTITY_TAG))) {
            stray.discard();
        }
    }
}
