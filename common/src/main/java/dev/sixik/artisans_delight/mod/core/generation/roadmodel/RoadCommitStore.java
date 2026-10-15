package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Закреплённые рёбра: дороги, уже уложенные в мир блоками.
 *
 * <p>Блоки в мире нельзя «пересчитать», поэтому ребро, по которому уложен
 * хоть один чанк, становится постоянным:
 * <ul>
 *   <li>сеть при любой пересборке обязана его содержать — с сохранённой
 *       геометрией, даже если новая топология его бы не выбрала;</li>
 *   <li>его концы восстанавливаются, даже если поле узлов их больше не даёт
 *       (например, перекрёсток подавлен новой деревней рядом);</li>
 *   <li>новые рёбра, пересекающие закреплённую дорогу, отбрасываются.</li>
 * </ul>
 *
 * <p>Потокобезопасно. Область действия (сид + измерение) и сброс —
 * через {@link RoadCalcCache#ensureScope(long, String)}.
 */
public final class RoadCommitStore {

    /**
     * Закреплённое ребро вместе с его концами на момент закрепления.
     *
     * @param a    узел {@code edge.fromId()}
     * @param b    узел {@code edge.toId()}
     * @param edge ребро с реальной геометрией (не ожидающее)
     */
    public record Committed(RoadNode a, RoadNode b, RoadEdge edge) {
    }

    private static final ConcurrentHashMap<String, Committed> COMMITTED = new ConcurrentHashMap<>();
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);

    private RoadCommitStore() {
    }

    /**
     * Закрепляет ребро. Повторное закрепление того же ключа игнорируется:
     * геометрия уложенной дороги не должна меняться.
     *
     * @return {@code true}, если ребро закреплено впервые
     */
    public static boolean commit(RoadEdge edge, RoadNode a, RoadNode b) {
        if (edge == null || edge.pending() || a == null || b == null) {
            return false;
        }
        RoadNode from = edge.fromId().equals(a.id()) ? a : b;
        RoadNode to = from == a ? b : a;
        if (COMMITTED.putIfAbsent(edge.key(), new Committed(from, to, edge)) == null) {
            DIRTY.set(true);
            return true;
        }
        return false;
    }

    public static boolean isCommitted(String edgeKey) {
        return COMMITTED.containsKey(edgeKey);
    }

    public static Committed get(String edgeKey) {
        return COMMITTED.get(edgeKey);
    }

    /** Снимок всех закреплённых рёбер. */
    public static List<Committed> all() {
        return new ArrayList<>(COMMITTED.values());
    }

    public static int size() {
        return COMMITTED.size();
    }

    /** Загрузка из файла (без пометки «изменено»). */
    static void restore(Collection<Committed> entries) {
        for (Committed c : entries) {
            COMMITTED.put(c.edge().key(), c);
        }
    }

    static void clear() {
        COMMITTED.clear();
        DIRTY.set(false);
    }

    /** Сбрасывает флаг изменений; {@code true}, если были изменения. */
    static boolean takeDirty() {
        return DIRTY.getAndSet(false);
    }

    static void markDirty() {
        DIRTY.set(true);
    }

    /** Есть ли несохранённые закрепления (без сброса флага). */
    public static boolean isDirty() {
        return DIRTY.get();
    }
}
