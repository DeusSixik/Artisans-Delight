package dev.sixik.artisans_delight.libs.world;

import dev.sixik.artisans_delight.libs.world.sync.Field;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodec;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;

/**
 * Base with declarative {@link Field}s and automatic NBT persistence.
 *
 * <p>Поля объявляются в конструкторе через {@link #field} / {@link #tracked} и после
 * этого сохраняются/загружаются сами: {@link #writeNbt}/{@link #readNbt} обходят
 * реестр по именам, отсутствующие ключи оставляют текущие значения. Ручной NBT нужен
 * только для данных вне полей — через {@link #writeExtraNbt}/{@link #readExtraNbt}.
 *
 * <p>Порядок объявления полей стабилен и доступен через {@link #syncedFields};
 * наследники используют его как основу сетевой схемы (см.
 * {@code SyncedWorldObjectComponent}).
 */
public abstract class FieldedWorldObjectComponent extends AbstractWorldObjectComponent {

    private final List<Field<?>> fields = new ArrayList<>();
    private final Map<String, Field<?>> byName = new LinkedHashMap<>();

    /**
     * Declares a plain field (no locking, no revision). For single-threaded
     * components. Must be called from the constructor, before attach.
     * Duplicate names are rejected.
     */
    protected final <Value> Field<Value> field(String name, FieldCodec<Value> codec, Value defaultValue) {
        return declare(new Field<>(name, codec, defaultValue));
    }

    /**
     * Declares a tracked field (lock-striped, monotonic revision). For synced
     * multithreaded components. Must be called from the constructor, before attach.
     * Duplicate names are rejected.
     */
    protected final <Value> TrackedField<Value> tracked(String name, FieldCodec<Value> codec, Value defaultValue) {
        return declare(new TrackedField<>(name, codec, defaultValue));
    }

    private <F extends Field<?>> F declare(F field) {
        if (byName.putIfAbsent(field.name(), field) != null) {
            throw new IllegalArgumentException("Duplicate field '" + field.name() + "' in " + getClass().getName());
        }
        fields.add(field);
        return field;
    }

    /**
     * Declared fields in declaration order. The returned list is a copy.
     */
    public final List<Field<?>> syncedFields() {
        return List.copyOf(fields);
    }

    /**
     * Looks up a field by name.
     */
    public final Field<?> fieldByName(String name) {
        Field<?> tracked = byName.get(name);
        if (tracked == null) {
            throw new IllegalArgumentException("Unknown field '" + name + "' in " + getClass().getName());
        }
        return tracked;
    }

    /**
     * Writes all fields into {@code nbt} under their names, then {@link #writeExtraNbt}.
     */
    public void writeNbt(CompoundTag nbt) {
        Objects.requireNonNull(nbt, "nbt");
        for (Field<?> tracked : fields) {
            writeNbtValue(nbt, tracked);
        }
        writeExtraNbt(nbt);
    }

    /**
     * Reads fields from {@code nbt} by name, then {@link #readExtraNbt}. Missing keys
     * keep current values, which makes saves forward-compatible with newly added fields.
     */
    public void readNbt(CompoundTag nbt) {
        Objects.requireNonNull(nbt, "nbt");
        for (Field<?> tracked : fields) {
            readNbtValue(nbt, tracked);
        }
        readExtraNbt(nbt);
    }

    /**
     * Hook for state kept outside {@link Field}s. Called after fields
     * in {@link #writeNbt}. Default: nothing.
     */
    protected void writeExtraNbt(CompoundTag nbt) {
    }

    /**
     * Hook for state kept outside {@link Field}s. Called after fields
     * in {@link #readNbt}. Default: nothing.
     */
    protected void readExtraNbt(CompoundTag nbt) {
    }

    private static void writeNbtValue(CompoundTag nbt, Field<?> tracked) {
        writeNbtValueCaptured(nbt, tracked);
    }

    private static <Value> void writeNbtValueCaptured(CompoundTag nbt, Field<Value> tracked) {
        tracked.codec().toNbt(nbt, tracked.name(), tracked.get());
    }

    private static void readNbtValue(CompoundTag nbt, Field<?> tracked) {
        readNbtValueCaptured(nbt, tracked);
    }

    private static <Value> void readNbtValueCaptured(CompoundTag nbt, Field<Value> tracked) {
        tracked.set(tracked.codec().fromNbt(nbt, tracked.name(), tracked.get()));
    }
}
