package com.countryross.gps.net;

import com.countryross.gps.client.ClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Servidor -> cliente: regioes, bloqueadores de sinal e parametros do mapa. */
public class SyncPacket {
    public record RegionInfo(String id, String name, int minX, int minZ, int maxX, int maxZ, boolean unlocked) {}

    public record JammerInfo(String id, String name, boolean circle, int x, int z, int radius,
                             int minX, int minZ, int maxX, int maxZ, int minY, int maxY,
                             String dimension, boolean active) {
        public boolean contains(double px, double py, double pz, String dim) {
            if (!active || !dimension.equals(dim)) return false;
            if (py < minY || py > maxY + 1) return false;
            if (circle) {
                double dx = px - x;
                double dz = pz - z;
                return dx * dx + dz * dz <= (double) radius * radius;
            }
            return px >= minX && px <= maxX + 1 && pz >= minZ && pz <= maxZ + 1;
        }
    }

    public final int scale;
    public final int contour;
    public final List<RegionInfo> regions;
    public final List<JammerInfo> jammers;

    public SyncPacket(int scale, int contour, List<RegionInfo> regions, List<JammerInfo> jammers) {
        this.scale = scale;
        this.contour = contour;
        this.regions = regions;
        this.jammers = jammers;
    }

    public static void encode(SyncPacket p, FriendlyByteBuf b) {
        b.writeVarInt(p.scale);
        b.writeVarInt(p.contour);
        b.writeVarInt(p.regions.size());
        for (RegionInfo r : p.regions) {
            b.writeUtf(r.id());
            b.writeUtf(r.name());
            b.writeInt(r.minX());
            b.writeInt(r.minZ());
            b.writeInt(r.maxX());
            b.writeInt(r.maxZ());
            b.writeBoolean(r.unlocked());
        }
        b.writeVarInt(p.jammers.size());
        for (JammerInfo j : p.jammers) {
            b.writeUtf(j.id());
            b.writeUtf(j.name());
            b.writeBoolean(j.circle());
            b.writeInt(j.x());
            b.writeInt(j.z());
            b.writeInt(j.radius());
            b.writeInt(j.minX());
            b.writeInt(j.minZ());
            b.writeInt(j.maxX());
            b.writeInt(j.maxZ());
            b.writeInt(j.minY());
            b.writeInt(j.maxY());
            b.writeUtf(j.dimension());
            b.writeBoolean(j.active());
        }
    }

    public static SyncPacket decode(FriendlyByteBuf b) {
        int scale = b.readVarInt();
        int contour = b.readVarInt();
        int n = b.readVarInt();
        List<RegionInfo> regions = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            regions.add(new RegionInfo(b.readUtf(), b.readUtf(), b.readInt(), b.readInt(), b.readInt(), b.readInt(),
                    b.readBoolean()));
        }
        int m = b.readVarInt();
        List<JammerInfo> jammers = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            jammers.add(new JammerInfo(b.readUtf(), b.readUtf(), b.readBoolean(), b.readInt(), b.readInt(),
                    b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(),
                    b.readUtf(), b.readBoolean()));
        }
        return new SyncPacket(scale, contour, regions, jammers);
    }

    public static void handle(SyncPacket p, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientState.applySync(p));
    }
}
