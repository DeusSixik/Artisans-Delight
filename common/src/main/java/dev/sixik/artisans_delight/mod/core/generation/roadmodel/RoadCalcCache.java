package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш дорогих детерминированных расчётов дорожной сети на время сессии мира.
 *
 * <p>Пересборка сети (появилась новая деревня) раньше считала всё заново:
 * поле узлов, проверку воды и, главное, A* уже построенных дорог — дороги
 * пропадали и досчитывались повторно. Все эти расчёты — чистые функции
 * от (сид, ячейка / концы ребра, рельеф), поэтому их результат можно
 * переиспользовать. Тогда пересборка меняет только окрестность новой деревни.
 *
 * <p>Кэш привязан к сиду мира: при смене сида очищается
 * ({@link #ensureSeed(long)}). Потокобезопасен: {@link ConcurrentHashMap},
 * при гонке два потока посчитают одно и то же — результат одинаков.
 */
public final class RoadCalcCache {

    /** Допуск сдвига конца ребра (блоки), при котором кэш дороги ещё годен. */
    private static final double GEOMETRY_ENDPOINT_TOLERANCE = 24.0;

    private static final Object SEED_LOCK = new Object();
    private static volatile long seed = Long.MIN_VALUE;

    /** Сырой кандидат ячейки поля: {@code {jx, jz, sx, sy, sz, water}} (water: 1 — вода). */
    private static final ConcurrentHashMap<Long, int[]> FIELD = new ConcurrentHashMap<>();
    /** Пересекает ли хорда море, по каноническим координатам концов. */
    private static final ConcurrentHashMap<String, Boolean> SEA = new ConcurrentHashMap<>();
    /** Результат A* по ключу ребра. */
    private static final ConcurrentHashMap<String, GeoEntry> GEOMETRY = new ConcurrentHashMap<>();

    /**
     * Запись кэша дороги.
     *
     * @param fromId id узла, координаты которого записаны в {@code ax, az}
     * @param edge   готовое ребро или {@code null}, если сухой дороги нет
     */
    private record GeoEntry(UUID fromId, int ax, int az, int bx, int bz, RoadEdge edge) {
    }

    /** Итог поиска дороги в кэше. */
    public enum Hit {
        /** Нет записи (или концы сдвинулись) — нужен A*. */
        MISS,
        /** Дорога есть — {@link #cachedEdge} вернёт её. */
        READY,
        /** Известно, что сухой дороги нет. */
        FAILED
    }

    private RoadCalcCache() {
    }

    /** Очищает кэш, если сид мира сменился. */
    public static void ensureSeed(long worldSeed) {
        if (seed == worldSeed) {
            return;
        }
        synchronized (SEED_LOCK) {
            if (seed != worldSeed) {
                FIELD.clear();
                SEA.clear();
                GEOMETRY.clear();
                seed = worldSeed;
            }
        }
    }

    // ----- поле -----

    static int[] field(int gx, int gz) {
        return FIELD.get(cellKey(gx, gz));
    }

    static void putField(int gx, int gz, int[] raw) {
        FIELD.put(cellKey(gx, gz), raw);
    }

    private static long cellKey(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
    }

    // ----- вода на хорде -----

    static Boolean sea(RoadNode a, RoadNode b) {
        return SEA.get(chordKey(a, b));
    }

    static void putSea(RoadNode a, RoadNode b, boolean crosses) {
        SEA.put(chordKey(a, b), crosses);
    }

    private static String chordKey(RoadNode a, RoadNode b) {
        boolean swap = a.x() > b.x() || (a.x() == b.x() && a.z() > b.z());
        RoadNode p = swap ? b : a;
        RoadNode q = swap ? a : b;
        return p.x() + "," + p.z() + ";" + q.x() + "," + q.z();
    }

    // ----- геометрия -----

    /**
     * Ищет посчитанную дорогу ребра. Запись годна, если оба конца сдвинулись
     * не дальше {@link #GEOMETRY_ENDPOINT_TOLERANCE} блоков.
     */
    public static Hit lookupGeometry(String edgeKey, RoadNode a, RoadNode b) {
        GeoEntry e = GEOMETRY.get(edgeKey);
        if (e == null || !endpointsMatch(e, a, b)) {
            return Hit.MISS;
        }
        return e.edge() == null ? Hit.FAILED : Hit.READY;
    }

    /** Посчитанное ребро (после {@link Hit#READY}) или {@code null}. */
    public static RoadEdge cachedEdge(String edgeKey) {
        GeoEntry e = GEOMETRY.get(edgeKey);
        return e == null ? null : e.edge();
    }

    /** Запоминает результат A* ({@code edge == null} — дороги нет). */
    public static void putGeometry(String edgeKey, RoadNode a, RoadNode b, RoadEdge edge) {
        GEOMETRY.put(edgeKey, new GeoEntry(a.id(), a.x(), a.z(), b.x(), b.z(), edge));
    }

    private static boolean endpointsMatch(GeoEntry e, RoadNode a, RoadNode b) {
        RoadNode first = e.fromId().equals(a.id()) ? a : b;
        RoadNode second = first == a ? b : a;
        double tol2 = GEOMETRY_ENDPOINT_TOLERANCE * GEOMETRY_ENDPOINT_TOLERANCE;
        return d2(e.ax(), e.az(), first) <= tol2 && d2(e.bx(), e.bz(), second) <= tol2;
    }

    private static double d2(int x, int z, RoadNode n) {
        double dx = (double) x - n.x();
        double dz = (double) z - n.z();
        return dx * dx + dz * dz;
    }

    /** Размеры кэша для логов. */
    public static String stats() {
        return "поле " + FIELD.size() + ", вода " + SEA.size() + ", дороги " + GEOMETRY.size();
    }
}
