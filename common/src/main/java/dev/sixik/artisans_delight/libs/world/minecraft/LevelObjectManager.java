package dev.sixik.artisans_delight.libs.world.minecraft;

import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import net.minecraft.world.level.Level;

public interface LevelObjectManager {

    static WorldObjectManager getManager(Level level) {
        if (!(level instanceof LevelObjectManager manager)) {
            throw new IllegalStateException("Level not implemented 'LevelObjectManager'");
        }

        return manager.getManager();
    }

    WorldObjectManager getManager();
}
