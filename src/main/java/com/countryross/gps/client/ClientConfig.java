package com.countryross.gps.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Preferencias visuais de cada jogador: config/countryross_gps/client.json */
public final class ClientConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static double defaultZoom = 1.5;
    public static boolean showGrid = true;
    public static boolean scanlines = true;
    public static boolean showCoordinates = true;

    private static class FileModel {
        public String _ajuda = "defaultZoom = zoom ao abrir o GPS (0.1 a 8). showGrid = grade no mapa. "
                + "scanlines = linhas de tela analogica. showCoordinates = mostrar X/Z/ALT no rodape.";
        public double defaultZoom = 1.5;
        public boolean showGrid = true;
        public boolean scanlines = true;
        public boolean showCoordinates = true;
    }

    private ClientConfig() {}

    public static void load() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("countryross_gps");
        Path file = dir.resolve("client.json");
        try {
            Files.createDirectories(dir);
            FileModel m = null;
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    m = GSON.fromJson(r, FileModel.class);
                } catch (Exception e) {
                    LOGGER.error("[GPS] Erro lendo client.json: {} (usando padroes)", e.toString());
                }
            } else {
                try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(new FileModel(), w);
                }
            }
            if (m != null) {
                defaultZoom = Math.max(0.1, Math.min(8.0, m.defaultZoom));
                showGrid = m.showGrid;
                scanlines = m.scanlines;
                showCoordinates = m.showCoordinates;
            }
        } catch (Exception e) {
            LOGGER.error("[GPS] Falha no client.json: {}", e.toString());
        }
    }
}
