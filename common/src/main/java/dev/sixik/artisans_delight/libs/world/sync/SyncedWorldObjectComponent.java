package dev.sixik.artisans_delight.libs.world.sync;

import dev.sixik.artisans_delight.libs.world.FieldedWorldObjectComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Component base with declarative {@link TrackedField}s, full-state NBT persistence
 * and revision-based network deltas.
 *
 * <p>Usage: declare fields via {@link #tracked} (inherited from
 * {@code FieldedWorldObjectComponent}), then mutate through the returned handles.
 * Only {@link TrackedField} instances participate in the network schema; a plain
 * {@link Field} declared via {@code field()} is NBT-only and never touches the wire.
 * Field order is declaration order and is part of the network schema:
 * {@link #writeFull} writes values in that order, {@link #readFull} reads them back
 * positionally. NBT persistence ({@link #writeNbt}/{@link #readNbt}) comes from
 * {@code FieldedWorldObjectComponent} and is key-based and tolerant: missing keys
 * keep current values.
 *
 * <p>Threading: fields are individually lock-striped ({@link StampedLock} each),
 * so concurrent writes to different fields never contend. Delta collection takes
 * per-field read stamps one at a time; a field that changes mid-collection lands
 * in the next delta, never torn within one field. {@link #writeDelta}/{@link
 * #readDelta} carry {@code (fieldIndex, revision)} pairs, so a newer full sync
 * cannot be overwritten by a stale delta on arrival.
 *
 * <p>Reader protocol: each side keeps the last applied revision per field index
 * (start at {@code -1}). After {@link #readFull}, reset all trackers to the
 * sender's revisions. After {@link #readDelta}, apply only entries with
 * {@code revision > known}, then store the sender's revision per touched field.
 * {@link DeltaTracker} implements exactly this bookkeeping.
 *
 * <p>Network schema: {@link #schemaFingerprint} hashes
 * {@code name + '\0' + codec.schemaName()} per field. Compare fingerprints before
 * applying a delta; on mismatch request a full sync or drop the packet instead of
 * decoding garbage. NBT persistence ({@link #writeNbt}/{@link #readNbt}) is
 * key-based and tolerant: missing keys keep current values.
 */
public abstract class SyncedWorldObjectComponent extends FieldedWorldObjectComponent {

    /**
     * Networked fields: every declared {@link TrackedField} in declaration order.
     * A plain {@link Field} (NBT-only) here is a schema error — network positions
     * must be dense, so it fails fast with {@link SyncException}.
     */
    private List<TrackedField<?>> networkFields() {
        List<Field<?>> all = syncedFields();
        List<TrackedField<?>> networked = new ArrayList<>(all.size());
        for (Field<?> field : all) {
            if (!(field instanceof TrackedField<?> tracked)) {
                throw new SyncException("Field '" + field.name() + "' in " + getClass().getName()
                        + " is NBT-only: declare networked fields via tracked(), not field()");
            }
            networked.add(tracked);
        }
        return networked;
    }

    /**
     * Stable fingerprint of the network schema. Exchange once per session (or with
     * every delta in dev builds) and refuse deltas on mismatch.
     */
    public final long schemaFingerprint() {
        List<TrackedField<?>> fields = networkFields();
        long hash = 0x9E3779B97F4A7C15L;
        for (TrackedField<?> tracked : fields) {
            hash = mix(hash, tracked.name());
            hash = mix(hash, tracked.codec().schemaName());
            hash = hash * 0x100000001B3L + fields.size();
        }
        return hash;
    }

    /**
     * Writes every field value in declaration order (no field names on the wire).
     */
    public final void writeFull(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        writeRevisions(buf);
        for (TrackedField<?> tracked : fields) {
            writeValue(buf, tracked);
        }
    }

    /**
     * Reads a full state written by {@link #writeFull}. Unknown trailing data is
     * left for the caller; missing fields are impossible by construction (full
     * always carries all), so any short read throws from the buffer itself.
     */
    public final void readFull(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        long[] revisions = readRevisions(buf);
        for (int i = 0; i < fields.size(); i++) {
            readValue(buf, fields.get(i));
        }
    }

    /**
     * Reads a full state and returns the sender revisions per field index.
     * Feed them into {@link DeltaTracker#reset} on the receiving side.
     */
    public final long[] readFullWithRevisions(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        long[] revisions = readRevisions(buf);
        for (TrackedField<?> tracked : fields) {
            readValue(buf, tracked);
        }
        return revisions;
    }

    /**
     * Writes only fields whose revision is newer than the tracker's snapshot.
     * Format: {@code varint count}, then per entry {@code varint index},
     * {@code varlong revision}, value. Returns {@code true} when at least one
     * field was written. The tracker snapshot is advanced to the component's
     * current revisions for every field, touched or not.
     */
    public final boolean writeDelta(FriendlyByteBuf buf, DeltaTracker tracker) {
        Objects.requireNonNull(tracker, "tracker");
        List<TrackedField<?>> fields = networkFields();
        tracker.ensureCapacity(fields.size());
        List<Integer> touched = null;
        for (int i = 0; i < fields.size(); i++) {
            TrackedField<?> tracked = fields.get(i);
            if (tracked.revision() > tracker.known(i)) {
                if (touched == null) {
                    touched = new ArrayList<>();
                }
                touched.add(i);
            }
        }
        if (touched == null) {
            tracker.advanceTo(currentRevisions());
            return false;
        }
        buf.writeVarInt(touched.size());
        for (int index : touched) {
            TrackedField<?> tracked = fields.get(index);
            buf.writeVarInt(index);
            buf.writeVarLong(tracked.revision());
            writeValue(buf, tracked);
        }
        tracker.advanceTo(currentRevisions());
        return true;
    }

    /**
     * Applies a delta written by {@link #writeDelta}. Entries with
     * {@code revision <= tracker.known(index)} are skipped but still consumed
     * from the buffer, so out-of-order redelivery cannot roll state back.
     * Unknown indexes throw {@link SyncException}.
     */
    public final void readDelta(FriendlyByteBuf buf, DeltaTracker tracker) {
        Objects.requireNonNull(tracker, "tracker");
        List<TrackedField<?>> fields = networkFields();
        tracker.ensureCapacity(fields.size());
        int count = buf.readVarInt();
        if (count < 0 || count > fields.size() * 2) {
            throw new SyncException("Delta entry count out of bounds: " + count);
        }
        for (int n = 0; n < count; n++) {
            int index = buf.readVarInt();
            long revision = buf.readVarLong();
            if (index < 0 || index >= fields.size()) {
                throw new SyncException("Delta references unknown field index " + index
                        + " in " + getClass().getName());
            }
            TrackedField<?> tracked = fields.get(index);
            if (revision <= tracker.known(index)) {
                skipValue(buf, tracked);
                continue;
            }
            readValue(buf, tracked);
            tracker.mark(index, revision);
        }
    }

    // NBT persistence is inherited from FieldedWorldObjectComponent (key-based,
    // tolerant to missing keys). Network helpers below.

    /**
     * Per-viewer revision bookkeeping for {@link #writeDelta}/{@link #readDelta}.
     * Not thread-safe by itself: confine one tracker per viewer/session and touch
     * it from a single thread, or guard externally.
     */
    public static final class DeltaTracker {

        private long[] known = new long[0];

        /**
         * Creates a tracker pre-sized for {@code fieldCount} fields, all unknown ({@code -1}).
         */
        public DeltaTracker(int fieldCount) {
            ensureCapacity(fieldCount);
        }

        /**
         * Creates an empty tracker; grows on first use.
         */
        public DeltaTracker() {
        }

        /**
         * Grows capacity to at least {@code fieldCount}, filling new slots with {@code -1}.
         */
        public void ensureCapacity(int fieldCount) {
            if (fieldCount < 0) {
                throw new IllegalArgumentException("fieldCount must be >= 0");
            }
            if (known.length >= fieldCount) {
                return;
            }
            long[] grown = new long[fieldCount];
            System.arraycopy(known, 0, grown, 0, known.length);
            for (int i = known.length; i < grown.length; i++) {
                grown[i] = -1L;
            }
            known = grown;
        }

        /**
         * Last applied revision for field {@code index} ({@code -1} = unknown).
         */
        public long known(int index) {
            if (index < 0 || index >= known.length) {
                return -1L;
            }
            return known[index];
        }

        /**
         * Records the sender revision for field {@code index}.
         */
        public void mark(int index, long revision) {
            ensureCapacity(index + 1);
            known[index] = revision;
        }

        /**
         * Resets all trackers to the sender's full-sync revisions.
         */
        public void reset(long[] revisions) {
            Objects.requireNonNull(revisions, "revisions");
            ensureCapacity(revisions.length);
            System.arraycopy(revisions, 0, known, 0, revisions.length);
        }

        /**
         * Advances every slot to {@code revisions[index]}.
         */
        void advanceTo(long[] revisions) {
            ensureCapacity(revisions.length);
            System.arraycopy(revisions, 0, known, 0, revisions.length);
        }
    }

    private long[] currentRevisions() {
        List<TrackedField<?>> fields = networkFields();
        long[] revisions = new long[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            revisions[i] = fields.get(i).revision();
        }
        return revisions;
    }

    private void writeRevisions(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        buf.writeVarInt(fields.size());
        for (TrackedField<?> tracked : fields) {
            buf.writeVarLong(tracked.revision());
        }
    }

    private long[] readRevisions(FriendlyByteBuf buf) {
        List<TrackedField<?>> fields = networkFields();
        int size = buf.readVarInt();
        if (size != fields.size()) {
            throw new SyncException("Full sync field count mismatch: got " + size
                    + ", expected " + fields.size() + " in " + getClass().getName());
        }
        long[] revisions = new long[size];
        for (int i = 0; i < size; i++) {
            revisions[i] = buf.readVarLong();
        }
        return revisions;
    }

    private static long mix(long hash, String text) {
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001B3L;
        }
        hash ^= 0xFF;
        hash *= 0x100000001B3L;
        return hash;
    }

    private static void writeValue(FriendlyByteBuf buf, TrackedField<?> tracked) {
        writeValueCaptured(buf, tracked);
    }

    private static <Value> void writeValueCaptured(FriendlyByteBuf buf, TrackedField<Value> tracked) {
        tracked.codec().toNetwork(buf, tracked.get());
    }

    private static void readValue(FriendlyByteBuf buf, TrackedField<?> tracked) {
        readValueCaptured(buf, tracked);
    }

    private static <Value> void readValueCaptured(FriendlyByteBuf buf, TrackedField<Value> tracked) {
        tracked.set(tracked.codec().fromNetwork(buf));
    }

    private static void skipValue(FriendlyByteBuf buf, TrackedField<?> tracked) {
        // Consume and discard: decode into a throwaway to keep the cursor aligned.
        skipValueCaptured(buf, tracked);
    }

    private static <Value> void skipValueCaptured(FriendlyByteBuf buf, TrackedField<Value> tracked) {
        tracked.codec().fromNetwork(buf);
    }
}
