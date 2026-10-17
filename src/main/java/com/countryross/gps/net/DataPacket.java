package com.countryross.gps.net;

import com.countryross.gps.client.ClientState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Servidor -> cliente: um pedaco dos dados (comprimidos) de uma regiao. */
public class DataPacket {
    public final String id;
    public final int part;
    public final int total;
    public final byte[] bytes;

    public DataPacket(String id, int part, int total, byte[] bytes) {
        this.id = id;
        this.part = part;
        this.total = total;
        this.bytes = bytes;
    }

    public static void encode(DataPacket p, FriendlyByteBuf b) {
        b.writeUtf(p.id);
        b.writeVarInt(p.part);
        b.writeVarInt(p.total);
        b.writeByteArray(p.bytes);
    }

    public static DataPacket decode(FriendlyByteBuf b) {
        return new DataPacket(b.readUtf(), b.readVarInt(), b.readVarInt(), b.readByteArray());
    }

    public static void handle(DataPacket p, Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientState.applyData(p));
    }
}
