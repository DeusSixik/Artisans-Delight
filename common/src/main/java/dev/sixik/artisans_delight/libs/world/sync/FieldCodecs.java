package dev.sixik.artisans_delight.libs.world.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Built-in {@link FieldCodec} instances and combinators.
 *
 * <p>All codecs are stateless singletons (except parameterized {@link #listOf} /
 * {@link #enumCodec} results) and safe to share across threads. Every schema name
 * is stable across restarts; renaming one breaks network compatibility by design,
 * because {@code SyncedWorldObjectComponent} compares schemas on delta reads.
 *
 * <p>Copy semantics: immutable values return as-is; {@link #listOf} and
 * {@link #compoundOf} deep-copy through their element codecs so snapshots never
 * alias live state.
 */
public final class FieldCodecs {

    private FieldCodecs() {
    }

    /**
     * Boolean codec.
     */
    public static FieldCodec<Boolean> bool() {
        return BoolCodec.INSTANCE;
    }

    /**
     * Byte codec.
     */
    public static FieldCodec<Byte> byteValue() {
        return ByteCodec.INSTANCE;
    }

    /**
     * Int codec (NBT int, network var-int).
     */
    public static FieldCodec<Integer> intValue() {
        return IntCodec.INSTANCE;
    }

    /**
     * Long codec (NBT long, network var-long).
     */
    public static FieldCodec<Long> longValue() {
        return LongCodec.INSTANCE;
    }

    /**
     * Float codec.
     */
    public static FieldCodec<Float> floatValue() {
        return FloatCodec.INSTANCE;
    }

    /**
     * Double codec.
     */
    public static FieldCodec<Double> doubleValue() {
        return DoubleCodec.INSTANCE;
    }

    /**
     * UTF-8 string codec (network length capped at 32767, vanilla limit).
     */
    public static FieldCodec<String> string() {
        return StringCodec.INSTANCE;
    }

    /**
     * UUID codec (two longs on the wire, int-array in NBT).
     */
    public static FieldCodec<UUID> uuid() {
        return UuidCodec.INSTANCE;
    }

    /**
     * Raw {@link CompoundTag} codec. Values are deep-copied on every snapshot.
     */
    public static FieldCodec<CompoundTag> compound() {
        return CompoundCodec.INSTANCE;
    }

    /**
     * Optional wrapper: presence flag plus the nested value.
     * A {@code null} value inside {@link TrackedField} means "absent".
     */
    public static <Value> FieldCodec<Optional<Value>> optionalOf(FieldCodec<Value> element) {
        Objects.requireNonNull(element, "element");
        return FieldCodec.<Optional<Value>>builder()
                .schema("optional<" + element.schemaName() + ">")
                .nbt((nbt, key, value) -> {
                    if (value != null && value.isPresent()) {
                        nbt.put(key, element.toNbtElement(value.get()));
                    }
                }, (nbt, key, defaultValue) -> {
                    Tag child = nbt.get(key);
                    if (child == null) {
                        return defaultValue;
                    }
                    return Optional.ofNullable(element.fromNbtElement(child, null));
                })
                .nbtElement(value -> {
                    CompoundTag wrapper = new CompoundTag();
                    if (value != null && value.isPresent()) {
                        wrapper.put("v", element.toNbtElement(value.get()));
                    }
                    return wrapper;
                }, (tag, defaultValue) -> {
                    if (!(tag instanceof CompoundTag wrapper)) {
                        return defaultValue;
                    }
                    Tag child = wrapper.get("v");
                    if (child == null) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(element.fromNbtElement(child, null));
                })
                .network((buf, value) -> {
                    boolean present = value != null && value.isPresent();
                    buf.writeBoolean(present);
                    if (present) {
                        element.toNetwork(buf, value.get());
                    }
                }, buf -> buf.readBoolean() ? Optional.of(element.fromNetwork(buf)) : Optional.empty())
                .copy(value -> {
                    if (value == null || value.isEmpty()) {
                        return value;
                    }
                    return Optional.of(element.copy(value.get()));
                })
                .equality((first, second) -> {
                    Object a = first == null ? null : first.orElse(null);
                    Object b = second == null ? null : second.orElse(null);
                    return element.areEqual(
                            uncheckedCast(a, element), uncheckedCast(b, element));
                })
                .build();
    }

    /**
     * List codec: defensive copy on snapshot, order-preserving, element-wise equality.
     */
    public static <Value> FieldCodec<List<Value>> listOf(FieldCodec<Value> element) {
        Objects.requireNonNull(element, "element");
        return FieldCodec.<List<Value>>builder()
                .schema("list<" + element.schemaName() + ">")
                .nbt((nbt, key, value) -> {
                    ListTag list = new ListTag();
                    if (value != null) {
                        for (Value item : value) {
                            list.add(element.toNbtElement(item));
                        }
                    }
                    nbt.put(key, list);
                }, (nbt, key, defaultValue) -> {
                    if (!(nbt.get(key) instanceof ListTag list)) {
                        return defaultValue;
                    }
                    List<Value> result = new ArrayList<>(list.size());
                    for (Tag child : list) {
                        result.add(element.fromNbtElement(child, null));
                    }
                    return result;
                })
                .nbtElement(value -> {
                    ListTag list = new ListTag();
                    if (value != null) {
                        for (Value item : value) {
                            list.add(element.toNbtElement(item));
                        }
                    }
                    return list;
                }, (tag, defaultValue) -> {
                    if (!(tag instanceof ListTag list)) {
                        return defaultValue;
                    }
                    List<Value> result = new ArrayList<>(list.size());
                    for (Tag child : list) {
                        result.add(element.fromNbtElement(child, null));
                    }
                    return result;
                })
                .network((buf, value) -> {
                    List<Value> safe = value == null ? List.of() : value;
                    buf.writeVarInt(safe.size());
                    for (Value item : safe) {
                        element.toNetwork(buf, item);
                    }
                }, buf -> {
                    int size = buf.readVarInt();
                    if (size < 0 || size > 65536) {
                        throw new SyncException("List size out of bounds: " + size);
                    }
                    List<Value> result = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        result.add(element.fromNetwork(buf));
                    }
                    return result;
                })
                .copy(value -> {
                    if (value == null) {
                        return null;
                    }
                    List<Value> copy = new ArrayList<>(value.size());
                    for (Value item : value) {
                        copy.add(element.copy(item));
                    }
                    return copy;
                })
                .equality((first, second) -> {
                    if (first == second) {
                        return true;
                    }
                    if (first == null || second == null || first.size() != second.size()) {
                        return false;
                    }
                    for (int i = 0; i < first.size(); i++) {
                        if (!element.areEqual(first.get(i), second.get(i))) {
                            return false;
                        }
                    }
                    return true;
                })
                .build();
    }

    /**
     * Enum codec by stable {@link Enum#name}. Unknown names on read fall back to
     * {@code defaultValue} instead of throwing, to tolerate content removals.
     */
    public static <E extends Enum<E>> FieldCodec<E> enumCodec(Class<E> enumClass) {
        Objects.requireNonNull(enumClass, "enumClass");
        E[] constants = enumClass.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new IllegalArgumentException(enumClass.getName() + " is not an enum");
        }
        return FieldCodec.<E>builder()
                .schema("enum:" + enumClass.getName())
                .nbt((nbt, key, value) -> {
                    if (value != null) {
                        nbt.putString(key, value.name());
                    }
                }, (nbt, key, defaultValue) -> {
                    String name = nbt.getString(key);
                    if (name == null || name.isEmpty()) {
                        return defaultValue;
                    }
                    try {
                        return Enum.valueOf(enumClass, name);
                    } catch (IllegalArgumentException unknown) {
                        return defaultValue;
                    }
                })
                .nbtElement(value -> StringTag.valueOf(value == null ? "" : value.name()),
                        (tag, defaultValue) -> {
                            if (!(tag instanceof StringTag named) || named.getAsString().isEmpty()) {
                                return defaultValue;
                            }
                            try {
                                return Enum.valueOf(enumClass, named.getAsString());
                            } catch (IllegalArgumentException unknown) {
                                return defaultValue;
                            }
                        })
                .network((buf, value) -> buf.writeUtf(value == null ? "" : value.name(), 128),
                        buf -> {
                            String name = buf.readUtf(128);
                            if (name.isEmpty()) {
                                return null;
                            }
                            try {
                                return Enum.valueOf(enumClass, name);
                            } catch (IllegalArgumentException unknown) {
                                throw new SyncException("Unknown " + enumClass.getSimpleName() + ": " + name);
                            }
                        })
                .build();
    }

    /**
     * Nested compound codec backed by {@link CompoundFieldWriter}/{@link CompoundFieldReader}
     * lambdas. The whole compound is one {@link TrackedField}, therefore the writer must
     * emit a fully consistent snapshot of its inputs.
     */
    public static <Value> FieldCodec<Value> compoundOf(
            String schemaName,
            CompoundFieldWriter<Value> writer,
            CompoundFieldReader<Value> reader,
            FieldCodec.ValueCopier<Value> copier,
            FieldCodec.ValueEquality<Value> equality) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(reader, "reader");
        return FieldCodec.<Value>builder()
                .schema(schemaName == null ? "compound" : schemaName)
                .nbt((nbt, key, value) -> {
                    CompoundTag child = new CompoundTag();
                    if (value != null) {
                        writer.write(child, value);
                    }
                    nbt.put(key, child);
                }, (nbt, key, defaultValue) -> {
                    if (!(nbt.get(key) instanceof CompoundTag child)) {
                        return defaultValue;
                    }
                    return reader.read(child, defaultValue);
                })
                .nbtElement(value -> {
                    CompoundTag child = new CompoundTag();
                    if (value != null) {
                        writer.write(child, value);
                    }
                    return child;
                }, (tag, defaultValue) -> tag instanceof CompoundTag child
                        ? reader.read(child, defaultValue)
                        : defaultValue)
                .network((buf, value) -> buf.writeNbt(compoundWriterToNbt(value, writer)),
                        buf -> reader.read(buf.readNbt(), null))
                .copy(copier == null ? value -> value : copier)
                .equality(equality == null ? Objects::equals : equality)
                .build();
    }

    /**
     * Writes fields of a nested object into a compound.
     */
    @FunctionalInterface
    public interface CompoundFieldWriter<Value> {
        void write(CompoundTag nbt, Value value);
    }

    /**
     * Reads a nested object from a compound; {@code defaultValue} is the field default.
     */
    @FunctionalInterface
    public interface CompoundFieldReader<Value> {
        Value read(CompoundTag nbt, Value defaultValue);
    }

    @SuppressWarnings("unchecked")
    private static <Value> Value uncheckedCast(Object value, FieldCodec<Value> codec) {
        return (Value) value;
    }

    private static <Value> CompoundTag compoundWriterToNbt(Value value, CompoundFieldWriter<Value> writer) {
        CompoundTag child = new CompoundTag();
        if (value != null) {
            writer.write(child, value);
        }
        return child;
    }

    private enum BoolCodec implements FieldCodec<Boolean> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, Boolean value) {
            nbt.putBoolean(key, value != null && value);
        }

        @Override
        public Boolean fromNbt(CompoundTag nbt, String key, Boolean defaultValue) {
            return nbt.contains(key) ? nbt.getBoolean(key) : defaultValue;
        }

        @Override
        public Tag toNbtElement(Boolean value) {
            return ByteTag.valueOf((byte) (Boolean.TRUE.equals(value) ? 1 : 0));
        }

        @Override
        public Boolean fromNbtElement(Tag element, Boolean defaultValue) {
            if (element instanceof ByteTag numeric) {
                return numeric.getAsByte() != 0;
            }
            return defaultValue;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, Boolean value) {
            buf.writeBoolean(Boolean.TRUE.equals(value));
        }

        @Override
        public Boolean fromNetwork(FriendlyByteBuf buf) {
            return buf.readBoolean();
        }

        @Override
        public String schemaName() {
            return "bool";
        }
    }

    private enum ByteCodec implements FieldCodec<Byte> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, Byte value) {
            nbt.putByte(key, value == null ? 0 : value);
        }

        @Override
        public Byte fromNbt(CompoundTag nbt, String key, Byte defaultValue) {
            return nbt.contains(key) ? nbt.getByte(key) : defaultValue;
        }

        @Override
        public Tag toNbtElement(Byte value) {
            return ByteTag.valueOf(value == null ? 0 : value);
        }

        @Override
        public Byte fromNbtElement(Tag element, Byte defaultValue) {
            if (element instanceof ByteTag numeric) {
                return numeric.getAsByte();
            }
            return defaultValue;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, Byte value) {
            buf.writeByte(value == null ? 0 : value);
        }

        @Override
        public Byte fromNetwork(FriendlyByteBuf buf) {
            return buf.readByte();
        }

        @Override
        public String schemaName() {
            return "byte";
        }
    }

    private enum IntCodec implements FieldCodec<Integer> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, Integer value) {
            nbt.putInt(key, value == null ? 0 : value);
        }

        @Override
        public Integer fromNbt(CompoundTag nbt, String key, Integer defaultValue) {
            return nbt.contains(key) ? nbt.getInt(key) : defaultValue;
        }

        @Override
        public Tag toNbtElement(Integer value) {
            return IntTag.valueOf(value == null ? 0 : value);
        }

        @Override
        public Integer fromNbtElement(Tag element, Integer defaultValue) {
            if (element instanceof IntTag numeric) {
                return numeric.getAsInt();
            }
            return defaultValue;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, Integer value) {
            buf.writeVarInt(value == null ? 0 : value);
        }

        @Override
        public Integer fromNetwork(FriendlyByteBuf buf) {
            return buf.readVarInt();
        }

        @Override
        public String schemaName() {
            return "int";
        }
    }

    private enum LongCodec implements FieldCodec<Long> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, Long value) {
            nbt.putLong(key, value == null ? 0L : value);
        }

        @Override
        public Long fromNbt(CompoundTag nbt, String key, Long defaultValue) {
            return nbt.contains(key) ? nbt.getLong(key) : defaultValue;
        }

        @Override
        public Tag toNbtElement(Long value) {
            return LongTag.valueOf(value == null ? 0L : value);
        }

        @Override
        public Long fromNbtElement(Tag element, Long defaultValue) {
            if (element instanceof LongTag numeric) {
                return numeric.getAsLong();
            }
            return defaultValue;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, Long value) {
            buf.writeVarLong(value == null ? 0L : value);
        }

        @Override
        public Long fromNetwork(FriendlyByteBuf buf) {
            return buf.readVarLong();
        }

        @Override
        public String schemaName() {
            return "long";
        }
    }

    private enum FloatCodec implements FieldCodec<Float> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, Float value) {
            nbt.putFloat(key, value == null ? 0F : value);
        }

        @Override
        public Float fromNbt(CompoundTag nbt, String key, Float defaultValue) {
            return nbt.contains(key) ? nbt.getFloat(key) : defaultValue;
        }

        @Override
        public Tag toNbtElement(Float value) {
            return FloatTag.valueOf(value == null ? 0F : value);
        }

        @Override
        public Float fromNbtElement(Tag element, Float defaultValue) {
            if (element instanceof FloatTag numeric) {
                return numeric.getAsFloat();
            }
            return defaultValue;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, Float value) {
            buf.writeFloat(value == null ? 0F : value);
        }

        @Override
        public Float fromNetwork(FriendlyByteBuf buf) {
            return buf.readFloat();
        }

        @Override
        public String schemaName() {
            return "float";
        }
    }

    private enum DoubleCodec implements FieldCodec<Double> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, Double value) {
            nbt.putDouble(key, value == null ? 0D : value);
        }

        @Override
        public Double fromNbt(CompoundTag nbt, String key, Double defaultValue) {
            return nbt.contains(key) ? nbt.getDouble(key) : defaultValue;
        }

        @Override
        public Tag toNbtElement(Double value) {
            return DoubleTag.valueOf(value == null ? 0D : value);
        }

        @Override
        public Double fromNbtElement(Tag element, Double defaultValue) {
            if (element instanceof DoubleTag numeric) {
                return numeric.getAsDouble();
            }
            return defaultValue;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, Double value) {
            buf.writeDouble(value == null ? 0D : value);
        }

        @Override
        public Double fromNetwork(FriendlyByteBuf buf) {
            return buf.readDouble();
        }

        @Override
        public String schemaName() {
            return "double";
        }
    }

    private enum StringCodec implements FieldCodec<String> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, String value) {
            nbt.putString(key, value == null ? "" : value);
        }

        @Override
        public String fromNbt(CompoundTag nbt, String key, String defaultValue) {
            return nbt.contains(key) ? nbt.getString(key) : defaultValue;
        }

        @Override
        public Tag toNbtElement(String value) {
            return StringTag.valueOf(value == null ? "" : value);
        }

        @Override
        public String fromNbtElement(Tag element, String defaultValue) {
            if (element instanceof StringTag text) {
                return text.getAsString();
            }
            return defaultValue;
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, String value) {
            buf.writeUtf(value == null ? "" : value);
        }

        @Override
        public String fromNetwork(FriendlyByteBuf buf) {
            return buf.readUtf();
        }

        @Override
        public String schemaName() {
            return "string";
        }
    }

    private enum UuidCodec implements FieldCodec<UUID> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, UUID value) {
            if (value != null) {
                nbt.putUUID(key, value);
            }
        }

        @Override
        public UUID fromNbt(CompoundTag nbt, String key, UUID defaultValue) {
            if (!nbt.hasUUID(key)) {
                return defaultValue;
            }
            return nbt.getUUID(key);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, UUID value) {
            buf.writeUUID(value == null ? new UUID(0L, 0L) : value);
        }

        @Override
        public UUID fromNetwork(FriendlyByteBuf buf) {
            return buf.readUUID();
        }

        @Override
        public String schemaName() {
            return "uuid";
        }
    }

    private enum CompoundCodec implements FieldCodec<CompoundTag> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, CompoundTag value) {
            nbt.put(key, value == null ? new CompoundTag() : value.copy());
        }

        @Override
        public CompoundTag fromNbt(CompoundTag nbt, String key, CompoundTag defaultValue) {
            if (nbt.get(key) instanceof CompoundTag child) {
                return child.copy();
            }
            return defaultValue == null ? null : defaultValue.copy();
        }

        @Override
        public Tag toNbtElement(CompoundTag value) {
            return value == null ? new CompoundTag() : value.copy();
        }

        @Override
        public CompoundTag fromNbtElement(Tag element, CompoundTag defaultValue) {
            if (element instanceof CompoundTag child) {
                return child.copy();
            }
            return defaultValue == null ? null : defaultValue.copy();
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, CompoundTag value) {
            buf.writeNbt(value == null ? new CompoundTag() : value);
        }

        @Override
        public CompoundTag fromNetwork(FriendlyByteBuf buf) {
            CompoundTag read = buf.readNbt();
            return read == null ? new CompoundTag() : read;
        }

        @Override
        public CompoundTag copy(CompoundTag value) {
            return value == null ? null : value.copy();
        }

        @Override
        public String schemaName() {
            return "compound";
        }
    }
}
