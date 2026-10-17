package com.countryross.gps.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Estado global salvo no mundo: so guarda o que MUDOU em relacao ao padrao da config
 * (regioes liberadas/bloqueadas e bloqueadores ligados/desligados).
 */
public class GpsSavedData extends SavedData {
    public final Map<String, Boolean> regionState = new HashMap<>();
    public final Map<String, Boolean> jammerState = new HashMap<>();

    public static GpsSavedData load(CompoundTag tag) {
        GpsSavedData d = new GpsSavedData();
        CompoundTag regions = tag.getCompound("regions");
        for (String k : regions.getAllKeys()) d.regionState.put(k, regions.getBoolean(k));
        CompoundTag jammers = tag.getCompound("jammers");
        for (String k : jammers.getAllKeys()) d.jammerState.put(k, jammers.getBoolean(k));
        // formato antigo: lista de regioes liberadas
        ListTag legacy = tag.getList("unlocked", Tag.TAG_STRING);
        for (int i = 0; i < legacy.size(); i++) d.regionState.putIfAbsent(legacy.getString(i), true);
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag regions = new CompoundTag();
        for (Map.Entry<String, Boolean> e : regionState.entrySet()) regions.putBoolean(e.getKey(), e.getValue());
        tag.put("regions", regions);
        CompoundTag jammers = new CompoundTag();
        for (Map.Entry<String, Boolean> e : jammerState.entrySet()) jammers.putBoolean(e.getKey(), e.getValue());
        tag.put("jammers", jammers);
        return tag;
    }

    public static GpsSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(GpsSavedData::load, GpsSavedData::new, "countryross_gps");
    }
}
