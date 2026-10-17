package com.countryross.gps.common;

import java.io.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Dados de mapa de uma regiao: uma grade de celulas (cada celula = "scale" blocos).
 * h[] = altura do topo; k[] = tipo (0 = terreno, 1 = agua, 2 = construcao/estrada).
 */
public class RegionData {
    private static final int MAGIC = 0x47505331;
    public static final int MAX_CELLS_PER_SIDE = 4096;

    public final int scale, minX, minZ, maxX, maxZ, cw, ch;
    public final short[] h;
    public final byte[] k;

    public RegionData(int scale, int minX, int minZ, int maxX, int maxZ) {
        this.scale = scale;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.cw = cells(maxX - minX + 1, scale);
        this.ch = cells(maxZ - minZ + 1, scale);
        this.h = new short[cw * ch];
        this.k = new byte[cw * ch];
    }

    public static int cells(int blocks, int scale) {
        return (blocks + scale - 1) / scale;
    }

    public boolean sameGeometry(int scale, int minX, int minZ, int maxX, int maxZ) {
        return this.scale == scale && this.minX == minX && this.minZ == minZ
                && this.maxX == maxX && this.maxZ == maxZ;
    }

    public byte[] toCompressed() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(bos)))) {
            out.writeInt(MAGIC);
            out.writeInt(scale);
            out.writeInt(minX);
            out.writeInt(minZ);
            out.writeInt(maxX);
            out.writeInt(maxZ);
            for (short s : h) out.writeShort(s);
            out.write(k);
        }
        return bos.toByteArray();
    }

    public static RegionData fromCompressed(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(data))))) {
            if (in.readInt() != MAGIC) throw new IOException("Arquivo de mapa invalido");
            int scale = in.readInt();
            int minX = in.readInt();
            int minZ = in.readInt();
            int maxX = in.readInt();
            int maxZ = in.readInt();
            if (scale < 1 || maxX < minX || maxZ < minZ) throw new IOException("Cabecalho invalido");
            if (cells(maxX - minX + 1, scale) > MAX_CELLS_PER_SIDE
                    || cells(maxZ - minZ + 1, scale) > MAX_CELLS_PER_SIDE) {
                throw new IOException("Regiao grande demais");
            }
            RegionData d = new RegionData(scale, minX, minZ, maxX, maxZ);
            for (int i = 0; i < d.h.length; i++) d.h[i] = in.readShort();
            in.readFully(d.k);
            return d;
        }
    }
}
