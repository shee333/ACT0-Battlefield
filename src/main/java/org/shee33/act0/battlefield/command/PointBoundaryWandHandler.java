package org.shee33.act0.battlefield.command;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 据点多边形边界圈画工具（管理员）：执行 {@code /aew1 point boundary edit <id>} 后获得一件标记骨头，
 * <b>左键点击方块逐个添加多边形顶点、右键点击完成闭合</b>。顶点按点击顺序环绕，自动闭合成简单多边形。
 *
 * <p>复用了 Arcade 热区框选道具的成熟模式（NBT 标记 + 服务端 {@code PlayerInteractEvent} + 内存会话），
 * 但把"两次点击定 AABB"扩展为"任意次点击定多边形"。会话状态存于本类的内存 map，随服务器重启清空
 * ——符合"一次性管理员布场操作"的语义。
 *
 * <p>至少 3 个顶点才能构成多边形；不足时右键给出提示且不写盘。多边形几何与判定见
 * {@link org.shee33.act0.battlefield.core.Polygon2D} 与 {@link ControlPointDef#contains}。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PointBoundaryWandHandler {

    /** 道具标记 NBT 键：值恒为 {@code true}，仅用于识别"这是圈画工具"。 */
    private static final String WAND_TAG = "Act0PointBoundaryWand";
    /** 记录正在编辑的据点 id，便于工具被复制/误用时仍能判断。 */
    private static final String POINT_ID_TAG = "Act0PointId";

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private PointBoundaryWandHandler() {
    }

    /** 一次圈画会话：目标据点、维度锁定、已采集的顶点。 */
    private static final class Session {
        private final int pointId;
        private final String dimension;
        private final List<BlockPos> vertices = new ArrayList<>();

        private Session(int pointId, String dimension) {
            this.pointId = pointId;
            this.dimension = dimension;
        }
    }

    /** 制作一件绑定到指定据点的圈画工具。 */
    public static ItemStack createWand(int pointId, String pointName) {
        ItemStack stack = new ItemStack(Items.BONE);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putBoolean(WAND_TAG, true);
        tag.putInt(POINT_ID_TAG, pointId);
        stack.setHoverName(Component.literal("§6据点多边形圈画工具 §7· " + pointName));
        return stack;
    }

    private static boolean isWand(ItemStack stack) {
        return !stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean(WAND_TAG);
    }

    /** 开始一次圈画会话：清空该玩家此前未完成的记录并发放工具。 */
    public static void beginEditing(ServerPlayer player, ControlPointDef def) {
        String dimension = player.serverLevel().dimension().location().toString();
        SESSIONS.put(player.getUUID(), new Session(def.pointId(), dimension));
        player.getInventory().add(createWand(def.pointId(), def.name()));
        player.sendSystemMessage(Component.literal("§a已开始圈画据点 §e" + def.name() + "§a："
                + "§f左键点击方块§a逐个添加顶点，§f右键点击§a完成闭合（至少 3 个顶点）。"));
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        handleVertex(event);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        handleFinish(event);
    }

    private static void handleVertex(PlayerInteractEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !isWand(player.getItemInHand(event.getHand()))) {
            return;
        }
        event.setCanceled(true);
        String dimension = event.getLevel().dimension().location().toString();
        if (!dimension.equals(session.dimension)) {
            player.sendSystemMessage(Component.literal("§c请在开始圈画时的同一维度内点击顶点。"));
            return;
        }
        BlockPos pos = event.getPos();
        // 同一方块连点去重（避免双击/误触产生零长度边）
        if (!session.vertices.isEmpty() && session.vertices.get(session.vertices.size() - 1).equals(pos)) {
            return;
        }
        session.vertices.add(pos.immutable());
        // 顶点处撒一簇粒子作视觉反馈（无需客户端代码，原版粒子随距离广播）。
        player.serverLevel().sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 6, 0.25, 0.25, 0.25, 0.0);
        player.sendSystemMessage(Component.literal("§a顶点 §f#" + session.vertices.size()
                + " §a已记录：§f(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")"
                + " §7右键点击完成。"));
    }

    private static void handleFinish(PlayerInteractEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !isWand(player.getItemInHand(event.getHand()))) {
            return;
        }
        event.setCanceled(true);
        if (session.vertices.size() < 3) {
            player.sendSystemMessage(Component.literal("§c至少需要 3 个顶点才能构成多边形（当前 "
                    + session.vertices.size() + " 个），继续左键添加。"));
            return;
        }
        BattlefieldData data = BattlefieldData.get(player.serverLevel());
        ControlPointDef def = data.pointById(session.pointId);
        if (def == null) {
            player.sendSystemMessage(Component.literal("§c据点已不存在，圈画取消。"));
            SESSIONS.remove(player.getUUID());
            return;
        }
        def.setBoundary(session.vertices);
        data.setDirty();
        SESSIONS.remove(player.getUUID());
        player.sendSystemMessage(Component.literal("§a据点 §e" + def.name() + "§a 的多边形边界已设置："
                + session.vertices.size() + " 个顶点。"));
    }
}
