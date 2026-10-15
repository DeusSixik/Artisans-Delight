package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Учёт укладки: какие рёбра уже обработаны в каком чанке.
 *
 * <p>Запись «чанк C, ребро E» появляется, когда участок дороги E в чанке C
 * обработан — при генерации чанка или доукладкой в уже сгенерированный.
 * Даже если ни один блок не поменялся (дорога шла по камню), запись ставится:
 * чанк для этого ребра больше не проверяется.
 *
 * <p>Нужен доукладке: чанк, сгенерированный до того, как дорога была
 * посчитана, не имеет записи — его доуложат, когда он будет загружен.
 *
 * <p>Потокобезопасен (пишут потоки генерации и серверный поток).
 * Область и сброс — через {@link RoadCalcCache#ensureScope(long, String)}.
 */
public final class RoadPavingLedger {

    /** Ключ чанка → ключи рёбер, уже обработанных в нём. */
    private static final ConcurrentHashMap<Long, Set<String>> PAVED = new ConcurrentHashMap<>();
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);

    private RoadPavingLedger() {
    }

    /** Ключ чанка из его координат. */
    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Обработано ли ребро в чанке. */
    public static boolean isPaved(long chunkKey, String edgeKey) {
        Set<String> s = PAVED.get(chunkKey);
        return s != null && s.contains(edgeKey);
    }

    /** Отмечает ребро обработанным в чанке. */
    public static void markPaved(long chunkKey, String edgeKey) {
        if (PAVED.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(edgeKey)) {
            DIRTY.set(true);
        }
    }

    /** Число записей (для логов). */
    public static int size() {
        int n = 0;
        for (Set<String> s : PAVED.values()) {
            n += s.size();
        }
        return n;
    }

    /** Снимок для сохранения. */
    public static Map<Long, Set<String>> snapshot() {
        Map<Long, Set<String>> out = new HashMap<>(PAVED.size() * 2);
        for (Map.Entry<Long, Set<String>> e : PAVED.entrySet()) {
            out.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        return out;
    }

    /** Загрузка с диска (без пометки «изменено»). */
    public static void restore(Map<Long, Set<String>> data) {
        for (Map.Entry<Long, Set<String>> e : data.entrySet()) {
            PAVED.computeIfAbsent(e.getKey(), k -> ConcurrentHashMap.newKeySet()).addAll(e.getValue());
        }
    }

    static void clear() {
        PAVED.clear();
        DIRTY.set(false);
    }

    static boolean takeDirty() {
        return DIRTY.getAndSet(false);
    }
}
