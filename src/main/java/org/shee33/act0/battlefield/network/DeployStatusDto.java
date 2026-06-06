package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C deploy screen state for the tactical overhead deployment map.
 */
public record DeployStatusDto(boolean active, boolean canSquad, boolean canPoint, boolean canBase,
                              String selectedKind, String selectedTarget, int readyInTicks,
                              double baseX, double baseZ, double squadX, double squadZ,
                              List<DeployPointDto> points,
                              List<DeploySquadMateDto> squadMates) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeBoolean(canSquad);
        buf.writeBoolean(canPoint);
        buf.writeBoolean(canBase);
        buf.writeUtf(selectedKind);
        buf.writeUtf(selectedTarget);
        buf.writeVarInt(readyInTicks);
        buf.writeDouble(baseX);
        buf.writeDouble(baseZ);
        buf.writeDouble(squadX);
        buf.writeDouble(squadZ);
        buf.writeVarInt(points.size());
        for (DeployPointDto point : points) {
            point.encode(buf);
        }
        buf.writeVarInt(squadMates.size());
        for (DeploySquadMateDto mate : squadMates) {
            mate.encode(buf);
        }
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
        double baseZ = buf.readDouble();
        double squadX = buf.readDouble();
        double squadZ = buf.readDouble();
        int n = buf.readVarInt();
        List<DeployPointDto> points = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            points.add(DeployPointDto.decode(buf));
        }
        int sn = buf.readVarInt();
        List<DeploySquadMateDto> squadMates = new ArrayList<>(sn);
        for (int i = 0; i < sn; i++) {
            squadMates.add(DeploySquadMateDto.decode(buf));
        }
        return new DeployStatusDto(active, canSquad, canPoint, canBase, selectedKind, selectedTarget, ready,
                baseX, baseZ, squadX, squadZ, points, squadMates);
    }

    public static DeployStatusDto inactive() {
        return new DeployStatusDto(false, false, false, false, "", "", 0,
            0, 0, 0, 0, List.of(), List.of());
    }
}
