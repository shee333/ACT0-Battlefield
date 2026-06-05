package org.shee33.act0.battlefield.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;

import javax.annotation.Nullable;

/**
 * 据点标记方块：管理员放置以界定一个据点。
 *
 * <p>放置时在该维度的 {@link BattlefieldData} 登记一个据点（自动分配编号、默认半径/高度），
 * 破坏时移除对应据点。占领判定区域以本方块为中心，可经命令调整半径/高度/名称。
 */
public final class ControlPointBlock extends Block {

    public ControlPointBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel) {
            ControlPointDef def = BattlefieldData.get(serverLevel).addPoint(pos);
            if (placer instanceof net.minecraft.server.level.ServerPlayer player) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§a已登记据点 §e" + def.name() + " §7(半径 " + def.radius()
                                + " · 高度 " + def.height() + ")，可用指令调整。"));
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            BattlefieldData.get(serverLevel).removePoint(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
