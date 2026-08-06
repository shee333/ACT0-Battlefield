package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C deploy screen state for the tactical overhead deployment map.
 *
 * <p>{@code modeName}/{@code mapName}（Wave4 新增）：左上角"动态模式标签"（部署界面动效规格文档
 * §3.8）展示用的只读静态信息——一场对局从始至终固定不变（本 mod 无地图轮换/模式切换概念，见
 * {@code RedeployService} 调用处注释），因此只是随每次状态推送顺带下发的两个展示字段，不影响
 * 既有字段语义。
 */
public record DeployStatusDto(boolean active, boolean canSquad, boolean canPoint, boolean canBase,
                              String selectedKind, String selectedTarget, int readyInTicks,
                              double baseX, double baseY, double baseZ,
                              double squadX, double squadY, double squadZ,
                              List<DeployPointDto> points,
                              List<DeploySquadMateDto> squadMates,
                              List<DeployAllyDto> allies,
                              boolean hasArea,
                              double areaMinX, double areaMinY, double areaMinZ,
                              double areaMaxX, double areaMaxY, double areaMaxZ,
                              boolean areaExplicit,
                              int spectateEntityId,
                              String modeName,
                              String mapName) {

    private static final int MAX_LIST_ENTRIES = 256;

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeBoolean(canSquad);
        buf.writeBoolean(canPoint);
        buf.writeBoolean(canBase);
        buf.writeUtf(selectedKind);
        buf.writeUtf(selectedTarget);
        buf.writeVarInt(readyInTicks);
        buf.writeDouble(baseX);
        buf.writeDouble(baseY);
        buf.writeDouble(baseZ);
        buf.writeDouble(squadX);
        buf.writeDouble(squadY);
        buf.writeDouble(squadZ);
        buf.writeVarInt(points.size());
        for (DeployPointDto point : points) {
            point.encode(buf);
        }
        buf.writeVarInt(squadMates.size());
        for (DeploySquadMateDto mate : squadMates) {
            mate.encode(buf);
        }
        buf.writeVarInt(allies.size());
        for (DeployAllyDto ally : allies) {
            ally.encode(buf);
        }
        buf.writeBoolean(hasArea);
        buf.writeDouble(areaMinX);
        buf.writeDouble(areaMinY);
        buf.writeDouble(areaMinZ);
        buf.writeDouble(areaMaxX);
        buf.writeDouble(areaMaxY);
        buf.writeDouble(areaMaxZ);
        buf.writeBoolean(areaExplicit);
        buf.writeVarInt(spectateEntityId);
        buf.writeUtf(modeName);
        buf.writeUtf(mapName);
    }

    public static DeployStatusDto decode(FriendlyByteBuf buf) {
        boolean active = buf.readBoolean();
        boolean canSquad = buf.readBoolean();
        boolean canPoint = buf.readBoolean();
        boolean canBase = buf.readBoolean();
        String selectedKind = buf.readUtf();
        String selectedTarget = buf.readUtf();
        int ready = buf.readVarInt();
        double baseX = buf.readDouble();
        double baseY = buf.readDouble();
        double baseZ = buf.readDouble();
        double squadX = buf.readDouble();
        double squadY = buf.readDouble();
        double squadZ = buf.readDouble();
        int n = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<DeployPointDto> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            points.add(DeployPointDto.decode(buf));
        }
        int sn = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<DeploySquadMateDto> squadMates = new ArrayList<>(sn);
        for (int i = 0; i < sn; i++) {
            squadMates.add(DeploySquadMateDto.decode(buf));
        }
        int an = Math.max(0, Math.min(buf.readVarInt(), MAX_LIST_ENTRIES));
        List<DeployAllyDto> allies = new ArrayList<>(an);
        for (int i = 0; i < an; i++) {
            allies.add(DeployAllyDto.decode(buf));
        }
        boolean hasArea = buf.readBoolean();
        double aminX = buf.readDouble();
        double aminY = buf.readDouble();
        double aminZ = buf.readDouble();
        double amaxX = buf.readDouble();
        double amaxY = buf.readDouble();
        double amaxZ = buf.readDouble();
        boolean areaExplicit = buf.readBoolean();
        int spectateEntityId = buf.readVarInt();
        String modeName = buf.readUtf();
        String mapName = buf.readUtf();
        return new DeployStatusDto(active, canSquad, canPoint, canBase, selectedKind, selectedTarget, ready,
                baseX, baseY, baseZ, squadX, squadY, squadZ, points, squadMates, allies,
                hasArea, aminX, aminY, aminZ, amaxX, amaxY, amaxZ, areaExplicit, spectateEntityId,
                modeName, mapName);
    }

    public static DeployStatusDto inactive() {
        return new DeployStatusDto(false, false, false, false, "", "", 0,
            0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(),
            false, 0, 0, 0, 0, 0, 0, false, -1, "", "");
    }
}