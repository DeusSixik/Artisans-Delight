package dev.sixik.artisans_delight.libs.world.minecraft;

import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class WorldObjectManagerApi {

    public static final Logger LOGGER = LoggerFactory.getLogger(WorldObjectManagerApi.class);

    private static final Map<ResourceKey<Level>, WStorage> MANAGERS =
            new Object2ObjectOpenHashMap<>(); // May be use Referenve2ObjectMap ?

    private static class WStorage {
        private WorldObjectManager server;
        private WorldObjectManager client;
    }

    public static WorldObjectManager registerWorld(Level level) {
        final WorldObjectManager manager = new WorldObjectManager();
        final WStorage storage = MANAGERS.computeIfAbsent(
                level.dimension(),
                (key) -> new WStorage()
        );

        if (level.isClientSide()) {
            storage.client = manager;
        } else {
            storage.server = manager;
        }

        LOGGER.info("Registered World Object Manager for dimension {}. Side: {}", level.dimension(), level.isClientSide ? "Client" : "Server");

        return manager;
    }

    public static WorldObjectManager getManager(Level level) {
        return getManager(level.dimension(), level.isClientSide());
    }

    public static WorldObjectManager getManager(ResourceKey<Level> dimensionKey, boolean isClient) {
        final WStorage manager = MANAGERS.get(dimensionKey);
        if (manager == null) {
            throw new IllegalStateException("No WorldObjectManager registered for dimension " + dimensionKey);
        }

        return isClient ? manager.client : manager.server;
    }
}
