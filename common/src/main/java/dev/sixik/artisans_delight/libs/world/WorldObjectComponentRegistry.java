package dev.sixik.artisans_delight.libs.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global registry of {@link WorldObjectComponentType}s, keyed by stable string id.
 *
 * <p>Fully concurrent: registration uses {@link ConcurrentHashMap#putIfAbsent} and
 * lookups never block. Registration is append-only during runtime; replacing an id
 * requires an explicit {@link #unregister(String)} so accidental codec collisions
 * surface loudly instead of silently swapping serializers.
 *
 * <p>Typical wiring: each component declares
 * {@code public static final WorldObjectComponentType<MyComp> TYPE = ...} and a static
 * initializer registers it once.
 */
public final class WorldObjectComponentRegistry {

    private static final WorldObjectComponentRegistry INSTANCE = new WorldObjectComponentRegistry();

    private final Map<String, WorldObjectComponentType<?>> byId = new ConcurrentHashMap<>();

    private WorldObjectComponentRegistry() {
    }

    /**
     * Process-wide singleton.
     */
    public static WorldObjectComponentRegistry instance() {
        return INSTANCE;
    }

    /**
     * Registers a type; throws if the id is already taken by another instance.
     */
    public <C extends WorldObjectComponent> WorldObjectComponentType<C> register(
            WorldObjectComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        WorldObjectComponentType<?> existing = byId.putIfAbsent(type.id(), type);
        if (existing != null && existing != type) {
            throw new IllegalStateException("Duplicate component id: " + type.id());
        }
        return type;
    }

    /**
     * Removes a registration. Returns the removed type, or empty if absent.
     */
    public Optional<WorldObjectComponentType<?>> unregister(String id) {
        return Optional.ofNullable(byId.remove(id));
    }

    /**
     * Looks up a type by id.
     */
    public Optional<WorldObjectComponentType<?>> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * Looks up a type by id with a checked cast to the expected component class.
     */
    @SuppressWarnings("unchecked")
    public <C extends WorldObjectComponent> Optional<WorldObjectComponentType<C>> get(
            String id, Class<C> componentClass) {
        WorldObjectComponentType<?> type = byId.get(id);
        if (type == null || !componentClass.isAssignableFrom(type.componentClass())) {
            return Optional.empty();
        }
        return Optional.of((WorldObjectComponentType<C>) type);
    }

    /**
     * Snapshot of all registered types.
     */
    public List<WorldObjectComponentType<?>> all() {
        return new ArrayList<>(byId.values());
    }

    /**
     * Number of registered types.
     */
    public int size() {
        return byId.size();
    }
}
