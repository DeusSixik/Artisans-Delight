package dev.sixik.artisans_delight.libs.world;

import java.util.Optional;

/**
 * Single unit of data or behaviour attached to a {@link WorldObject}.
 *
 * <p>Implementations must be thread-safe: {@code onTick} and any public mutators may run
 * on ticker threads while other threads read. Extend {@link AbstractWorldObjectComponent}
 * to inherit a ready-made read/write lock and ownership bookkeeping.
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
