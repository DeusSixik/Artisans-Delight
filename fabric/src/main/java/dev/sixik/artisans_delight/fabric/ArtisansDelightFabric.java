package dev.sixik.artisans_delight.fabric;

import dev.sixik.artisans_delight.ArtisansDelight;
import net.fabricmc.api.ModInitializer;

public final class ArtisansDelightFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        ArtisansDelight.init();
    }
}
