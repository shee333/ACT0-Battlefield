package org.shee33.act0.battlefield.item;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.shee33.act0.battlefield.deployable.DeployableKind;

/** 支援兵医疗箱：部署后在 3 格内延迟起效并为同阵营玩家回满血量。 */
public final class MedicBoxItem extends DeployableBoxItem {

    public MedicBoxItem(Properties properties) {
        super(properties, DeployableKind.MEDIC);
    }

    @Override
    protected ItemStack displayStack() {
        return new ItemStack(Items.ENDER_CHEST);
    }
}
