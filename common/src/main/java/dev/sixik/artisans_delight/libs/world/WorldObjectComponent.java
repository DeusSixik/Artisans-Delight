package dev.sixik.artisans_delight.libs.world;

import java.util.Optional;

/**
 * Single unit of data or behaviour attached to a {@link WorldObject}.
 *
 * <p>Two ready-made bases cover both models: {@code SyncedWorldObjectComponent}
 * for shared multithreaded state with NBT persistence and network deltas, and
 * {@link SingleThreadedWorldObjectComponent} for plain single-threaded logic
 * with direct NBT persistence and no locking overhead.
 *
 * <p>Lifecycle contract: {@link #onAttached} runs before the component becomes visible
 * to readers and must not mutate the owner synchronously; use {@link WorldObject#defer}
 * for follow-up work. {@link #onDetached} runs after the component is hidden from new
 * readers, so stale readers may still observe it until they refresh their view.
 */
public interface WorldObjectComponent {

    /**
     * Lower values tick and serialize first. Defaults to {@code 0}.
     */
    default int priority() {
        return 0;
    }

    /**
     * Called once, before the component becomes visible to readers.
     */
    default void onAttached() {
    }

    /**
     * Called once, after the component has been detached and hidden from new readers.
     */
    default void onDetached() {
    }

    /**
     * Owning object, or {@link Optional#empty} while detached.
     */
    Optional<WorldObject> owner();

    /**
     * Returns {@code true} between {@link #onAttached} and {@link #onDetached}.
     */
    boolean isAttached();
}
