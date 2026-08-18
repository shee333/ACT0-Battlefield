package org.shee33.act0.battlefield.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link DeployLoadoutDto#withOverrides} 是纯函数，不依赖 {@code ServerPlayer}。
 *
 * <p>它同时承担客户端的<b>乐观更新</b>与该更新的合法性把关：面板点击后先本地翻转显示，
 * 非法项必须被原地忽略，否则玩家会看到一个服务端从未接受过的选择。
 */
class DeployLoadoutOverrideTest {

    /** 构造可选项：显示名刻意与 ID 不同，用来确认匹配走的是 ID 而不是显示名。 */
    private static List<DeployOptionDto> opts(String... ids) {
        List<DeployOptionDto> out = new ArrayList<>(ids.length);
        for (String id : ids) {
            out.add(new DeployOptionDto(id, "名称:" + id));
        }
        return out;
    }

    private static DeployLoadoutDto sampleLoadout() {
        DeploySlotOptionsDto primary = new DeploySlotOptionsDto(0, "主武器", "m4a1",
                opts("m4a1", "ak74", "scar_h"));
        DeploySlotOptionsDto secondary = new DeploySlotOptionsDto(1, "副武器", "g17",
                opts("g17", "deagle"));
        return new DeployLoadoutDto(List.of(primary, secondary));
    }

    private static String currentOf(DeployLoadoutDto dto, int slotIndex) {
        return dto.slots().get(slotIndex).currentItemName();
    }

    /** 显示名绝不能被当成选择键：否则界面上同名的两把枪会互相顶替。 */
    @Test
    void displayNameIsNeverAcceptedAsSelectionKey() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertEquals("m4a1", currentOf(loadout.withOverrides(Map.of(0, "名称:ak74")), 0),
                "显示名不是合法的选择键，不应改变当前项");
        assertEquals("ak74", currentOf(loadout.withOverrides(Map.of(0, "ak74")), 0));
        assertEquals("名称:m4a1", loadout.slots().get(0).currentDisplayName());
        assertEquals("名称:ak74", loadout.slots().get(0).displayNameOf("ak74"));
        assertEquals("unknown", loadout.slots().get(0).displayNameOf("unknown"),
                "不在可选项里的 ID 应退回 ID 本身，而不是空串");
    }

    /** 覆盖后可选项列表必须原样带过去，否则换装一次之后面板就再也列不出别的枪。 */
    @Test
    void overrideKeepsOptionList() {
        DeploySlotOptionsDto slot = sampleLoadout().withOverrides(Map.of(0, "ak74")).slots().get(0);
        assertEquals(List.of("m4a1", "ak74", "scar_h"), slot.availableItemNames());
        assertEquals("名称:ak74", slot.currentDisplayName());
    }

    @Test
    void overrideReplacesOnlyTheTargetedSlot() {
        DeployLoadoutDto overridden = sampleLoadout().withOverrides(Map.of(0, "ak74"));
        assertEquals("ak74", currentOf(overridden, 0));
        assertEquals("g17", currentOf(overridden, 1), "未覆盖的槽位应维持原样");
    }

    /** 越权/不存在的提交必须被忽略，而不是照单显示。 */
    @Test
    void illegalOverridesAreIgnored() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertEquals("m4a1", currentOf(loadout.withOverrides(Map.of(0, "未上架的枪")), 0),
                "不在本槽位可选列表内的物品应被忽略");
        assertEquals("m4a1", currentOf(loadout.withOverrides(Map.of(0, "g17")), 0),
                "副武器的物品提交到主武器槽位应被忽略");
        assertSame(loadout, loadout.withOverrides(Map.of(99, "m4a1")),
                "不存在的槽位序号不应产生任何变化");
    }

    @Test
    void blankOverrideIsIgnored() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertSame(loadout, loadout.withOverrides(Map.of(0, "")));
        Map<Integer, String> withNull = new HashMap<>();
        withNull.put(0, null);
        assertSame(loadout, loadout.withOverrides(withNull), "null 覆盖不应抛异常，也不应改变快照");
    }

    @Test
    void returnsSameInstanceWhenNothingChanges() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertSame(loadout, loadout.withOverrides(null));
        assertSame(loadout, loadout.withOverrides(Map.of()));
        assertSame(loadout, loadout.withOverrides(Map.of(0, "m4a1")), "覆盖成当前已选中的同一项不应产生变化");
    }
}
