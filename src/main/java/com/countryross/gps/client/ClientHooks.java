package com.countryross.gps.client;

import net.minecraft.client.Minecraft;

/** Ponto de entrada seguro para codigo que so existe no cliente. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openGps() {
        Minecraft.getInstance().setScreen(new GpsScreen());
    }
}
