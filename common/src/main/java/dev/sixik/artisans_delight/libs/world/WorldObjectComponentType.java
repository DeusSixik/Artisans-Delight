package dev.sixik.artisans_delight.libs.world;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stable descriptor of a component kind. Used for duplicate policies, lookups
 * and debug output.
 *
 * <p>The id must be unique per registry (typically {@code "modid:path"}). The factory
 * creates detached instances; {@link WorldObject} attaches them via
 * {@link AbstractWorldObjectComponent#bind}.
 *
 * <p>Serialization lives in {@code libs.world.sync}: components that need persistence
 * or network sync extend {@code SyncedWorldObjectComponent} and declare
 * {@code FieldCodec}-backed fields.
 */
public final class WorldObjectComponentType<C extends WorldObjectComponent> {

    /**
     * What to do when an object already holds a component of this type.
     */
    public enum DuplicatePolicy {
        /**
         * Add as-is; several components of the same type may coexist.
         */
        ALLOW_MULTIPLE,
        /**
         * Reject with {@link DuplicateComponentException}.
         */
        REJECT,
        /**
         * Detach the old component and install the new one atomically.
         */
        REPLACE
    }

    private final String id;
    private final Class<C> componentClass;
    private final DuplicatePolicy duplicatePolicy;
    private final Supplier<C> factory;

    public WorldObjectComponentType(
            String id,
            Class<C> componentClass,
            DuplicatePolicy duplicatePolicy,
            Supplier<C> factory) {
        this.id = Objects.requireNonNull(id, "id");
        this.componentClass = Objects.requireNonNull(componentClass, "componentClass");
        this.duplicatePolicy = Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
        this.factory = Objects.requireNonNull(factory, "factory");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }

    /**
     * Stable unique id, e.g. {@code "artisansdelight:energy"}.
     */
    public String id() {
        return id;
    }

    /**
     * Runtime class of the component.
     */
    public Class<C> componentClass() {
        return componentClass;
    }

    /**
     * Duplicate handling when attaching to the same {@link WorldObject}.
     */
    public DuplicatePolicy duplicatePolicy() {
        return duplicatePolicy;
    }

    /**
     * Creates a detached instance.
     */
    public C createDefault() {
        return Objects.requireNonNull(factory.get(), "factory returned null");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorldObjectComponentType<?> that)) {
            return false;
        }
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "WorldObjectComponentType{id='" + id + "'}";
    }
}
