package com.countryross.gps.server;

import com.countryross.gps.common.RegionData;
import com.countryross.gps.net.DataPacket;
import com.countryross.gps.net.Network;
import com.countryross.gps.net.SyncPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Logica do servidor: regioes liberadas, geracao do mapa (escaneando o mundo real aos poucos)
 * e envio dos dados aos clientes.
 */
public final class RegionManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PART_SIZE = 200_000;

    private static GpsConfig config = new GpsConfig();
    private static GpsSavedData data;
    private static Path cacheDir;
    /** Dados comprimidos prontos de cada regiao (id -> bytes). */
    private static final Map<String, byte[]> ready = new HashMap<>();
    private static final ArrayDeque<ScanJob> queue = new ArrayDeque<>();
    private static ScanJob current;
    /** Jogadores que ja estavam dentro da area de cada gatilho por coordenadas (para disparar so na ENTRADA). */
    private static final Map<String, Set<UUID>> inside = new HashMap<>();

    private RegionManager() {}

    // ------------------------------------------------------------------ ciclo de vida

    public static void init(MinecraftServer server) {
        config = GpsConfig.load();
        data = GpsSavedData.get(server);
        cacheDir = server.getWorldPath(LevelResource.ROOT).resolve("countryross_gps");
        ready.clear();
        queue.clear();
        current = null;
        inside.clear();
        for (GpsConfig.Region r : config.regions) {
            if (isUnlocked(r)) ensure(r);
        }
        LOGGER.info("[GPS] {} regiao(oes) configurada(s).", config.regions.size());
    }

    public static void shutdown() {
        ready.clear();
        queue.clear();
        current = null;
        data = null;
    }

    public static void reload(MinecraftServer server) {
        init(server);
        broadcastEverything();
    }

    // ------------------------------------------------------------------ consultas

    public static int adminLevel() {
        return config.adminPermissionLevel;
    }

    public static int triggerLevel() {
        return config.triggerPermissionLevel;
    }

    public static boolean isUnlocked(GpsConfig.Region r) {
        Boolean o = data == null ? null : data.regionState.get(r.id);
        return o != null ? o : r.unlockedByDefault;
    }

    public static boolean isJammerActive(GpsConfig.Jammer j) {
        Boolean o = data == null ? null : data.jammerState.get(j.id);
        return o != null ? o : j.activeByDefault;
    }

    public static GpsConfig.Region find(String id) {
        for (GpsConfig.Region r : config.regions) if (r.id.equals(id)) return r;
        return null;
    }

    public static GpsConfig.Jammer findJammer(String id) {
        for (GpsConfig.Jammer j : config.jammers) if (j.id.equals(id)) return j;
        return null;
    }

    public static GpsConfig.Trigger findTrigger(String id) {
        for (GpsConfig.Trigger t : config.triggers) if (t.id.equals(id)) return t;
        return null;
    }

    public static List<String> regionIds() {
        List<String> ids = new ArrayList<>();
        for (GpsConfig.Region r : config.regions) ids.add(r.id);
        return ids;
    }

    public static List<String> jammerIds() {
        List<String> ids = new ArrayList<>();
        for (GpsConfig.Jammer j : config.jammers) ids.add(j.id);
        return ids;
    }

    /** Gatilhos que aceitam comando. */
    public static List<String> triggerIds() {
        List<String> ids = new ArrayList<>();
        for (GpsConfig.Trigger t : config.triggers) if (t.useCommand) ids.add(t.id);
        return ids;
    }

    public static String describe(GpsConfig.Region r) {
        String estado = isUnlocked(r) ? "LIBERADA" : "BLOQUEADA";
        String mapa;
        if (ready.containsKey(r.id)) mapa = "mapa pronto";
        else if (current != null && current.r.id.equals(r.id)) mapa = "gerando mapa " + current.percent() + "%";
        else if (isUnlocked(r)) mapa = "mapa na fila";
        else mapa = "sem mapa";
        return "Regiao " + r.id + " (" + r.name + "): " + estado + ", " + mapa;
    }

    public static String describe(GpsConfig.Jammer j) {
        return "Bloqueador " + j.id + " (" + j.name + "): " + (isJammerActive(j) ? "ATIVO" : "desligado");
    }

    // ------------------------------------------------------------------ regioes: liberar / bloquear

    private static void setRegionState(GpsConfig.Region r, boolean unlocked) {
        if (unlocked == r.unlockedByDefault) data.regionState.remove(r.id);
        else data.regionState.put(r.id, unlocked);
        data.setDirty();
    }

    public static boolean unlock(MinecraftServer server, String id) {
        GpsConfig.Region r = find(id);
        if (r == null || isUnlocked(r)) return false;
        setRegionState(r, true);
        ensure(r);
        Network.sendToAll(buildSync());
        if (ready.containsKey(id)) sendData(id, Network::sendToAll);
        return true;
    }

    public static boolean lock(String id) {
        GpsConfig.Region r = find(id);
        if (r == null || !isUnlocked(r)) return false;
        setRegionState(r, false);
        Network.sendToAll(buildSync());
        return true;
    }

    public static boolean rescan(String id) {
        GpsConfig.Region r = find(id);
        if (r == null) return false;
        try {
            Files.deleteIfExists(cacheDir.resolve(id + ".bin"));
        } catch (IOException e) {
            LOGGER.warn("[GPS] Nao consegui apagar o cache de {}: {}", id, e.toString());
        }
        ready.remove(id);
        queue.removeIf(j -> j.r.id.equals(id));
        if (current != null && current.r.id.equals(id)) current = null;
        if (isUnlocked(r)) ensure(r);
        return true;
    }

    // ------------------------------------------------------------------ bloqueadores de sinal

    /** @return true se o estado mudou. */
    public static boolean setJammer(String id, boolean active) {
        GpsConfig.Jammer j = findJammer(id);
        if (j == null || isJammerActive(j) == active) return false;
        if (active == j.activeByDefault) data.jammerState.remove(id);
        else data.jammerState.put(id, active);
        data.setDirty();
        Network.sendToAll(buildSync());
        return true;
    }

    /** @return o novo estado, ou null se o bloqueador nao existe. */
    public static Boolean toggleJammer(String id) {
        GpsConfig.Jammer j = findJammer(id);
        if (j == null) return null;
        boolean next = !isJammerActive(j);
        setJammer(id, next);
        return next;
    }

    // ------------------------------------------------------------------ gatilhos

    /** @return -2 = gatilho nao existe, -1 = nao aceita comando, 0+ = quantas alteracoes fez. */
    public static int runCommandTrigger(MinecraftServer server, String id, String who) {
        GpsConfig.Trigger t = findTrigger(id);
        if (t == null) return -2;
        if (!t.useCommand) return -1;
        return fire(server, t, who);
    }

    /** Chamado a cada 1 segundo: dispara gatilhos por coordenadas quando alguem ENTRA na area. */
    public static void checkTriggers(MinecraftServer server) {
        if (data == null) return;
        for (GpsConfig.Trigger t : config.triggers) {
            if (!t.useCoordinates) continue;
            Set<UUID> before = inside.getOrDefault(t.id, Set.of());
            Set<UUID> now = new HashSet<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.isSpectator()) continue;
                if (!p.level().dimension().location().toString().equals(t.dimension)) continue;
                if (p.getY() < t.minY || p.getY() > t.maxY + 1) continue;
                double dx = p.getX() - t.x;
                double dz = p.getZ() - t.z;
                if (dx * dx + dz * dz > (double) t.radius * t.radius) continue;
                now.add(p.getUUID());
                if (!before.contains(p.getUUID())) fire(server, t, p.getName().getString());
            }
            inside.put(t.id, now);
        }
    }

    private static int fire(MinecraftServer server, GpsConfig.Trigger t, String who) {
        int changed = 0;
        for (String target : t.targets) {
            switch (t.act) {
                case UNLOCK_REGION -> {
                    GpsConfig.Region r = find(target);
                    if (r != null && unlock(server, target)) {
                        changed++;
                        announce(server, t, config.messages.unlock, r.name, who);
                    }
                }
                case LOCK_REGION -> {
                    GpsConfig.Region r = find(target);
                    if (r != null && lock(target)) {
                        changed++;
                        announce(server, t, config.messages.lock, r.name, who);
                    }
                }
                case JAMMER_ON -> {
                    GpsConfig.Jammer j = findJammer(target);
                    if (j != null && setJammer(target, true)) {
                        changed++;
                        announce(server, t, config.messages.jammerOn, j.name, who);
                    }
                }
                case JAMMER_OFF -> {
                    GpsConfig.Jammer j = findJammer(target);
                    if (j != null && setJammer(target, false)) {
                        changed++;
                        announce(server, t, config.messages.jammerOff, j.name, who);
                    }
                }
                case JAMMER_TOGGLE -> {
                    Boolean now = toggleJammer(target);
                    GpsConfig.Jammer j = findJammer(target);
                    if (now != null && j != null) {
                        changed++;
                        announce(server, t, now ? config.messages.jammerOn : config.messages.jammerOff, j.name, who);
                    }
                }
            }
        }
        return changed;
    }

    private static void announce(MinecraftServer server, GpsConfig.Trigger t, String defaultTemplate,
                                 String target, String who) {
        if (!t.broadcast) return;
        String template = (t.message != null && !t.message.isBlank()) ? t.message : defaultTemplate;
        String text = template.replace("%target%", target).replace("%player%", who);
        server.getPlayerList().broadcastSystemMessage(colorize(text), false);
    }

    /** Converte &a, &c, &l... em cores/estilos do Minecraft. */
    private static Component colorize(String text) {
        return Component.literal(text.replaceAll("&([0-9a-fA-Fk-oK-OrR])", "\u00a7$1"));
    }

    // ------------------------------------------------------------------ envio aos clientes

    public static SyncPacket buildSync() {
        List<SyncPacket.RegionInfo> list = new ArrayList<>();
        for (GpsConfig.Region r : config.regions) {
            list.add(new SyncPacket.RegionInfo(r.id, r.name, r.minX, r.minZ, r.maxX, r.maxZ, isUnlocked(r)));
        }
        List<SyncPacket.JammerInfo> jl = new ArrayList<>();
        for (GpsConfig.Jammer j : config.jammers) {
            jl.add(new SyncPacket.JammerInfo(j.id, j.name, j.shape.equals("circle"), j.x, j.z, j.radius,
                    j.minX, j.minZ, j.maxX, j.maxZ, j.minY, j.maxY, j.dimension, isJammerActive(j)));
        }
        return new SyncPacket(config.scale, config.contourInterval, list, jl);
    }

    public static void sendAllTo(ServerPlayer player) {
        Network.sendTo(player, buildSync());
        for (GpsConfig.Region r : config.regions) {
            if (isUnlocked(r) && ready.containsKey(r.id)) {
                sendData(r.id, msg -> Network.sendTo(player, msg));
            }
        }
    }

    private static void broadcastEverything() {
        Network.sendToAll(buildSync());
        for (GpsConfig.Region r : config.regions) {
            if (isUnlocked(r) && ready.containsKey(r.id)) sendData(r.id, Network::sendToAll);
        }
    }

    private static void sendData(String id, Consumer<Object> out) {
        byte[] bytes = ready.get(id);
        if (bytes == null) return;
        int total = Math.max(1, (bytes.length + PART_SIZE - 1) / PART_SIZE);
        for (int i = 0; i < total; i++) {
            int from = i * PART_SIZE;
            int to = Math.min(bytes.length, from + PART_SIZE);
            out.accept(new DataPacket(id, i, total, Arrays.copyOfRange(bytes, from, to)));
        }
    }

    // ------------------------------------------------------------------ geracao do mapa

    private static void ensure(GpsConfig.Region r) {
        if (ready.containsKey(r.id)) return;
        byte[] disk = readDisk(r);
        if (disk != null) {
            ready.put(r.id, disk);
            return;
        }
        for (ScanJob j : queue) if (j.r.id.equals(r.id)) return;
        if (current != null && current.r.id.equals(r.id)) return;
        queue.add(new ScanJob(r, config.scale));
        LOGGER.info("[GPS] Regiao '{}' entrou na fila para gerar o mapa.", r.id);
    }

    private static byte[] readDisk(GpsConfig.Region r) {
        Path file = cacheDir.resolve(r.id + ".bin");
        if (!Files.exists(file)) return null;
        try {
            byte[] bytes = Files.readAllBytes(file);
            RegionData d = RegionData.fromCompressed(bytes);
            if (!d.sameGeometry(config.scale, r.minX, r.minZ, r.maxX, r.maxZ)) return null;
            return bytes;
        } catch (Exception e) {
            LOGGER.warn("[GPS] Cache de '{}' invalido, vai ser regenerado: {}", r.id, e.toString());
            return null;
        }
    }

    public static void tick(MinecraftServer server) {
        if (current == null) current = queue.poll();
        if (current == null) return;
        ServerLevel level = server.overworld();
        ScanJob job = current;
        try {
            for (int i = 0; i < config.chunksPerTick && !job.done(); i++) job.step(level);
        } catch (Exception e) {
            LOGGER.error("[GPS] Erro escaneando '{}': {}", job.r.id, e.toString());
            current = null;
            return;
        }
        int pct = job.percent();
        if (pct >= job.lastLoggedPct + 10) {
            job.lastLoggedPct = pct - (pct % 10);
            LOGGER.info("[GPS] Gerando mapa de '{}': {}%", job.r.id, pct);
        }
        if (job.done()) {
            current = null;
            try {
                byte[] bytes = job.out.toCompressed();
                Files.createDirectories(cacheDir);
                Files.write(cacheDir.resolve(job.r.id + ".bin"), bytes);
                ready.put(job.r.id, bytes);
                LOGGER.info("[GPS] Mapa de '{}' pronto ({} KB).", job.r.id, bytes.length / 1024);
                if (isUnlocked(job.r)) sendData(job.r.id, Network::sendToAll);
            } catch (IOException e) {
                LOGGER.error("[GPS] Nao consegui salvar o mapa de '{}': {}", job.r.id, e.toString());
            }
        }
    }

    private static final class ScanJob {
        final GpsConfig.Region r;
        final int scale;
        final RegionData out;
        final int cx0, cx1, cz0, cz1;
        int cx, cz;
        int lastLoggedPct = 0;

        ScanJob(GpsConfig.Region r, int scale) {
            this.r = r;
            this.scale = scale;
            this.out = new RegionData(scale, r.minX, r.minZ, r.maxX, r.maxZ);
            this.cx0 = r.minX >> 4;
            this.cx1 = r.maxX >> 4;
            this.cz0 = r.minZ >> 4;
            this.cz1 = r.maxZ >> 4;
            this.cx = cx0;
            this.cz = cz0;
        }

        boolean done() {
            return cz > cz1;
        }

        int percent() {
            long width = cx1 - cx0 + 1L;
            long total = width * (cz1 - cz0 + 1L);
            long doneChunks = (long) (Math.min(cz, cz1 + 1) - cz0) * width + (cz > cz1 ? 0 : (cx - cx0));
            return (int) Math.min(100, doneChunks * 100 / Math.max(1, total));
        }

        void step(ServerLevel level) {
            scanChunk(level, this, cx, cz);
            cx++;
            if (cx > cx1) {
                cx = cx0;
                cz++;
            }
        }
    }

    private static void scanChunk(ServerLevel level, ScanJob j, int chunkX, int chunkZ) {
        level.getChunk(chunkX, chunkZ); // carrega (ou gera) o chunk
        GpsConfig.Region r = j.r;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int lz = 0; lz < 16; lz++) {
            int wz = baseZ + lz;
            if (wz < r.minZ || wz > r.maxZ) continue;
            int dz = wz - r.minZ;
            if (dz % j.scale != 0) continue;
            int cellZ = dz / j.scale;
            for (int lx = 0; lx < 16; lx++) {
                int wx = baseX + lx;
                if (wx < r.minX || wx > r.maxX) continue;
                int dx = wx - r.minX;
                if (dx % j.scale != 0) continue;
                int cellX = dx / j.scale;

                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
                pos.set(wx, y, wz);
                BlockState state = level.getBlockState(pos);
                byte kind;
                if (state.getFluidState().is(FluidTags.WATER)) kind = 1;
                else if (isNatural(state)) kind = 0;
                else kind = 2;

                int idx = cellZ * j.out.cw + cellX;
                j.out.h[idx] = (short) y;
                j.out.k[idx] = kind;
            }
        }
    }

    /** Blocos "naturais" viram terreno; o resto (tabuas, tijolos, concreto...) vira construcao/estrada. */
    private static boolean isNatural(BlockState s) {
        return s.isAir()
                || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD)
                || s.is(BlockTags.TERRACOTTA) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)
                || s.is(BlockTags.FLOWERS) || s.is(BlockTags.ICE) || s.is(BlockTags.SNOW)
                || s.is(BlockTags.STONE_ORE_REPLACEABLES)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.CLAY) || s.is(Blocks.SNOW_BLOCK)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.CALCITE)
                || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.BEDROCK);
    }
}
