package dev.sixik.artisans_delight.neoforge;

import dev.sixik.artisans_delight.ArtisansDelight;
import net.neoforged.fml.common.Mod;

@Mod(ArtisansDelight.MOD_ID)
public final class ArtisansDelightNeoForge {
    public ArtisansDelightNeoForge() {
        // Run our common setup.
        ArtisansDelight.init();
    }
}
