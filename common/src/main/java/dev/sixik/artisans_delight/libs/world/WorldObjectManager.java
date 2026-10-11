package dev.sixik.artisans_delight.libs.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Owns every {@link WorldObject} of a world (or dimension): creates, ticks and
 * discards them. All operations are safe to call from any thread; iteration during
 * {@link #tickAll} is weakly consistent, so objects added mid-tick tick on the next
 * call and per-object failures never stop the remaining objects.
 */
public final class WorldObjectManager implements AutoCloseable {

    /**
     * Observer of object membership. Callbacks must be short and non-blocking.
     */
    public interface Listener {
        /**
         * A live object entered the manager.
         */
        default void onObjectAdded(WorldObjectManager manager, WorldObject object) {
        }

        /**
         * An object left the manager (already detached).
         */
        default void onObjectRemoved(WorldObjectManager manager, WorldObject object) {
        }
    }

    private final ConcurrentHashMap<UUID, WorldObject> objects = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCounter = new AtomicLong();

    private boolean closed = false;

    /**
     * Creates, activates and registers a new object.
     */
    public WorldObject create() {
        WorldObject object = new WorldObject();
        add(object);
        return object;
    }

    /**
     * Registers {@code object}. Returns {@code false} if an object
     * with the same id is already present (the argument is left untouched).
     */
    public boolean add(WorldObject object) {
        Objects.requireNonNull(object, "object");
        if (objects.putIfAbsent(object.id(), object) != null) {
            return false;
        }
        fireAdded(object);
        return true;
    }

    /**
     * Looks up an object by id.
     */
    public Optional<WorldObject> get(UUID id) {
        return Optional.ofNullable(objects.get(id));
    }

    /**
     * Detaches and unregisters the object with {@code id}. Returns {@code true} if
     * one was present.
     */
    public boolean remove(UUID id) {
        WorldObject removed = objects.remove(id);
        if (removed == null) {
            return false;
        }
        try {
            removed.detach();
        } finally {
            fireRemoved(removed);
        }
        return true;
    }

    /**
     * Live view of all registered objects. No copy, no allocation: backed directly
     * by the registry. The view is weakly consistent — safe to iterate while other
     * threads add or remove objects — but never mutate through it. Need a stable
     * point-in-time copy (e.g. to sort or retain)? Use {@link #snapshot()}.
     */
    public Collection<WorldObject> all() {
        return Collections.unmodifiableCollection(objects.values());
    }

    /**
     * Point-in-time copy of all registered objects. Allocates; prefer {@link #all()}
     * or {@link #forEach} for plain iteration.
     */
    public List<WorldObject> snapshot() {
        return new ArrayList<>(objects.values());
    }

    /**
     * Applies {@code action} to every registered object. Zero-allocation iteration
     * over the live registry; a per-object failure does not stop the rest —
     * use {@link #tickAll} when failures must be aggregated and rethrown.
     */
    public void forEach(Consumer<WorldObject> action) {
        Objects.requireNonNull(action, "action");
        objects.values().forEach(action);
    }

    /**
     * Number of registered objects.
     */
    public int size() {
        return objects.size();
    }

    /**
     * Last tick number issued by {@link #tickAll}.
     */
    public long lastTick() {
        return tickCounter.get();
    }

    /**
     * Ticks every object once with the next monotonic tick number. Per-object
     * failures are aggregated: all objects still tick, then a {@link
     * WorldObjectException} with each {@link WorldObject.TickException} suppressed
     * is thrown if any failed.
     */
    public void tickAll() {
        long tick = tickCounter.incrementAndGet();
        List<Throwable> failures = null;
        for (WorldObject object : objects.values()) {
            try {
                object.tick(tick);
            } catch (Throwable failure) {
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                failures.add(failure);
            }
        }
        if (failures != null) {
            WorldObjectException aggregated = new WorldObjectException(
                    "tickAll #" + tick + " failed for " + failures.size() + " object(s)");
            for (Throwable failure : failures) {
                aggregated.addSuppressed(failure);
            }
            throw aggregated;
        }
    }

    /**
     * Debug view: object id to attached type ids (see {@link WorldObject#describe}).
     */
    public Map<String, List<String>> describeAll() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (WorldObject object : objects.values()) {
            result.put(object.id().toString(), object.describe());
        }
        return Map.copyOf(result);
    }

    /**
     * Subscribes a membership listener.
     */
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unsubscribes a membership listener.
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void fireAdded(WorldObject object) {
        for (Listener listener : listeners) {
            listener.onObjectAdded(this, object);
        }
    }

    private void fireRemoved(WorldObject object) {
        for (Listener listener : listeners) {
            listener.onObjectRemoved(this, object);
        }
    }

    /**
     * Detaches every object and clears the manager. Idempotent.
     */
    @Override
    public void close() {
        List<WorldObject> remaining = new ArrayList<>(objects.values());
        objects.clear();
        for (WorldObject object : remaining) {
            try {
                object.detach();
            } catch (Throwable ignored) {
                // Close must release everything even if one object misbehaves.
            }
            fireRemoved(object);
        }
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}
