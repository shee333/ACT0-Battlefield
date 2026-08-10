package org.shee33.act0.battlefield.item;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.shee33.act0.battlefield.deployable.DeployableKind;

/** 工程兵弹药箱：部署后在 3 格内自动为同阵营玩家补给主副武器备弹。 */
public final class AmmoBoxItem extends DeployableBoxItem {

    public AmmoBoxItem(Properties properties) {
        super(properties, DeployableKind.AMMO);
    }

    @Override
    protected ItemStack displayStack() {
        return new ItemStack(Items.CHEST);
    }
}
