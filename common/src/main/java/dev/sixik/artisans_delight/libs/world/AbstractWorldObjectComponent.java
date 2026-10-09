package dev.sixik.artisans_delight.libs.world;

import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Thread-safe base for {@link WorldObjectComponent} implementations.
 *
 * <p>State convention: guard mutable fields with {@link #withRead}/{@link #runWrite}
 * (or the exposed {@link #readLock}/{@link #writeLock} directly). Ownership is managed
 * by {@link WorldObject} only; the {@code bind} method is package-private on purpose.
 */
public abstract class AbstractWorldObjectComponent implements WorldObjectComponent {

    private final ReadWriteLock stateLock = new ReentrantReadWriteLock();

    private volatile WorldObject owner;
    private volatile boolean attached;

    @Override
    public final Optional<WorldObject> owner() {
        return Optional.ofNullable(owner);
    }

    @Override
    public final boolean isAttached() {
        return attached;
    }

    /**
     * Returns the owner or throws {@link IllegalStateException} while detached.
     */
    protected final WorldObject requireOwner() {
        WorldObject current = owner;
        if (current == null) {
            throw new IllegalStateException(getClass().getName() + " is detached");
        }
        return current;
    }

    /**
     * Runs {@code action} under the read lock and returns its result.
     */
    protected final <T> T withRead(Supplier<T> action) {
        stateLock.readLock().lock();
        try {
            return action.get();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Runs {@code action} under the read lock.
     */
    protected final void runRead(Runnable action) {
        stateLock.readLock().lock();
        try {
            action.run();
        } finally {
            stateLock.readLock().unlock();
        }
    }

    /**
     * Runs {@code action} under the write lock and returns its result.
     */
    protected final <T> T withWrite(Supplier<T> action) {
        stateLock.writeLock().lock();
        try {
            return action.get();
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /**
     * Runs {@code action} under the write lock.
     */
    protected final void runWrite(Runnable action) {
        stateLock.writeLock().lock();
        try {
            action.run();
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /**
     * Direct read lock for hand-rolled critical sections.
     */
    protected final Lock readLock() {
        return stateLock.readLock();
    }

    /**
     * Direct write lock for hand-rolled critical sections.
     */
    protected final Lock writeLock() {
        return stateLock.writeLock();
    }

    /**
     * Called by {@link WorldObject} only. A {@code null} owner means detached.
     */
    final void bind(WorldObject newOwner) {
        if (newOwner != null && owner != null && owner != newOwner) {
            throw new IllegalStateException(
                    getClass().getName() + " already attached to " + owner.id());
        }
        owner = newOwner;
        attached = newOwner != null;
    }
}
