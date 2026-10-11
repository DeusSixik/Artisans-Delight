package dev.sixik.artisans_delight.neoforge;

import dev.sixik.artisans_delight.ArtisansDelight;
import dev.sixik.artisans_delight.mod.api.ArtisansDelightConstants;
import net.neoforged.fml.common.Mod;

@Mod(ArtisansDelightConstants.MOD_ID)
public final class ArtisansDelightNeoForge {
    public ArtisansDelightNeoForge() {
        // Run our common setup.
        ArtisansDelight.init();
    }
}
