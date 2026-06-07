package org.shee33.act0.battlefield.match;

import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * 单玩家发光同步：只向某一个观察者发送实体共享标记包，避免全局发光泄露位置。
 */
public final class GlowSync {
    private static final EntityDataAccessor<Byte> SHARED_FLAGS =
            new EntityDataAccessor<>(0, EntityDataSerializers.BYTE);

    private static final int FLAG_ON_FIRE = 0;
    private static final int FLAG_CROUCHING = 1;
    private static final int FLAG_SPRINTING = 3;
    private static final int FLAG_SWIMMING = 4;
    private static final int FLAG_INVISIBLE = 5;
    private static final int FLAG_GLOWING = 6;
    private static final int FLAG_FALL_FLYING = 7;

    private GlowSync() {
    }

    public static void showGlowTo(ServerPlayer viewer, Entity target) {
        sendSharedFlags(viewer, target, true);
    }

    public static void hideGlowFrom(ServerPlayer viewer, Entity target) {
        sendSharedFlags(viewer, target, false);
    }

    private static void sendSharedFlags(ServerPlayer viewer, Entity target, boolean glow) {
        if (viewer == null || target == null) {
            return;
        }
        byte flags = baseFlags(target);
        flags = setBit(flags, FLAG_GLOWING, glow || target.hasGlowingTag());
        SynchedEntityData.DataValue<Byte> value = SynchedEntityData.DataValue.create(SHARED_FLAGS, flags);
        viewer.connection.send(new ClientboundSetEntityDataPacket(target.getId(), List.of(value)));
    }

    private static byte baseFlags(Entity e) {
        byte flags = 0;
        flags = setBit(flags, FLAG_ON_FIRE, e.isOnFire());
        flags = setBit(flags, FLAG_CROUCHING, e.isCrouching());
        flags = setBit(flags, FLAG_SPRINTING, e.isSprinting());
        flags = setBit(flags, FLAG_SWIMMING, e.isSwimming());
        flags = setBit(flags, FLAG_INVISIBLE, e.isInvisible());
        if (e instanceof net.minecraft.world.entity.LivingEntity living) {
            flags = setBit(flags, FLAG_FALL_FLYING, living.isFallFlying());
        }
        return flags;
    }

    private static byte setBit(byte base, int bit, boolean on) {
        if (on) {
            return (byte) (base | (1 << bit));
        }
        return (byte) (base & ~(1 << bit));
    }
}
