package dev.sixik.artisans_delight.libs.world.sync;

import java.util.Objects;
import java.util.concurrent.locks.StampedLock;
import java.util.function.UnaryOperator;

/**
 * A single named value guarded by a {@link StampedLock}, with a monotonic revision
 * and codec-based snapshot comparison.
 *
 * <p>Threading: optimistic reads for {@link #get}, exclusive writes for {@link #set}
 * and friends. Every write performs compare-and-copy under the write stamp: the new
 * value is compared against the live value, not against a stale snapshot, so a
 * concurrent write can never be silently dropped by a lost-update race. The revision
 * increments only when the value actually changes (by {@link FieldCodec#areEqual});
 * setting an equal value leaves {@link #revision} untouched, which is what keeps
 * network deltas quiet.
 *
 * <p>Values must be effectively immutable to readers: {@link #get} returns the live
 * reference without copying. For mutable value types use {@link #update} with a
 * copy-on-write function, or a codec whose {@link FieldCodec#copy} deep-copies and
 * rely on {@link #set}/{@link #update} to install the replacement.
 *
 * @param <Value> field value type
 */
public final class TrackedField<Value> {

    private final String name;
    private final FieldCodec<Value> codec;
    private final StampedLock lock = new StampedLock();

    private volatile Value value;
    private volatile long revision;

    /**
     * Creates a field with the given default. The default is copied once through
     * {@link FieldCodec#copy} so later external mutation of the argument cannot
     * leak into the field.
     */
    public TrackedField(String name, FieldCodec<Value> codec, Value defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.value = codec.copy(defaultValue);
        this.revision = 0L;
    }

    /**
     * Field name, stable across restarts. Used as the NBT key and delta id.
     */
    public String name() {
        return name;
    }

    /**
     * Codec used for NBT, network and equality.
     */
    public FieldCodec<Value> codec() {
        return codec;
    }

    /**
     * Returns the live value (no copy). Optimistic read, never blocks writers.
     */
    public Value get() {
        long stamp = lock.tryOptimisticRead();
        Value current = value;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                current = value;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return current;
    }

    /**
     * Returns the current revision. Increments on every effective change.
     */
    public long revision() {
        return revision;
    }

    /**
     * Installs {@code newValue}. Returns {@code true} when the value changed.
     * The comparison runs under the write stamp against the live value, so racing
     * writers serialize and no update is lost: each writer compares its own
     * argument to whatever is current at its turn.
     */
    public boolean set(Value newValue) {
        long stamp = lock.writeLock();
        try {
            Value current = value;
            if (codec.areEqual(current, newValue)) {
                return false;
            }
            value = codec.copy(newValue);
            revision++;
            return true;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Atomically replaces the value with {@code remap.apply(live)}. The function
     * runs under the write stamp against the live value, so read-modify-write
     * sequences (counters, appends) cannot interleave. Returns {@code true} on change.
     */
    public boolean update(UnaryOperator<Value> remap) {
        Objects.requireNonNull(remap, "remap");
        long stamp = lock.writeLock();
        try {
            Value current = value;
            Value next = remap.apply(codec.copy(current));
            if (codec.areEqual(current, next)) {
                return false;
            }
            value = codec.copy(next);
            revision++;
            return true;
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    /**
     * Copies the value if its revision is newer than {@code knownRevision}.
     * Returns {@code null} when nothing changed, which lets callers build deltas
     * without allocating per-field snapshots every tick.
     */
    public Value copyIfNewerThan(long knownRevision) {
        long stamp = lock.readLock();
        try {
            if (revision <= knownRevision) {
                return null;
            }
            return codec.copy(value);
        } finally {
            lock.unlockRead(stamp);
        }
    }

    /**
     * Compares the live value against {@code snapshot} with codec equality.
     */
    public boolean isEqualTo(Value snapshot) {
        long stamp = lock.tryOptimisticRead();
        Value current = value;
        if (!lock.validate(stamp)) {
            stamp = lock.readLock();
            try {
                current = value;
            } finally {
                lock.unlockRead(stamp);
            }
        }
        return codec.areEqual(current, snapshot);
    }
}
