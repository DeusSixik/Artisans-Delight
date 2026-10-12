package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Глобальное хранилище последней посчитанной виртуальной сети.
 *
 * <p>Потокобезопасно: писатель — фоновый поток генерации
 * ({@link dev.sixik.artisans_delight.mod.core.generation.path.ServerNetworkInitializer}),
 * читатель — render thread карты. Сеть публикуется целиком (volatile),
 * частичные состояния невозможны.
 *
 * <p>Ревизия ({@link #revision()}) монотонно растёт при каждой публикации —
 * карта по ней определяет, что нужно обновить маркеры.
 */
public final class RoadNetworkStore {

    private static final AtomicReference<RoadNetwork> CURRENT = new AtomicReference<>();
    private static final AtomicLong REVISION = new AtomicLong(0);

    private RoadNetworkStore() {
    }

    /** Опубликовать новую сеть (целиком заменяет предыдущую). */
    public static long publish(RoadNetwork network) {
        CURRENT.set(network);
        return REVISION.incrementAndGet();
    }

    /** Последняя опубликованная сеть или {@code null}, если расчёта ещё не было. */
    public static RoadNetwork current() {
        return CURRENT.get();
    }

    /** Ревизия последней публикации (0 — ничего не опубликовано). */
    public static long revision() {
        return REVISION.get();
    }

    /** Очистить хранилище (смена мира, выход). */
    public static void clear() {
        CURRENT.set(null);
        REVISION.incrementAndGet();
    }
}
