package com.countryross.gps.server;

import com.countryross.gps.common.RegionData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Supplier;

/**
 * Configuracao do mod. Fica na pasta config/countryross_gps/ :
 * general.json, regions.json, jammers.json, triggers.json (+ LEIA-ME.txt).
 */
public class GpsConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String OVERWORLD = "minecraft:overworld";

    // ---- geral
    public int scale = 2;
    public int contourInterval = 4;
    public int chunksPerTick = 2;
    public int adminPermissionLevel = 2;
    public int triggerPermissionLevel = 2;
    public Messages messages = new Messages();
    // ---- listas
    public List<Region> regions = new ArrayList<>();
    public List<Jammer> jammers = new ArrayList<>();
    public List<Trigger> triggers = new ArrayList<>();

    // ================================================================== modelos

    public static class Messages {
        public String unlock = "&a[GPS] &fSinal estabelecido: &e%target%";
        public String lock = "&c[GPS] &fSinal perdido: &e%target%";
        public String jammerOn = "&c[GPS] &fBloqueador de sinal ativado: &e%target%";
        public String jammerOff = "&a[GPS] &fBloqueador de sinal desativado: &e%target%";
    }

    public static class Region {
        public String id;
        public String name;
        public int minX, minZ, maxX, maxZ;
        public boolean unlockedByDefault = false;
    }

    /** Bloqueador de Sinal: enquanto ativo, o GPS de quem esta dentro da area perde o sinal. */
    public static class Jammer {
        public String id;
        public String name;
        public boolean activeByDefault = false;
        /** "circle" (x, z, radius) ou "rect" (minX, minZ, maxX, maxZ). */
        public String shape = "circle";
        public int x, z;
        public int radius = 32;
        public int minX, minZ, maxX, maxZ;
        public int minY = -64, maxY = 320;
        public String dimension = OVERWORLD;
    }

    public enum Action { UNLOCK_REGION, LOCK_REGION, JAMMER_ON, JAMMER_OFF, JAMMER_TOGGLE }

    /** Gatilho: o que faz (action/targets) e COMO e disparado (coordenadas e/ou comando). */
    public static class Trigger {
        public String id;
        public String action = "UNLOCK_REGION";
        public List<String> targets = new ArrayList<>();

        public boolean useCoordinates = false;
        public int x, z;
        public int radius = 16;
        public int minY = -64, maxY = 320;
        public String dimension = OVERWORLD;

        public boolean useCommand = true;

        public boolean broadcast = true;
        public String message = "";

        public transient Action act;
    }

    // ================================================================== arquivos

    private static class GeneralFile {
        public String _ajuda = "scale = blocos por pixel do mapa (2 recomendado). contourInterval = altura entre curvas de nivel. "
                + "chunksPerTick = velocidade de geracao do mapa. adminPermissionLevel/triggerPermissionLevel = nivel de OP "
                + "necessario (2 = OP e command block; 0 = qualquer jogador). messages = textos do chat (&a = cor, %target%, %player%).";
        public int scale = 2;
        public int contourInterval = 4;
        public int chunksPerTick = 2;
        public int adminPermissionLevel = 2;
        public int triggerPermissionLevel = 2;
        public Messages messages = new Messages();
    }

    private static class RegionsFile {
        public String _ajuda = "Cada regiao e um retangulo (minX/minZ ate maxX/maxZ). unlockedByDefault = ja comeca com sinal. "
                + "O id so pode ter letras minusculas, numeros e _ .";
        public List<Region> regions = new ArrayList<>();
    }

    private static class JammersFile {
        public String _ajuda = "Bloqueadores de Sinal. shape: circle (x, z, radius) ou rect (minX, minZ, maxX, maxZ). "
                + "minY/maxY limitam a altura (ex.: bunker subterraneo). activeByDefault = ja comeca ligado. "
                + "Ligue/desligue com gatilhos (triggers.json) ou /countrygps jammer on|off|toggle <id>.";
        public List<Jammer> jammers = new ArrayList<>();
    }

    private static class TriggersFile {
        public String _ajuda = "action: UNLOCK_REGION | LOCK_REGION | JAMMER_ON | JAMMER_OFF | JAMMER_TOGGLE. "
                + "targets: ids das regioes ou dos bloqueadores. useCoordinates: dispara quando um jogador ENTRA no circulo "
                + "(x, z, radius, minY, maxY, dimension). useCommand: dispara com /countrygps trigger <id> (chat ou command block). "
                + "broadcast/message: aviso no chat (message vazio = mensagem padrao de general.json). Veja o LEIA-ME.txt.";
        public List<Trigger> triggers = new ArrayList<>();
    }

    // formato antigo (config/countryross_gps.json), usado so para migrar
    private static class LegacyFile {
        int scale = 2, contourInterval = 4, chunksPerTick = 2;
        List<Region> regions;
        List<LegacyPoint> unlockPoints;
    }

    private static class LegacyPoint {
        String id;
        int x, z;
        int radius = 16;
        List<String> unlocks;
    }

    // ================================================================== padroes

    private static RegionsFile defaultRegions() {
        RegionsFile f = new RegionsFile();
        Region inicial = new Region();
        inicial.id = "inicial";
        inicial.name = "Regiao Inicial";
        inicial.minX = -512; inicial.minZ = -512; inicial.maxX = 511; inicial.maxZ = 511;
        inicial.unlockedByDefault = true;
        Region norte = new Region();
        norte.id = "norte";
        norte.name = "Distrito Norte";
        norte.minX = -512; norte.minZ = -1536; norte.maxX = 511; norte.maxZ = -513;
        f.regions.add(inicial);
        f.regions.add(norte);
        return f;
    }

    private static JammersFile defaultJammers() {
        JammersFile f = new JammersFile();
        Jammer j = new Jammer();
        j.id = "bloqueador_exemplo";
        j.name = "Bloqueador de Exemplo";
        j.x = 300; j.z = 300; j.radius = 40;
        f.jammers.add(j);
        return f;
    }

    private static TriggersFile defaultTriggers() {
        TriggersFile f = new TriggersFile();

        Trigger torre = new Trigger();
        torre.id = "torre_norte";
        torre.action = "UNLOCK_REGION";
        torre.targets.add("norte");
        torre.useCoordinates = true;
        torre.x = 0; torre.z = -520; torre.radius = 24;
        torre.useCommand = true;
        f.triggers.add(torre);

        Trigger liga = new Trigger();
        liga.id = "ligar_bloqueador";
        liga.action = "JAMMER_ON";
        liga.targets.add("bloqueador_exemplo");
        liga.useCommand = true;
        f.triggers.add(liga);

        Trigger desliga = new Trigger();
        desliga.id = "desligar_bloqueador";
        desliga.action = "JAMMER_OFF";
        desliga.targets.add("bloqueador_exemplo");
        desliga.useCommand = true;
        f.triggers.add(desliga);
        return f;
    }

    // ================================================================== carregar

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("countryross_gps");
    }

    public static GpsConfig load() {
        Path dir = dir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("[GPS] Nao consegui criar a pasta {}: {}", dir, e.toString());
        }
        migrateLegacy(dir);

        GeneralFile g = readOrCreate(dir.resolve("general.json"), GeneralFile.class, GeneralFile::new);
        RegionsFile rf = readOrCreate(dir.resolve("regions.json"), RegionsFile.class, GpsConfig::defaultRegions);
        JammersFile jf = readOrCreate(dir.resolve("jammers.json"), JammersFile.class, GpsConfig::defaultJammers);
        TriggersFile tf = readOrCreate(dir.resolve("triggers.json"), TriggersFile.class, GpsConfig::defaultTriggers);
        writeReadmeIfMissing(dir);

        GpsConfig c = new GpsConfig();
        c.scale = g.scale;
        c.contourInterval = g.contourInterval;
        c.chunksPerTick = g.chunksPerTick;
        c.adminPermissionLevel = g.adminPermissionLevel;
        c.triggerPermissionLevel = g.triggerPermissionLevel;
        c.messages = g.messages;
        c.regions = rf.regions;
        c.jammers = jf.jammers;
        c.triggers = tf.triggers;
        c.sanitize();
        return c;
    }

    private static <T> T readOrCreate(Path file, Class<T> cls, Supplier<T> def) {
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                T v = GSON.fromJson(r, cls);
                if (v != null) return v;
                LOGGER.error("[GPS] {} esta vazio; usando valores padrao (o arquivo nao foi alterado).",
                        file.getFileName());
            } catch (Exception e) {
                LOGGER.error("[GPS] Erro lendo {}: {} (usando valores padrao; o arquivo nao foi alterado)",
                        file.getFileName(), e.toString());
            }
            return def.get();
        }
        T created = def.get();
        writeJson(file, created);
        return created;
    }

    private static void writeJson(Path file, Object obj) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(obj, w);
        } catch (IOException e) {
            LOGGER.error("[GPS] Nao consegui escrever {}: {}", file, e.toString());
        }
    }

    private static void migrateLegacy(Path dir) {
        Path legacy = FMLPaths.CONFIGDIR.get().resolve("countryross_gps.json");
        if (!Files.exists(legacy) || Files.exists(dir.resolve("regions.json"))) return;
        try (Reader r = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
            LegacyFile old = GSON.fromJson(r, LegacyFile.class);
            if (old == null) return;

            GeneralFile g = new GeneralFile();
            g.scale = old.scale;
            g.contourInterval = old.contourInterval;
            g.chunksPerTick = old.chunksPerTick;
            writeJson(dir.resolve("general.json"), g);

            RegionsFile rf = new RegionsFile();
            if (old.regions != null) rf.regions = old.regions;
            writeJson(dir.resolve("regions.json"), rf);

            TriggersFile tf = new TriggersFile();
            if (old.unlockPoints != null) {
                for (LegacyPoint p : old.unlockPoints) {
                    Trigger t = new Trigger();
                    t.id = p.id == null ? "ponto" : p.id;
                    t.action = "UNLOCK_REGION";
                    if (p.unlocks != null) t.targets = p.unlocks;
                    t.useCoordinates = true;
                    t.x = p.x;
                    t.z = p.z;
                    t.radius = p.radius;
                    t.useCommand = true;
                    tf.triggers.add(t);
                }
            }
            writeJson(dir.resolve("triggers.json"), tf);
        } catch (Exception e) {
            LOGGER.error("[GPS] Falha ao migrar a configuracao antiga: {}", e.toString());
            return;
        }
        try {
            Files.move(legacy, legacy.resolveSibling("countryross_gps.json.antigo"), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[GPS] Configuracao antiga migrada para {} (o original virou countryross_gps.json.antigo).", dir);
        } catch (IOException e) {
            LOGGER.warn("[GPS] Migrado, mas nao consegui renomear o arquivo antigo: {}", e.toString());
        }
    }

    // ================================================================== validacao

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String cleanId(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    private static String nz(String v, String d) {
        return v == null ? d : v;
    }

    private void sanitize() {
        scale = clamp(scale, 1, 8);
        contourInterval = clamp(contourInterval, 1, 64);
        chunksPerTick = clamp(chunksPerTick, 1, 32);
        adminPermissionLevel = clamp(adminPermissionLevel, 0, 4);
        triggerPermissionLevel = clamp(triggerPermissionLevel, 0, 4);

        Messages d = new Messages();
        if (messages == null) messages = d;
        messages.unlock = nz(messages.unlock, d.unlock);
        messages.lock = nz(messages.lock, d.lock);
        messages.jammerOn = nz(messages.jammerOn, d.jammerOn);
        messages.jammerOff = nz(messages.jammerOff, d.jammerOff);

        // ---- regioes
        List<Region> okRegions = new ArrayList<>();
        Set<String> regionIds = new HashSet<>();
        if (regions == null) regions = new ArrayList<>();
        for (Region r : regions) {
            if (r == null || r.id == null || r.id.isBlank()) continue;
            r.id = cleanId(r.id);
            if (!regionIds.add(r.id)) {
                LOGGER.error("[GPS] Regiao com id repetido ignorada: {}", r.id);
                continue;
            }
            if (r.name == null || r.name.isBlank()) r.name = r.id;
            if (r.minX > r.maxX) { int t = r.minX; r.minX = r.maxX; r.maxX = t; }
            if (r.minZ > r.maxZ) { int t = r.minZ; r.minZ = r.maxZ; r.maxZ = t; }
            int cw = RegionData.cells(r.maxX - r.minX + 1, scale);
            int ch = RegionData.cells(r.maxZ - r.minZ + 1, scale);
            if (cw > RegionData.MAX_CELLS_PER_SIDE || ch > RegionData.MAX_CELLS_PER_SIDE) {
                LOGGER.error("[GPS] Regiao '{}' grande demais ({}x{} celulas, max {}). Diminua a area ou aumente 'scale'.",
                        r.id, cw, ch, RegionData.MAX_CELLS_PER_SIDE);
                continue;
            }
            okRegions.add(r);
        }
        regions = okRegions;

        // ---- bloqueadores
        List<Jammer> okJammers = new ArrayList<>();
        Set<String> jammerIds = new HashSet<>();
        if (jammers == null) jammers = new ArrayList<>();
        for (Jammer j : jammers) {
            if (j == null || j.id == null || j.id.isBlank()) continue;
            j.id = cleanId(j.id);
            if (!jammerIds.add(j.id)) {
                LOGGER.error("[GPS] Bloqueador com id repetido ignorado: {}", j.id);
                continue;
            }
            if (j.name == null || j.name.isBlank()) j.name = j.id;
            String shape = j.shape == null ? "circle" : j.shape.toLowerCase(Locale.ROOT);
            j.shape = shape.startsWith("rect") ? "rect" : "circle";
            if (j.radius < 1) j.radius = 32;
            if (j.minX > j.maxX) { int t = j.minX; j.minX = j.maxX; j.maxX = t; }
            if (j.minZ > j.maxZ) { int t = j.minZ; j.minZ = j.maxZ; j.maxZ = t; }
            if (j.minY > j.maxY) { int t = j.minY; j.minY = j.maxY; j.maxY = t; }
            if (j.dimension == null || j.dimension.isBlank()) j.dimension = OVERWORLD;
            okJammers.add(j);
        }
        jammers = okJammers;

        // ---- gatilhos
        List<Trigger> okTriggers = new ArrayList<>();
        Set<String> triggerIds = new HashSet<>();
        if (triggers == null) triggers = new ArrayList<>();
        for (Trigger t : triggers) {
            if (t == null || t.id == null || t.id.isBlank()) continue;
            t.id = cleanId(t.id);
            if (!triggerIds.add(t.id)) {
                LOGGER.error("[GPS] Gatilho com id repetido ignorado: {}", t.id);
                continue;
            }
            try {
                t.act = Action.valueOf(nz(t.action, "").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                LOGGER.error("[GPS] Gatilho '{}' ignorado: action invalida '{}' (use UNLOCK_REGION, LOCK_REGION, "
                        + "JAMMER_ON, JAMMER_OFF ou JAMMER_TOGGLE).", t.id, t.action);
                continue;
            }
            List<String> cleaned = new ArrayList<>();
            if (t.targets != null) for (String s : t.targets) if (s != null && !s.isBlank()) cleaned.add(cleanId(s));
            t.targets = cleaned;
            if (t.radius < 1) t.radius = 16;
            if (t.minY > t.maxY) { int tmp = t.minY; t.minY = t.maxY; t.maxY = tmp; }
            if (t.dimension == null || t.dimension.isBlank()) t.dimension = OVERWORLD;
            if (t.message == null) t.message = "";
            if (!t.useCoordinates && !t.useCommand) {
                LOGGER.warn("[GPS] Gatilho '{}' nunca vai disparar (useCoordinates e useCommand estao false).", t.id);
            }
            boolean forRegions = t.act == Action.UNLOCK_REGION || t.act == Action.LOCK_REGION;
            for (String target : t.targets) {
                if (forRegions ? !regionIds.contains(target) : !jammerIds.contains(target)) {
                    LOGGER.warn("[GPS] Gatilho '{}': {} '{}' nao existe.", t.id, forRegions ? "regiao" : "bloqueador",
                            target);
                }
            }
            okTriggers.add(t);
        }
        triggers = okTriggers;
    }

    // ================================================================== LEIA-ME

    private static void writeReadmeIfMissing(Path dir) {
        Path file = dir.resolve("LEIA-ME.txt");
        if (Files.exists(file)) return;
        String text = """
                CountryRoss GPS - configuracao
                ==============================
                Esta pasta e criada automaticamente. Depois de editar um arquivo, use /countrygps reload no jogo.
                Em servidor, edite os arquivos NO SERVIDOR. Em mundo singleplayer, edite no seu Minecraft.

                ARQUIVOS
                  general.json   resolucao do mapa, curvas de nivel, mensagens do chat, permissoes dos comandos
                  regions.json   as regioes do mapa (retangulos)
                  jammers.json   Bloqueadores de Sinal (areas onde o GPS perde o sinal enquanto estiverem ligados)
                  triggers.json  GATILHOS: o que libera/bloqueia regioes e liga/desliga bloqueadores, e COMO
                  client.json    preferencias visuais de cada jogador (zoom inicial, grade, linhas de varredura)

                GATILHOS (triggers.json)
                  action          UNLOCK_REGION, LOCK_REGION, JAMMER_ON, JAMMER_OFF ou JAMMER_TOGGLE
                  targets         ids das regioes (UNLOCK/LOCK) ou dos bloqueadores (JAMMER_*)
                  useCoordinates  true = dispara quando um jogador ENTRA na area: x, z, radius, minY, maxY, dimension
                  useCommand      true = pode ser disparado pelo comando abaixo
                  broadcast       true = avisa no chat quando algo muda
                  message         texto proprio do aviso (vazio = mensagem padrao do general.json)
                                  cores com & (ex.: &a verde, &c vermelho, &e amarelo, &f branco); %target% e %player%

                  Os dois jeitos podem estar ligados ao mesmo tempo no mesmo gatilho.

                COMANDOS
                  /countrygps trigger <id>             dispara um gatilho (use em COMMAND BLOCK ou no chat)
                  /countrygps unlock <regiao>          libera uma regiao
                  /countrygps lock <regiao>            bloqueia uma regiao
                  /countrygps jammer on|off|toggle <id>   liga/desliga um bloqueador
                  /countrygps list                     mostra regioes e bloqueadores
                  /countrygps rescan <regiao>          gera o mapa da regiao de novo (depois de mudar o terreno)
                  /countrygps reload                   recarrega esta configuracao

                EXEMPLO COM COMMAND BLOCK
                  Coloque um command block com:  /countrygps trigger ligar_bloqueador
                  Ao ativa-lo (botao, alavanca, redstone), o bloqueador "bloqueador_exemplo" liga e o GPS de quem
                  estiver na area perde o sinal. Outro command block com desligar_bloqueador desliga.

                PERMISSOES
                  adminPermissionLevel e triggerPermissionLevel (general.json): 2 = OP e command blocks (padrao).
                  Com triggerPermissionLevel = 0, qualquer jogador pode usar /countrygps trigger no chat.
                  Mudancas de permissao valem depois de reiniciar o servidor.
                """;
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[GPS] Nao consegui escrever o LEIA-ME.txt: {}", e.toString());
        }
    }
}
