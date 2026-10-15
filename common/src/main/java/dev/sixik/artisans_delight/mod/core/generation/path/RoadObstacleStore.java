package dev.sixik.artisans_delight.mod.core.generation.path;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Активные препятствия на дорогах по областям (сид мира + измерение).
 *
 * <p>Ключ области — {@code RoadCalcCache.scopeKey(seed, dim)}: препятствия
 * разных миров и измерений не смешиваются и не требуют очистки. Хранилище
 * сохраняется на диск вместе с кэшем дорог ({@link RoadPersistence}), поэтому
 * объезды переживают перезапуск: уложенные в мир объездные участки
 * не «забываются».
 *
 * <p>Потокобезопасно.
 */
public final class RoadObstacleStore {

    private static final Map<String, Map<ResourceLocation, RoadObstacle>> BY_SCOPE = new ConcurrentHashMap<>();
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);

    private RoadObstacleStore() {
    }

    /** Добавляет или заменяет препятствие с тем же id. */
    public static void put(String scope, RoadObstacle obstacle) {
        BY_SCOPE.computeIfAbsent(scope, k -> new ConcurrentHashMap<>()).put(obstacle.id(), obstacle);
        DIRTY.set(true);
    }

    /** Убирает препятствие; {@code true}, если оно было. */
    public static boolean remove(String scope, ResourceLocation id) {
        Map<ResourceLocation, RoadObstacle> m = BY_SCOPE.get(scope);
        if (m == null || m.remove(id) == null) {
            return false;
        }
        DIRTY.set(true);
        return true;
    }

    /** Препятствия области в детерминированном порядке (по id). */
    public static List<RoadObstacle> get(String scope) {
        Map<ResourceLocation, RoadObstacle> m = scope == null ? null : BY_SCOPE.get(scope);
        if (m == null || m.isEmpty()) {
            return List.of();
        }
        List<RoadObstacle> out = new ArrayList<>(m.values());
        out.sort(Comparator.comparing(o -> o.id().toString()));
        return out;
    }

    /** Загрузка с диска: заменяет препятствия области (без пометки «изменено»). */
    static void restore(String scope, Collection<RoadObstacle> obstacles) {
        Map<ResourceLocation, RoadObstacle> m = new ConcurrentHashMap<>();
        for (RoadObstacle o : obstacles) {
            m.put(o.id(), o);
        }
        BY_SCOPE.put(scope, m);
    }

    /** Сбрасывает флаг изменений; {@code true}, если были изменения. */
    public static boolean takeDirty() {
        return DIRTY.getAndSet(false);
    }
}
