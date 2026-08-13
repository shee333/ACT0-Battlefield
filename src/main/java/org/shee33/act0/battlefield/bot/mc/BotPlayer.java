package org.shee33.act0.battlefield.bot.mc;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * AI 士兵的躯体：一个没有客户端的真 {@link ServerPlayer}。
 *
 * <p><b>为什么是 ServerPlayer 而不是自定义 Mob</b>：{@code ArcadeMatch} 的伤害归属、命中反馈、
 * 击杀计分、热区占领计数、配装发放全部以 {@code ServerPlayer} 为准入条件——做成 Mob 就要把这些
 * 逐条重写；做成玩家则<b>一行都不用改</b>。代价只有一个：MC 的导航栈绑定在 {@code Mob} 上，
 * 寻路要自建。而战地风格的 bot 本来就需要理解据点与掩体的自建导航，那份工作无论如何都躲不掉。
 *
 * <p><b>本类存在的唯一理由：补上缺失的物理驱动。</b>
 * 原版把玩家的每 tick 工作拆成了两半：
 * <ul>
 *   <li>{@link ServerPlayer#tick()} —— 由所在世界的实体循环驱动，做游戏模式、容器同步等；</li>
 *   <li>{@link ServerPlayer#doTick()} —— 由 {@code ServerGamePacketListenerImpl.tick()} 驱动，
 *       内部才调用 {@code Player.tick()}，也就是重力、碰撞、台阶攀爬所在的真正物理链路。</li>
 * </ul>
 * 而 bot 的假连接从不会被 {@code ServerConnectionListener} 遍历到，{@code doTick()} 永远不会被调用
 * ——不补这一刀，bot 会像雕像一样定在原地，既不受重力也不响应任何移动意图。
 *
 * <p>补上之后，位移、碰撞、重力、台阶攀爬全部由原版物理接管，无需自研玩家物理引擎。
 * 移动意图只需每 tick 写入 {@code zza}/{@code xxa}/{@code yRot}，见 {@link BotMovementDriver}。
 */
public final class BotPlayer extends ServerPlayer {

    /**
     * 区块跟随间隔（tick）。
     *
     * <p>真玩家靠上行移动包触发区块票据更新；bot 没有上行包，若不主动更新，走出初始加载范围后
     * 就会踏进未加载区块——表现为凭空卡住或掉出世界。每 10 tick（0.5 秒）更新一次足够跟上
     * 步行速度，且开销可忽略。
     */
    private static final int CHUNK_FOLLOW_INTERVAL_TICKS = 10;

    /**
     * 阵亡后延迟多少 tick 再服务端回血。
     *
     * <p>留出这几 tick 是为了让 {@code ArcadeMatch} 先完成它的记账（把阵亡者转入观察者、
     * 评估回合是否结束）。立刻回血会让对局看到一个"没死的死人"，回合判定随之错乱。
     *
     * <p>上限由原版决定：{@code LivingEntity.tickDeath()} 在 {@code deathTime > 19} 时会把实体
     * 移除，所以必须远早于 20 tick 完成回血。取 2 兼顾两侧。
     */
    private static final int DEATH_RECOVERY_DELAY_TICKS = 2;

    private int ticksAtZeroHealth;

    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
    }

    /**
     * 阵亡后由服务端直接恢复，替代 bot 无法执行的客户端复活流程。
     *
     * <p><b>为什么不走原版的"按重生"。</b>{@code PlayerList.respawn} 会<b>返回一个新的
     * ServerPlayer 实例</b>并替换掉旧的——那个新实例是普通 {@code ServerPlayer}，不再是
     * {@link BotPlayer}，于是丢掉 {@link #tick()} 里补调 {@code doTick()} 的那一刀，bot 会永久
     * 冻结；{@code BotManager} 也会因实例不匹配而把它从注册表移除。要保住子类就得 mixin 拦截，
     * 而本方案的全部价值就在于不引入 mixin。项目自身也早已记录过这个风险
     * （见 {@code MatchManager} 中"避免重生换 ServerPlayer 实例导致引用失效"）。
     *
     * <p>因此原地回血：同一个实例、同一份引用，{@code ArcadeMatch} 的观察者与回合编排照常生效，
     * 它只是不必再等一个永远不会到来的客户端复活请求。
     */
    private void recoverIfDown() {
        if (getHealth() > 0.0F) {
            ticksAtZeroHealth = 0;
            return;
        }
        if (++ticksAtZeroHealth <= DEATH_RECOVERY_DELAY_TICKS) {
            return;
        }
        ticksAtZeroHealth = 0;
        setHealth(getMaxHealth());
        // 一并清掉将死状态，否则 tickDeath() 会继续推进 deathTime 直至把实体移除。
        deathTime = 0;
        dead = false;
    }

    /** 本 tick 的头部扫视偏移（度）；见 {@link #applyHeadYawOffset()}。 */
    private float headYawOffset;

    @Override
    public void tick() {
        super.tick();

        // placeNewPlayer 尚未把监听器装上时不能推进物理。
        // 刻意用显式判空而非捕获 NPE：后者会连带吞掉真正的逻辑缺陷。
        if (this.connection == null) {
            return;
        }

        this.doTick();
        recoverIfDown();

        if (this.tickCount % CHUNK_FOLLOW_INTERVAL_TICKS == 0) {
            this.connection.resetPosition();
            this.serverLevel().getChunkSource().move(this);
        }

        applyHeadYawOffset();
    }

    /**
     * 施加本 tick 的头部扫视偏移，随后清零。
     *
     * <p><b>必须放在 {@code super.tick()} 之后。</b>原版的实体 tick 会把玩家的头部朝向重新对齐
     * 到身体，因此在 tick 开始阶段（{@code ServerTickEvent.Phase.START}，即 bot 驱动循环所在处）
     * 写入的头部朝向会被完全抹掉——实测头身夹角恒为 0.0°，扫视毫无作用。
     *
     * <p>用"每 tick 消费一次"的语义而非常驻字段：没有设置偏移的那些 tick 自动回到
     * 头随身体，不会因为某处忘记清零而让 bot 一直歪着头。
     */
    private void applyHeadYawOffset() {
        if (headYawOffset != 0.0F) {
            setYHeadRot(Mth.wrapDegrees(getYRot() + headYawOffset));
            headYawOffset = 0.0F;
        }
    }

    /**
     * 设定本 tick 的头部相对身体偏移（度）。由位移层在未交火时写入以实现行进扫视；
     * 交火时不写，头部即回到与身体（也就是枪口）一致。
     */
    public void setHeadYawOffset(float degrees) {
        this.headYawOffset = degrees;
    }
}
