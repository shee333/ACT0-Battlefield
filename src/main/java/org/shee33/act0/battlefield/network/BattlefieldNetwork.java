package org.shee33.act0.battlefield.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.util.List;

/**
 * 大战场网络通道：加入界面、BF 风格 HUD（顶部票数条/据点进度/小队信息）与旧简易 HUD 清除兼容包。
 *
 * <p>不用原版计分板侧边栏（其右侧强制渲染数字），所有 HUD 均由客户端自绘。
 */
public final class BattlefieldNetwork {

    private static final String PROTOCOL = "8";

    @SuppressWarnings("removal")
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Act0Battlefield.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private BattlefieldNetwork() {
    }

    /** 在模组构造期调用，注册数据包。 */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SyncHudPacket.class,
                SyncHudPacket::encode, SyncHudPacket::decode, SyncHudPacket::handle);
        CHANNEL.registerMessage(id++, SyncStatusPacket.class,
                SyncStatusPacket::encode, SyncStatusPacket::decode, SyncStatusPacket::handle);
        CHANNEL.registerMessage(id++, ActionPacket.class,
                ActionPacket::encode, ActionPacket::decode, ActionPacket::handle);
        CHANNEL.registerMessage(id++, SyncBattleHudPacket.class,
            SyncBattleHudPacket::encode, SyncBattleHudPacket::decode, SyncBattleHudPacket::handle);
        CHANNEL.registerMessage(id++, SyncDeployPacket.class,
            SyncDeployPacket::encode, SyncDeployPacket::decode, SyncDeployPacket::handle);
        CHANNEL.registerMessage(id++, DeployActionPacket.class,
            DeployActionPacket::encode, DeployActionPacket::decode, DeployActionPacket::handle);
        CHANNEL.registerMessage(id++, KillFeedPacket.class,
            KillFeedPacket::encode, KillFeedPacket::decode, KillFeedPacket::handle);
        CHANNEL.registerMessage(id++, SyncBattleTabPacket.class,
            SyncBattleTabPacket::encode, SyncBattleTabPacket::decode, SyncBattleTabPacket::handle);
        CHANNEL.registerMessage(id++, SyncBattleResultPacket.class,
            SyncBattleResultPacket::encode, SyncBattleResultPacket::decode, SyncBattleResultPacket::handle);
        CHANNEL.registerMessage(id++, SyncFireLockPacket.class,
            SyncFireLockPacket::encode, SyncFireLockPacket::decode, SyncFireLockPacket::handle);
        CHANNEL.registerMessage(id++, HitFeedbackPacket.class,
            HitFeedbackPacket::encode, HitFeedbackPacket::decode, HitFeedbackPacket::handle);
        CHANNEL.registerMessage(id++, SpotEnemyPacket.class,
            SpotEnemyPacket::encode, SpotEnemyPacket::decode, SpotEnemyPacket::handle);
        CHANNEL.registerMessage(id++, DownedActionPacket.class,
            DownedActionPacket::encode, DownedActionPacket::decode, DownedActionPacket::handle);
        CHANNEL.registerMessage(id++, SyncBreakthroughHudPacket.class,
            SyncBreakthroughHudPacket::encode, SyncBreakthroughHudPacket::decode, SyncBreakthroughHudPacket::handle);
        CHANNEL.registerMessage(id++, SyncDeployLoadoutPacket.class,
            SyncDeployLoadoutPacket::encode, SyncDeployLoadoutPacket::decode, SyncDeployLoadoutPacket::handle);
        CHANNEL.registerMessage(id++, DeploySpawnFxPacket.class,
            DeploySpawnFxPacket::encode, DeploySpawnFxPacket::decode, DeploySpawnFxPacket::handle);
        CHANNEL.registerMessage(id++, DownedFeedbackPacket.class,
            DownedFeedbackPacket::encode, DownedFeedbackPacket::decode, DownedFeedbackPacket::handle);
    }

    /** 向玩家推送 HUD 内容。 */
    public static void sendHud(ServerPlayer player, String title, List<String> lines) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncHudPacket(true, title, lines));
    }

    /** 清除玩家的 HUD（对局结束/离场）。 */
    public static void clearHud(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncHudPacket(false, "", List.of()));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBattleHudPacket(false, null));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBattleTabPacket(false, null));
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncDeployPacket(false, DeployStatusDto.inactive()));
    }

    /** 向玩家推送 BF 风格 HUD 快照（顶部票数/据点进度/小队）。 */
    public static void sendBattleHud(ServerPlayer player, BattleHudDto hud) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBattleHudPacket(true, hud));
    }

    public static void sendBreakthroughHud(ServerPlayer player, BreakthroughHudDto hud) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBreakthroughHudPacket(hud));
    }

    public static void sendDeployLoadout(ServerPlayer player, DeployLoadoutDto loadout) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncDeployLoadoutPacket(loadout));
    }

    public static void clearBreakthroughHud(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBreakthroughHudPacket(
                new BreakthroughHudDto(false, 0, 0, 0, 0, List.of(), List.of(), 0, 0)));
    }

    /** 向玩家推送部署界面状态。 */
    public static void sendDeploy(ServerPlayer player, boolean open, DeployStatusDto status) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncDeployPacket(open, status));
    }

    public static void sendFireLock(ServerPlayer player, boolean locked) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncFireLockPacket(locked));
    }

    /** 向玩家推送击杀提示。 */
    public static void sendKillFeed(ServerPlayer player, String killer, String victim, int killerFaction, int victimFaction, String weapon) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new KillFeedPacket(killer, victim, killerFaction, victimFaction, weapon));
    }

    /** 向攻击者推送准心命中/击杀反馈。 */
    public static void sendHitFeedback(ServerPlayer player, boolean kill) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new HitFeedbackPacket(kill));
    }

    /** 向玩家推送部署传送落地反馈（屏幕淡出 + 底部据点提示）。 */
    public static void sendDeploySpawnFx(ServerPlayer player, String pointLabel) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DeploySpawnFxPacket(pointLabel));
    }

    /** 向被击倒玩家推送倒地开始反馈（四角 vignette + 顶部横幅）。 */
    public static void sendDownedFeedback(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DownedFeedbackPacket((byte) 0, ""));
    }

    /** 向被救起玩家推送救援成功反馈。 */
    public static void sendRevivedFeedback(ServerPlayer player, String reviverName) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DownedFeedbackPacket((byte) 1, reviverName));
    }

    /** 向玩家推送自定义 TAB 战绩面板。 */
    public static void sendBattleTab(ServerPlayer player, BattleTabDto tab) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBattleTabPacket(true, tab));
    }

    /** 向玩家推送战报界面。 */
    public static void sendBattleResult(ServerPlayer player, BattleResultDto result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBattleResultPacket(result));
    }

    /**
     * 向玩家下发状态快照。
     *
     * @param open {@code true}=玩家主动开屏（界面未开则打开）；{@code false}=仅刷新已开界面。
     */
    public static void sendStatus(ServerPlayer player, boolean open, BattlefieldStatusDto status) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncStatusPacket(open, status));
    }
}
