package dev.sixik.artisans_delight.libs.world.sync;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A single named value with codec-based copy and equality. No locking, no revision.
 *
 * <p>Базовое поле для однопоточных компонентов ({@code SingleThreadedWorldObjectComponent}):
 * прямой доступ к значению, ноль накладных расходов на синхронизацию. Равенство через
 * {@link FieldCodec#areEqual}, defensive copy через {@link FieldCodec#copy} — та же
 * семантика снапшотов, что и у {@link TrackedField}, но без ревизий и дельт.
 *
 * <p>Значения должны быть эффективно иммутабельными для читателей: {@link #get}
 * возвращает живую ссылку без копирования. Для мутабельных типов используйте
 * {@link #update} с copy-on-write функцией.
 *
 * @param <Value> field value type
 */
public class Field<Value> {

    private final String name;
    private final FieldCodec<Value> codec;

    private Value value;

    /**
     * Creates a field with the given default. The default is copied once through
     * {@link FieldCodec#copy} so later external mutation of the argument cannot
     * leak into the field.
     */
    public Field(String name, FieldCodec<Value> codec, Value defaultValue) {
        this.name = Objects.requireNonNull(name, "name");
        this.codec = Objects.requireNonNull(codec, "codec");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.value = codec.copy(defaultValue);
    }

    /**
     * Field name, stable across restarts. Used as the NBT key.
     */
    public final String name() {
        return name;
    }

    /**
     * Codec used for NBT, network and equality.
     */
    public final FieldCodec<Value> codec() {
        return codec;
    }

    /**
     * Returns the live value (no copy, no lock).
     */
    public Value get() {
        return value;
    }

    /**
     * Installs {@code newValue}. Returns {@code true} when the value changed
     * (by {@link FieldCodec#areEqual}).
     */
    public boolean set(Value newValue) {
        if (codec.areEqual(value, newValue)) {
            return false;
        }
        value = codec.copy(newValue);
        return true;
    }

    /**
     * Atomically (for the calling thread) replaces the value with
     * {@code remap.apply(live-copy)}. Returns {@code true} on change.
     */
    public boolean update(UnaryOperator<Value> remap) {
        Objects.requireNonNull(remap, "remap");
        Value next = remap.apply(codec.copy(value));
        if (codec.areEqual(value, next)) {
            return false;
        }
        value = codec.copy(next);
        return true;
    }

    /**
     * Compares the live value against {@code snapshot} with codec equality.
     */
    public boolean isEqualTo(Value snapshot) {
        return codec.areEqual(value, snapshot);
    }
}
