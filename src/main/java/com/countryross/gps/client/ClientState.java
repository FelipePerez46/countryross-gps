package com.countryross.gps.client;

import com.countryross.gps.common.RegionData;
import com.countryross.gps.net.DataPacket;
import com.countryross.gps.net.SyncPacket;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.util.*;

/** Estado do GPS no cliente: regioes recebidas do servidor e texturas dos mapas. */
public final class ClientState {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record Loaded(ResourceLocation tex, int cw, int ch, int scale) {}

    public static int scale = 2;
    public static int contour = 4;
    public static final List<SyncPacket.RegionInfo> regions = new ArrayList<>();
    public static final List<SyncPacket.JammerInfo> jammers = new ArrayList<>();

    private static final Map<String, Loaded> loaded = new HashMap<>();
    private static final Map<String, byte[][]> partial = new HashMap<>();

    private ClientState() {}

    public static Loaded get(String id) {
        return loaded.get(id);
    }

    public static SyncPacket.RegionInfo find(String id) {
        for (SyncPacket.RegionInfo r : regions) if (r.id().equals(id)) return r;
        return null;
    }

    public static void clear() {
        for (String id : new ArrayList<>(loaded.keySet())) release(id);
        regions.clear();
        jammers.clear();
        partial.clear();
    }

    private static void release(String id) {
        Loaded l = loaded.remove(id);
        if (l != null) Minecraft.getInstance().getTextureManager().release(l.tex());
    }

    /** O jogador esta dentro de um Bloqueador de Sinal ativo? */
    public static boolean isJammed(LocalPlayer p) {
        String dim = p.level().dimension().location().toString();
        for (SyncPacket.JammerInfo j : jammers) {
            if (j.contains(p.getX(), p.getY(), p.getZ(), dim)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ pacotes

    public static void applySync(SyncPacket p) {
        scale = p.scale;
        contour = p.contour;
        regions.clear();
        regions.addAll(p.regions);
        jammers.clear();
        jammers.addAll(p.jammers);
        for (String id : new ArrayList<>(loaded.keySet())) {
            SyncPacket.RegionInfo info = find(id);
            if (info == null || !info.unlocked()) release(id);
        }
    }

    public static void applyData(DataPacket p) {
        byte[][] parts = partial.get(p.id);
        if (p.part == 0 || parts == null || parts.length != p.total) {
            parts = new byte[p.total][];
            partial.put(p.id, parts);
        }
        if (p.part < 0 || p.part >= parts.length) return;
        parts[p.part] = p.bytes;
        for (byte[] b : parts) if (b == null) return;
        partial.remove(p.id);

        try {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] b : parts) all.write(b);
            RegionData d = RegionData.fromCompressed(all.toByteArray());
            build(p.id, d);
        } catch (Exception e) {
            LOGGER.error("[GPS] Falha ao montar o mapa '{}': {}", p.id, e.toString());
        }
    }

    // ------------------------------------------------------------------ textura do mapa

    private static final int[] WATER = {28, 92, 70};
    private static final int[] LAND = {104, 165, 78};
    private static final int[] BUILT = {205, 232, 150};
    private static final int[] CONTOUR = {52, 112, 52};
    private static final int[] CONTOUR_INDEX = {30, 78, 36};

    private static void build(String id, RegionData d) {
        release(id);
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, d.cw, d.ch, false);
        int interval = Math.max(1, contour);
        for (int z = 0; z < d.ch; z++) {
            for (int x = 0; x < d.cw; x++) {
                img.setPixelRGBA(x, z, cellColor(d, x, z, interval));
            }
        }
        DynamicTexture tex = new DynamicTexture(img);
        tex.setFilter(false, false);
        ResourceLocation loc = Minecraft.getInstance().getTextureManager().register("countryross_gps_" + id, tex);
        loaded.put(id, new Loaded(loc, d.cw, d.ch, d.scale));
    }

    private static int abgr(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    private static int abgr(int[] c) {
        return abgr(c[0], c[1], c[2]);
    }

    private static int cellColor(RegionData d, int x, int z, int interval) {
        int cw = d.cw, ch = d.ch;
        int i = z * cw + x;
        byte kind = d.k[i];
        if (kind == 1) return abgr(WATER);

        int h = d.h[i];

        // sombreamento de relevo (luz vindo do noroeste)
        int hw = (x > 0 && d.k[i - 1] != 1) ? d.h[i - 1] : h;
        int hn = (z > 0 && d.k[i - cw] != 1) ? d.h[i - cw] : h;
        int slope = (hw + hn) - 2 * h;
        int shade = Math.max(-24, Math.min(24, -slope * 5));

        if (kind == 2) {
            return abgr(BUILT[0] + shade / 2, BUILT[1] + shade / 2, BUILT[2] + shade / 2);
        }

        // curvas de nivel e linha de costa (so em terreno)
        boolean line = false;
        boolean index = false;
        int band = Math.floorDiv(h, interval);
        int[] nbr = {
                x + 1 < cw ? i + 1 : -1,
                z + 1 < ch ? i + cw : -1,
                x > 0 ? i - 1 : -1,
                z > 0 ? i - cw : -1
        };
        for (int n = 0; n < 4; n++) {
            int j = nbr[n];
            if (j < 0) continue;
            if (d.k[j] == 1) {
                line = true; // costa
                continue;
            }
            if (d.k[j] != 0) continue;
            int b2 = Math.floorDiv((int) d.h[j], interval);
            if (b2 < band) { // marca so o lado mais alto: linha fina de 1 pixel
                line = true;
                if (Math.floorMod(band, 5) == 0) index = true;
            }
        }
        if (line) return abgr(index ? CONTOUR_INDEX : CONTOUR);

        return abgr(LAND[0] + (int) (shade * 0.7), LAND[1] + shade, LAND[2] + (int) (shade * 0.6));
    }
}
