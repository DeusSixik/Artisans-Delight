package dev.sixik.artisans_delight.libs.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Universal thread-safe container of {@link WorldObjectComponent}s.
 *
 * <p>Threading model (read-heavy workload): reads ({@link #entries}, {@link #get},
 * {@link #tick}) never take locks. Every mutation publishes a new immutable snapshot
 * through a {@code volatile} reference, so readers iterate a consistent view without
 * blocking. Mutations serialize on an internal lock; they are rare by design.
 *
 * <p>Ordering: components tick and serialize in ascending {@link
 * WorldObjectComponent#priority} order, stable by attach sequence. There is no manual
 * index juggling; if a component's priority changes at runtime, call {@link #resort}.
 *
 * <p>Lifecycle of an attached component: bound to this owner, published to readers,
 * then {@link WorldObjectComponent#onAttached} runs. Detach hides it from new readers
 * first, then {@link WorldObjectComponent#onDetached} runs. Callbacks execute outside
 * the owner's write lock on the mutating thread, so synchronous structural changes
 * are safe &mdash; use {@link #defer} when work must run on the ticking thread or
 * after the current mutation settles.
 *
 * <p>Only {@link AbstractWorldObjectComponent} subclasses can be attached; direct
 * {@link WorldObjectComponent} implementations are rejected with {@link
 * IllegalArgumentException}. This guarantees uniform ownership bookkeeping.
 */
public final class WorldObject {

    /**
     * One attached component plus the type it was attached with ({@code null} = untyped).
     */
    public record ComponentEntry(
            WorldObjectComponent component, WorldObjectComponentType<?> type, long sequence) {
    }

    /**
     * Aggregates per-component tick failures; individual causes are {@code suppressed}.
     */
    public static final class TickException extends WorldObjectException {
        public TickException(String message) {
            super(message);
        }
    }

    private static final Comparator<ComponentEntry> ORDER =
            Comparator.comparingInt((ComponentEntry entry) -> entry.component().priority())
                    .thenComparingLong(ComponentEntry::sequence);

    private final UUID id;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final CopyOnWriteArrayList<WorldObjectListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Runnable> deferred = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequencer = new AtomicLong();

    private volatile View view = View.EMPTY;

    public WorldObject() {
        this(UUID.randomUUID());
    }

    public WorldObject(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Stable identity of this object.
     */
    public UUID id() {
        return id;
    }

    /**
     * Current lifecycle: a live object accepts mutations until {@link #detach}.
     */
    public boolean isDetached() {
        return view.detached;
    }

    /**
     * Detaches every component, clears deferred work and rejects future mutations.
     * Idempotent; returns {@code true} on the first call. Readers holding an older
     * snapshot keep iterating it safely.
     */
    public boolean detach() {
        List<ComponentEntry> removed;
        writeLock.lock();
        try {
            if (view.detached) {
                return false;
            }
            removed = view.entries;
            view = View.EMPTY.detached();
            deferred.clear();
            for (ComponentEntry entry : removed) {
                asAbstract(entry.component()).bind(null);
            }
        } finally {
            writeLock.unlock();
        }
        List<Throwable> failures = new ArrayList<>();
        for (ComponentEntry entry : removed) {
            try {
                entry.component().onDetached();
            } catch (Throwable failure) {
                failures.add(failure);
            }
            fireRemoved(entry.component(), failures);
        }
        throwIfFailed(failures, "WorldObject.detach callbacks failed for " + id);
        return true;
    }

    // Attach.

    /**
     * Attaches an untyped component (always allowed, duplicates permitted).
     */
    public <C extends WorldObjectComponent> C attach(C component) {
        return attach(component, null);
    }

    /**
     * Attaches {@code component} under {@code type}, applying its duplicate policy.
     * The component becomes visible to readers before {@code onAttached} runs, so the
     * callback can query siblings (including itself). A throwing {@code onAttached}
     * still leaves the component attached; listeners are notified regardless.
     */
    public <C extends WorldObjectComponent> C attach(
            C component, WorldObjectComponentType<C> type) {
        Objects.requireNonNull(component, "component");
        AbstractWorldObjectComponent handle = asAbstract(component);
        List<ComponentEntry> replaced;
        writeLock.lock();
        try {
            requireLive();
            if (handle.isAttached()) {
                throw new IllegalStateException("Component already attached: " + component);
            }
            replaced = List.of();
            if (type != null) {
                List<ComponentEntry> clashing = view.byType.getOrDefault(type, List.of());
                if (!clashing.isEmpty()) {
                    switch (type.duplicatePolicy()) {
                        case REJECT -> throw new DuplicateComponentException(
                                "Duplicate component of type '" + type.id() + "' on " + id);
                        case REPLACE -> replaced = List.copyOf(clashing);
                        case ALLOW_MULTIPLE -> {
                        }
                    }
                }
            }
            List<ComponentEntry> next = new ArrayList<>(view.entries);
            next.removeAll(replaced);
            handle.bind(this);
            for (ComponentEntry entry : replaced) {
                asAbstract(entry.component()).bind(null);
            }
            next.add(new ComponentEntry(component, type, sequencer.getAndIncrement()));
            next.sort(ORDER);
            view = View.of(next);
        } finally {
            writeLock.unlock();
        }
        List<Throwable> failures = new ArrayList<>();
        for (ComponentEntry entry : replaced) {
            try {
                entry.component().onDetached();
            } catch (Throwable failure) {
                failures.add(failure);
            }
            fireRemoved(entry.component(), failures);
        }
        try {
            component.onAttached();
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            fireAdded(component, failures);
            fireOrderChanged(failures);
        }
        throwIfFailed(failures, "WorldObject.attach callbacks failed for " + id);
        return component;
    }

    // Detach by handle, class or type.

    /**
     * Detaches this exact instance. Returns {@code true} if it was present.
     */
    public boolean detachComponent(WorldObjectComponent component) {
        Objects.requireNonNull(component, "component");
        ComponentEntry target;
        writeLock.lock();
        try {
            if (view.detached) {
                return false;
            }
            target = null;
            for (ComponentEntry entry : view.entries) {
                if (entry.component() == component) {
                    target = entry;
                    break;
                }
            }
            if (target == null) {
                return false;
            }
            List<ComponentEntry> next = new ArrayList<>(view.entries);
            next.remove(target);
            view = View.of(next);
            asAbstract(component).bind(null);
        } finally {
            writeLock.unlock();
        }
        List<Throwable> failures = new ArrayList<>();
        try {
            target.component().onDetached();
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            fireRemoved(target.component(), failures);
        }
        throwIfFailed(failures, "WorldObject.detach callbacks failed for " + id);
        return true;
    }

    /**
     * Detaches every component assignable to {@code componentClass}.
     */
    public <C extends WorldObjectComponent> List<C> detachAll(Class<C> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        return detachMatching(
                entry -> componentClass.isInstance(entry.component()), componentClass::cast);
    }

    /**
     * Detaches every component attached under {@code type}.
     */
    public <C extends WorldObjectComponent> List<C> detachAll(WorldObjectComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        return detachMatching(entry -> matches(entry, type), entry -> type.componentClass().cast(entry));
    }

    private <C extends WorldObjectComponent> List<C> detachMatching(
            java.util.function.Predicate<ComponentEntry> filter,
            java.util.function.Function<WorldObjectComponent, C> cast) {
        List<ComponentEntry> removed;
        writeLock.lock();
        try {
            if (view.detached) {
                return List.of();
            }
            removed = new ArrayList<>();
            List<ComponentEntry> next = new ArrayList<>();
            for (ComponentEntry entry : view.entries) {
                if (filter.test(entry)) {
                    removed.add(entry);
                } else {
                    next.add(entry);
                }
            }
            if (removed.isEmpty()) {
                return List.of();
            }
            view = View.of(next);
            for (ComponentEntry entry : removed) {
                asAbstract(entry.component()).bind(null);
            }
        } finally {
            writeLock.unlock();
        }
        List<C> result = new ArrayList<>(removed.size());
        List<Throwable> failures = new ArrayList<>();
        for (ComponentEntry entry : removed) {
            try {
                entry.component().onDetached();
            } catch (Throwable failure) {
                failures.add(failure);
            } finally {
                fireRemoved(entry.component(), failures);
            }
            result.add(cast.apply(entry.component()));
        }
        throwIfFailed(failures, "WorldObject.detach callbacks failed for " + id);
        return List.copyOf(result);
    }

    // Lock-free reads. Lookups by type/class resolve through the per-snapshot
    // index; polymorphic class queries still scan the entries linearly, which is
    // a single cache line for the expected handful of components per object.

    /**
     * Immutable snapshot of entries (component plus its attach type). Never blocks.
     */
    public List<ComponentEntry> entries() {
        return view.entries;
    }

    /**
     * Immutable snapshot of components in tick order. Never blocks.
     */
    public List<WorldObjectComponent> components() {
        List<ComponentEntry> entries = view.entries;
        List<WorldObjectComponent> result = new ArrayList<>(entries.size());
        for (ComponentEntry entry : entries) {
            result.add(entry.component());
        }
        return List.copyOf(result);
    }

    /**
     * All components assignable to {@code componentClass}, in tick order.
     */
    public <C extends WorldObjectComponent> List<C> get(Class<C> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        List<ComponentEntry> entries = view.entries;
        List<C> result = new ArrayList<>();
        for (ComponentEntry entry : entries) {
            if (componentClass.isInstance(entry.component())) {
                result.add(componentClass.cast(entry.component()));
            }
        }
        return List.copyOf(result);
    }

    /**
     * First component assignable to {@code componentClass}, if any.
     */
    public <C extends WorldObjectComponent> Optional<C> getSingle(Class<C> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass");
        for (ComponentEntry entry : view.entries) {
            if (componentClass.isInstance(entry.component())) {
                return Optional.of(componentClass.cast(entry.component()));
            }
        }
        return Optional.empty();
    }

    /**
     * All components attached under {@code type}, in tick order. O(1) index lookup.
     */
    public <C extends WorldObjectComponent> List<C> get(WorldObjectComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        List<ComponentEntry> entries = view.byType.getOrDefault(type, List.of());
        List<C> result = new ArrayList<>(entries.size());
        for (ComponentEntry entry : entries) {
            result.add(type.componentClass().cast(entry.component()));
        }
        return List.copyOf(result);
    }

    /**
     * First component attached under {@code type}, if any. O(1) index lookup.
     */
    public <C extends WorldObjectComponent> Optional<C> getSingle(WorldObjectComponentType<C> type) {
        Objects.requireNonNull(type, "type");
        List<ComponentEntry> entries = view.byType.getOrDefault(type, List.of());
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(type.componentClass().cast(entries.get(0).component()));
    }

    /**
     * Returns {@code true} if any component is assignable to {@code componentClass}.
     */
    public boolean has(Class<? extends WorldObjectComponent> componentClass) {
        return getSingle(componentClass).isPresent();
    }

    /**
     * Returns {@code true} if any component is attached under {@code type}. O(1).
     */
    public boolean has(WorldObjectComponentType<?> type) {
        Objects.requireNonNull(type, "type");
        return view.byType.containsKey(type);
    }

    /**
     * Number of attached components.
     */
    public int size() {
        return view.entries.size();
    }

    /**
     * Re-sorts by current {@code priority()} values. Returns {@code true} on change.
     */
    public boolean resort() {
        boolean changed;
        writeLock.lock();
        try {
            if (view.detached) {
                return false;
            }
            List<ComponentEntry> next = new ArrayList<>(view.entries);
            next.sort(ORDER);
            changed = !orderEquals(next, view.entries);
            if (changed) {
                view = View.of(next);
            }
        } finally {
            writeLock.unlock();
        }
        if (changed) {
            List<Throwable> failures = new ArrayList<>();
            fireOrderChanged(failures);
            throwIfFailed(failures, "WorldObject.resort callbacks failed for " + id);
        }
        return changed;
    }

    // Tick and deferred work.

    /**
     * Ticks every {@link TickableComponent} in order over a lock-free snapshot.
     * Concurrent attach/detach never breaks the iteration; components attached
     * mid-tick tick on the next call. Per-component failures are reported to
     * listeners and aggregated into {@link TickException}. Deferred tasks run after.
     */
    public void tick(long tick) {
        if (view.detached) {
            return;
        }
        List<Throwable> failures = null;
        for (ComponentEntry entry : view.entries) {
            if (entry.component() instanceof TickableComponent tickable) {
                try {
                    tickable.onTick(tick);
                } catch (Throwable failure) {
                    if (failures == null) {
                        failures = new ArrayList<>();
                    }
                    failures.add(failure);
                    fireError(entry.component(), failure);
                }
            }
        }
        try {
            drainDeferred();
        } catch (WorldObjectException failure) {
            if (failures == null) {
                failures = new ArrayList<>();
            }
            failures.add(failure);
        }
        if (failures != null) {
            TickException aggregated = new TickException(
                    "Tick failed for " + failures.size() + " component(s) on " + id);
            for (Throwable failure : failures) {
                aggregated.addSuppressed(failure);
            }
            throw aggregated;
        }
    }

    /**
     * Queues work to run on the ticking thread. A task queued from a tick runs after
     * the remaining components of that tick; a task queued from a mutation callback
     * runs on the next {@link #tick}/{@link #drainDeferred} call. Best-effort across
     * {@link #detach}: tasks still queued at detach time are discarded.
     */
    public void defer(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (view.detached) {
            throw new DetachedException("WorldObject " + id + " is detached");
        }
        deferred.add(task);
    }

    /**
     * Runs all queued deferred tasks on the calling thread.
     */
    public void drainDeferred() {
        List<Throwable> failures = null;
        Runnable task;
        while ((task = deferred.poll()) != null) {
            try {
                task.run();
            } catch (Throwable failure) {
                if (failures == null) {
                    failures = new ArrayList<>();
                }
                failures.add(failure);
            }
        }
        if (failures != null) {
            throwIfFailed(failures, "Deferred tasks failed for " + id);
        }
    }

    /**
     * Returns {@code true} if deferred tasks are queued.
     */
    public boolean hasPendingDeferred() {
        return !deferred.isEmpty();
    }

    // Listeners.

    /**
     * Subscribes a listener. Safe to call at any time; never blocks readers.
     */
    public void addListener(WorldObjectListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unsubscribes a listener.
     */
    public void removeListener(WorldObjectListener listener) {
        listeners.remove(listener);
    }

    private void fireAdded(WorldObjectComponent component, List<Throwable> failures) {
        for (WorldObjectListener listener : listeners) {
            try {
                listener.onComponentAdded(this, component);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
    }

    private void fireRemoved(WorldObjectComponent component, List<Throwable> failures) {
        for (WorldObjectListener listener : listeners) {
            try {
                listener.onComponentRemoved(this, component);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
    }

    private void fireOrderChanged(List<Throwable> failures) {
        for (WorldObjectListener listener : listeners) {
            try {
                listener.onOrderChanged(this);
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
    }

    private void fireError(WorldObjectComponent component, Throwable cause) {
        for (WorldObjectListener listener : listeners) {
            try {
                listener.onComponentError(this, component, cause);
            } catch (Throwable ignored) {
                // Error reporting must never break the tick of remaining components.
            }
        }
    }

    // Debug view.

    /**
     * Debug view: one type id per attached component ({@code "untyped:"} plus class
     * name for untyped ones). For real persistence see
     * {@code libs.world.sync.SyncedWorldObjectComponent}.
     */
    public List<String> describe() {
        List<String> result = new ArrayList<>();
        for (ComponentEntry entry : view.entries) {
            if (entry.type() != null) {
                result.add(entry.type().id());
            } else {
                result.add("untyped:" + entry.component().getClass().getName());
            }
        }
        return List.copyOf(result);
    }

    // Internals.

    private void requireLive() {
        if (view.detached) {
            throw new DetachedException("WorldObject " + id + " is detached");
        }
    }

    private static AbstractWorldObjectComponent asAbstract(WorldObjectComponent component) {
        if (!(component instanceof AbstractWorldObjectComponent handle)) {
            throw new IllegalArgumentException(
                    "Component must extend AbstractWorldObjectComponent: " + component.getClass().getName());
        }
        return handle;
    }

    private static boolean matches(ComponentEntry entry, WorldObjectComponentType<?> type) {
        if (type.equals(entry.type())) {
            return true;
        }
        return entry.type() == null && type.componentClass().isInstance(entry.component());
    }

    private static boolean orderEquals(List<ComponentEntry> first, List<ComponentEntry> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i).component() != second.get(i).component()) {
                return false;
            }
        }
        return true;
    }

    private static void throwIfFailed(List<Throwable> failures, String message) {
        if (failures == null || failures.isEmpty()) {
            return;
        }
        WorldObjectException aggregated = new WorldObjectException(
                message + " (" + failures.size() + " failure(s))");
        for (Throwable failure : failures) {
            aggregated.addSuppressed(failure);
        }
        throw aggregated;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorldObject that)) {
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
        View current = view;
        return "WorldObject{id=" + id + ", detached=" + current.detached + ", size=" + current.entries.size() + "}";
    }

    /**
     * Immutable per-snapshot state: ordered entries plus a type index.
     * Built once per mutation under the write lock, then shared lock-free.
     * The index maps exact attach types to their entries in tick order; untyped
     * components live only in {@link #entries}. Memory cost is one map plus one
     * list per distinct type — negligible against the copy-on-write snapshot itself.
     */
    private static final class View {
        static final View EMPTY = new View(List.of(), Map.of(), false);

        final List<ComponentEntry> entries;
        final Map<WorldObjectComponentType<?>, List<ComponentEntry>> byType;
        final boolean detached;

        private View(
                List<ComponentEntry> entries,
                Map<WorldObjectComponentType<?>, List<ComponentEntry>> byType,
                boolean detached) {
            this.entries = entries;
            this.byType = byType;
            this.detached = detached;
        }

        static View of(List<ComponentEntry> entries) {
            List<ComponentEntry> frozen = List.copyOf(entries);
            Map<WorldObjectComponentType<?>, List<ComponentEntry>> index = new HashMap<>();
            for (ComponentEntry entry : frozen) {
                if (entry.type() != null) {
                    index.computeIfAbsent(entry.type(), key -> new ArrayList<>()).add(entry);
                }
            }
            index.replaceAll((key, list) -> List.copyOf(list));
            return new View(frozen, Map.copyOf(index), false);
        }

        View detached() {
            return new View(List.of(), Map.of(), true);
        }
    }
}
