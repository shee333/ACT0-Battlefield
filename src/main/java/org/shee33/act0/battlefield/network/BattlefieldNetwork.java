package org.shee33.act0.battlefield.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.util.List;
import java.util.Optional;

/**
 * 大战场网络通道：加入界面、BF 风格 HUD（顶部票数条/据点进度/小队信息）与旧简易 HUD 清除兼容包。
 *
 * <p>不用原版计分板侧边栏（其右侧强制渲染数字），所有 HUD 均由客户端自绘。
 */
public final class BattlefieldNetwork {

    /**
     * 通道协议版本。<b>任何改动包表都必须 +1</b>——不只是增删包，调整注册顺序、改动某个包的
     * payload 结构同样算。忘记 bump 的后果不是"报个错"：两端版本字符串相同 → 握手放行 →
     * 玩家正常进服 → 之后每个包都按错位的索引解码。0.1.74 把 SyncStatusPacket 从表中间删掉
     * 却没 bump，旧客户端一按 B 键（ActionPacket，旧索引 2）就被服务端当成 SyncBattleHudPacket
     * （新索引 2，S2C），方向校验抛 IllegalStateException 直接断线；更糟的是 SpotEnemyPacket
     * 错位后落到另一个 C2S 包上，方向校验放行、拿错误字节流静默解码，日志里什么都查不到。
     *
     * <p>{@code NetworkProtocolFingerprintTest} 会锁住包表指纹，漏 bump 时直接测试失败。
     */
    private static final String PROTOCOL = "14";

    @SuppressWarnings("removal")
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Act0Battlefield.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private BattlefieldNetwork() {
    }

    /** 在模组构造期调用，注册数据包。
     *
     * <p>每个包都显式声明 {@link NetworkDirection}：S2C 包（服务端下发、客户端 DistExecutor 处理）
     * 用 {@code PLAY_TO_CLIENT}；C2S 包（客户端 {@code sendToServer} 发出、服务端处理）用
     * {@code PLAY_TO_SERVER}。缺失方向声明会让 Forge 的方向校验永远通过，等同于关闭校验。
     */
    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, SyncHudPacket.class,
                SyncHudPacket::encode, SyncHudPacket::decode, SyncHudPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ActionPacket.class,
                ActionPacket::encode, ActionPacket::decode, ActionPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SyncBattleHudPacket.class,
            SyncBattleHudPacket::encode, SyncBattleHudPacket::decode, SyncBattleHudPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SyncDeployPacket.class,
            SyncDeployPacket::encode, SyncDeployPacket::decode, SyncDeployPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DeployActionPacket.class,
            DeployActionPacket::encode, DeployActionPacket::decode, DeployActionPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, KillFeedPacket.class,
            KillFeedPacket::encode, KillFeedPacket::decode, KillFeedPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SyncBattleTabPacket.class,
            SyncBattleTabPacket::encode, SyncBattleTabPacket::decode, SyncBattleTabPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SyncBattleResultPacket.class,
            SyncBattleResultPacket::encode, SyncBattleResultPacket::decode, SyncBattleResultPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SyncFireLockPacket.class,
            SyncFireLockPacket::encode, SyncFireLockPacket::decode, SyncFireLockPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, HitFeedbackPacket.class,
            HitFeedbackPacket::encode, HitFeedbackPacket::decode, HitFeedbackPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SpotEnemyPacket.class,
            SpotEnemyPacket::encode, SpotEnemyPacket::decode, SpotEnemyPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, DownedActionPacket.class,
            DownedActionPacket::encode, DownedActionPacket::decode, DownedActionPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SyncBreakthroughHudPacket.class,
            SyncBreakthroughHudPacket::encode, SyncBreakthroughHudPacket::decode, SyncBreakthroughHudPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SyncDeployLoadoutPacket.class,
            SyncDeployLoadoutPacket::encode, SyncDeployLoadoutPacket::decode, SyncDeployLoadoutPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DeploySpawnFxPacket.class,
            DeploySpawnFxPacket::encode, DeploySpawnFxPacket::decode, DeploySpawnFxPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DownedFeedbackPacket.class,
            DownedFeedbackPacket::encode, DownedFeedbackPacket::decode, DownedFeedbackPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, CapturePointEventPacket.class,
            CapturePointEventPacket::encode, CapturePointEventPacket::decode, CapturePointEventPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DeployPanPacket.class,
            DeployPanPacket::encode, DeployPanPacket::decode, DeployPanPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, ReviveHeartbeatPacket.class,
            ReviveHeartbeatPacket::encode, ReviveHeartbeatPacket::decode, ReviveHeartbeatPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SyncMatchStartFxPacket.class,
            SyncMatchStartFxPacket::encode, SyncMatchStartFxPacket::decode, SyncMatchStartFxPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DeploySlotOverridePacket.class,
            DeploySlotOverridePacket::encode, DeploySlotOverridePacket::decode, DeploySlotOverridePacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, RequestBattlefieldRoomListPacket.class,
            RequestBattlefieldRoomListPacket::encode, RequestBattlefieldRoomListPacket::decode,
            RequestBattlefieldRoomListPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SyncBattlefieldRoomListPacket.class,
            SyncBattlefieldRoomListPacket::encode, SyncBattlefieldRoomListPacket::decode,
            SyncBattlefieldRoomListPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, OpenBattlefieldBrowserPacket.class,
            OpenBattlefieldBrowserPacket::encode, OpenBattlefieldBrowserPacket::decode,
            OpenBattlefieldBrowserPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DamageDirectionPacket.class,
            DamageDirectionPacket::encode, DamageDirectionPacket::decode,
            DamageDirectionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, MarkPingPacket.class,
            MarkPingPacket::encode, MarkPingPacket::decode,
            MarkPingPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SyncPingPacket.class,
            SyncPingPacket::encode, SyncPingPacket::decode,
            SyncPingPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, SyncDeployablesPacket.class,
            SyncDeployablesPacket::encode, SyncDeployablesPacket::decode,
            SyncDeployablesPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /**
     * 向受击者推送伤害来源方位角（弧度，正北 0 顺时针）。只发方位、不发坐标，
     * 小地图"不显示敌人位置"的架构决策因此不破。
     */
    public static void sendDamageDirection(ServerPlayer player, float bearingRad) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DamageDirectionPacket(bearingRad));
    }

    /** 向玩家推送其可见的已部署补给物列表（驱动地面提示圆）。 */
    public static void sendDeployables(ServerPlayer player, List<DeployableDto> deployables) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncDeployablesPacket(deployables));
    }

    /** 把战术标记同步给某玩家。 */
    public static void sendPing(ServerPlayer player, double x, double z) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncPingPacket(x, z));
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
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncDeployablesPacket(List.of()));
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
                new BreakthroughHudDto(false, 0, 0, 0, 0, List.of(), List.of(), 0, 0, "", 0, 0, "", 0)));
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

    /**
     * 向玩家推送"比赛开局"全屏黑屏转场（淡入→停留→淡出）。倒计时结束、COMBAT 阶段正式开始
     * 那一刻触发，与 {@link #sendDeploySpawnFx} 语义/状态互不干扰，各自独立并存。
     */
    public static void sendMatchStartFx(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncMatchStartFxPacket());
    }

    /**
     * 向玩家推送部署确认"过场相机"的起止位姿快照——只在过场开始时发一次，客户端按渲染帧率自行插值
     * （见 {@code ClientDeployPan}），不逐 tick 重发。
     */
    public static void sendDeployPan(ServerPlayer player,
                                      double startX, double startY, double startZ, float startYaw, float startPitch,
                                      double endX, double endY, double endZ, float endYaw, float endPitch,
                                      int durationTicks) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DeployPanPacket(
                startX, startY, startZ, startYaw, startPitch, endX, endY, endZ, endYaw, endPitch, durationTicks));
    }

    /** 向被击倒玩家推送倒地开始反馈（四角 vignette + 顶部横幅）。 */
    public static void sendDownedFeedback(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DownedFeedbackPacket((byte) 0, ""));
    }

    /** 向被救起玩家推送救援成功反馈。 */
    public static void sendRevivedFeedback(ServerPlayer player, String reviverName) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DownedFeedbackPacket((byte) 1, reviverName));
    }

    /** 倒地超时/主动放弃转入重生时，提前清除客户端"倒地"横幅与 vignette（非被救起）。 */
    public static void sendDownedClearedFeedback(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new DownedFeedbackPacket((byte) 2, ""));
    }

    /** 向玩家推送据点状态边沿事件（HUD 顶部横幅 + 小地图据点图标一次性提亮反馈）。 */
    public static void sendCapturePointEvent(ServerPlayer player, int pointId,
                                              CapturePointEventPacket.Kind kind, int factionCode) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CapturePointEventPacket(pointId, kind, factionCode));
    }

    /** 向玩家推送自定义 TAB 战绩面板。 */
    public static void sendBattleTab(ServerPlayer player, BattleTabDto tab) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBattleTabPacket(true, tab));
    }

    /** 向玩家推送战报界面。 */
    public static void sendBattleResult(ServerPlayer player, BattleResultDto result) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBattleResultPacket(result));
    }

    /** 向玩家推送对局浏览器房间列表快照。 */
    public static void sendRoomList(ServerPlayer player, List<BattlefieldRoomDto> rooms) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncBattlefieldRoomListPacket(rooms));
    }

    /** 告知玩家打开对局浏览器（战地终端物品/命令等服务端触发点）。 */
    public static void sendOpenBrowser(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenBattlefieldBrowserPacket());
    }
}
