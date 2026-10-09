package dev.sixik.artisans_delight.libs.world.sync;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Describes how a single component field is read and written to NBT and the network buffer.
 *
 * <p>This is a port of the {@code sdmshop2} JSON-based {@code FieldCodec}: NBT replaces
 * JSON as the disk format, {@link FriendlyByteBuf} stays the network transport.
 * Implementations must be stateless and thread-safe; a codec is shared by every
 * {@link TrackedField} of its value type.
 *
 * @param <Value> field value type
 */
public interface FieldCodec<Value> {

    /**
     * Writes the field value into {@code nbt} under {@code key}.
     */
    void toNbt(CompoundTag nbt, String key, Value value);

    /**
     * Reads the field value from {@code nbt}, or returns {@code defaultValue} when
     * the key is absent.
     */
    Value fromNbt(CompoundTag nbt, String key, Value defaultValue);

    /**
     * Writes the value as a standalone {@link Tag}.
     * Used by nested codecs (lists, optionals) to avoid building a temporary
     * {@link CompoundTag} per element.
     */
    default Tag toNbtElement(Value value) {
        CompoundTag nbt = new CompoundTag();
        toNbt(nbt, "value", value);
        Tag element = nbt.get("value");
        if (element == null) {
            throw new SyncException("Codec " + schemaName() + " wrote no 'value' tag");
        }
        return element;
    }

    /**
     * Reads the value from a standalone {@link Tag}.
     * A {@code null} element yields {@code defaultValue}.
     */
    default Value fromNbtElement(Tag element, Value defaultValue) {
        if (element == null) {
            return defaultValue;
        }
        CompoundTag nbt = new CompoundTag();
        nbt.put("value", element);
        return fromNbt(nbt, "value", defaultValue);
    }

    /**
     * Writes the value into the network buffer.
     */
    void toNetwork(FriendlyByteBuf buf, Value value);

    /**
     * Reads the value from the network buffer.
     */
    Value fromNetwork(FriendlyByteBuf buf);

    /**
     * Creates a defensive copy for snapshot/diff logic.
     * Immutable values (ints, strings, records) may return as-is.
     */
    default Value copy(Value value) {
        return value;
    }

    /**
     * Compares two values with codec-specific rules.
     */
    default boolean areEqual(Value first, Value second) {
        return Objects.equals(first, second);
    }

    /**
     * Returns the codec name used for network schema compatibility checks.
     */
    default String schemaName() {
        return getClass().getName();
    }

    /**
     * Creates a builder for ad-hoc codec declarations.
     */
    static <Value> Builder<Value> builder() {
        return new Builder<>();
    }

    /**
     * Builder for assembling a codec from read/write functions.
     */
    final class Builder<Value> {
        private String schemaName;
        private NbtWriter<Value> nbtWriter;
        private NbtReader<Value> nbtReader;
        private NbtElementWriter<Value> nbtElementWriter;
        private NbtElementReader<Value> nbtElementReader;
        private NetworkWriter<Value> networkWriter;
        private NetworkReader<Value> networkReader;
        private ValueCopier<Value> copier = value -> value;
        private ValueEquality<Value> equality = Objects::equals;

        /**
         * Sets the stable codec name for the network schema.
         */
        public Builder<Value> schema(String schemaName) {
            this.schemaName = schemaName;
            return this;
        }

        /**
         * Sets the NBT read/write functions.
         */
        public Builder<Value> nbt(NbtWriter<Value> writer, NbtReader<Value> reader) {
            this.nbtWriter = writer;
            this.nbtReader = reader;
            return this;
        }

        /**
         * Sets fast standalone-{@link Tag} read/write functions.
         */
        public Builder<Value> nbtElement(NbtElementWriter<Value> writer, NbtElementReader<Value> reader) {
            this.nbtElementWriter = writer;
            this.nbtElementReader = reader;
            return this;
        }

        /**
         * Sets the network buffer read/write functions.
         */
        public Builder<Value> network(NetworkWriter<Value> writer, NetworkReader<Value> reader) {
            this.networkWriter = writer;
            this.networkReader = reader;
            return this;
        }

        /**
         * Sets how values are copied for snapshots.
         */
        public Builder<Value> copy(ValueCopier<Value> copier) {
            this.copier = copier == null ? value -> value : copier;
            return this;
        }

        /**
         * Sets how values are compared.
         */
        public Builder<Value> equality(ValueEquality<Value> equality) {
            this.equality = equality == null ? Objects::equals : equality;
            return this;
        }

        /**
         * Builds the codec, verifying that all mandatory functions are set.
         */
        public FieldCodec<Value> build() {
            if (nbtWriter == null) {
                throw new IllegalStateException("Nbt writer is not configured");
            }
            if (nbtReader == null) {
                throw new IllegalStateException("Nbt reader is not configured");
            }
            if (networkWriter == null) {
                throw new IllegalStateException("Network writer is not configured");
            }
            if (networkReader == null) {
                throw new IllegalStateException("Network reader is not configured");
            }
            String finalSchemaName = schemaName == null ? "custom:" + getClass().getName() : schemaName;

            return new FieldCodec<>() {
                @Override
                public void toNbt(CompoundTag nbt, String key, Value value) {
                    nbtWriter.write(nbt, key, value);
                }

                @Override
                public Value fromNbt(CompoundTag nbt, String key, Value defaultValue) {
                    return nbtReader.read(nbt, key, defaultValue);
                }

                @Override
                public Tag toNbtElement(Value value) {
                    if (nbtElementWriter != null) {
                        Tag element = nbtElementWriter.write(value);
                        if (element == null) {
                            throw new SyncException("Codec " + finalSchemaName + " produced null element");
                        }
                        return element;
                    }

                    CompoundTag nbt = new CompoundTag();
                    nbtWriter.write(nbt, "value", value);
                    Tag element = nbt.get("value");
                    if (element == null) {
                        throw new SyncException("Codec " + finalSchemaName + " wrote no 'value' tag");
                    }
                    return element;
                }

                @Override
                public Value fromNbtElement(Tag element, Value defaultValue) {
                    if (element == null) {
                        return defaultValue;
                    }
                    if (nbtElementReader != null) {
                        return nbtElementReader.read(element, defaultValue);
                    }

                    CompoundTag nbt = new CompoundTag();
                    nbt.put("value", element);
                    return nbtReader.read(nbt, "value", defaultValue);
                }

                @Override
                public void toNetwork(FriendlyByteBuf buf, Value value) {
                    networkWriter.write(buf, value);
                }

                @Override
                public Value fromNetwork(FriendlyByteBuf buf) {
                    return networkReader.read(buf);
                }

                @Override
                public Value copy(Value value) {
                    return copier.copy(value);
                }

                @Override
                public boolean areEqual(Value first, Value second) {
                    return equality.areEqual(first, second);
                }

                @Override
                public String schemaName() {
                    return finalSchemaName;
                }
            };
        }
    }

    /**
     * Writes a value into NBT.
     */
    @FunctionalInterface
    interface NbtWriter<Value> {
        void write(CompoundTag nbt, String key, Value value);
    }

    /**
     * Reads a value from NBT.
     */
    @FunctionalInterface
    interface NbtReader<Value> {
        Value read(CompoundTag nbt, String key, Value defaultValue);
    }

    /**
     * Writes a value as a standalone {@link Tag}.
     */
    @FunctionalInterface
    interface NbtElementWriter<Value> {
        Tag write(Value value);
    }

    /**
     * Reads a value from a standalone {@link Tag}.
     */
    @FunctionalInterface
    interface NbtElementReader<Value> {
        Value read(Tag element, Value defaultValue);
    }

    /**
     * Writes a value into the network buffer.
     */
    @FunctionalInterface
    interface NetworkWriter<Value> {
        void write(FriendlyByteBuf buf, Value value);
    }

    /**
     * Reads a value from the network buffer.
     */
    @FunctionalInterface
    interface NetworkReader<Value> {
        Value read(FriendlyByteBuf buf);
    }

    /**
     * Copies a value.
     */
    @FunctionalInterface
    interface ValueCopier<Value> {
        Value copy(Value value);
    }

    /**
     * Compares two values.
     */
    @FunctionalInterface
    interface ValueEquality<Value> {
        boolean areEqual(Value first, Value second);
    }
}
