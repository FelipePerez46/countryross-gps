package com.countryross.gps;

import com.countryross.gps.item.GpsItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CountryRossGps.MODID);

    public static final RegistryObject<Item> GPS =
            ITEMS.register("gps", () -> new GpsItem(new Item.Properties().stacksTo(1)));

    private ModItems() {}
}
