package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Кэш дорогих детерминированных расчётов дорожной сети.
 *
 * <p>Пересборка сети (появилась новая деревня) раньше считала всё заново:
 * поле узлов, проверку воды и, главное, A* уже построенных дорог — дороги
 * пропадали и досчитывались повторно. Все эти расчёты — чистые функции
 * от (сид, ячейка / концы ребра, рельеф), поэтому их результат можно
 * переиспользовать. Тогда пересборка меняет только окрестность новой деревни.
 *
 * <p>Кэш привязан к области (сид мира + измерение), при её смене очищается
 * вместе с {@link RoadCommitStore} ({@link #ensureScope(long, String)}).
 * Содержимое сохраняется на диск ({@code RoadPersistence}) и переживает
 * перезапуск мира.
 *
 * <p>Потокобезопасен: {@link ConcurrentHashMap}; при гонке два потока
 * посчитают одно и то же — результат одинаков.
 */
public final class RoadCalcCache {

    /** Допуск сдвига конца ребра (блоки), при котором кэш дороги ещё годен. */
    private static final double GEOMETRY_ENDPOINT_TOLERANCE = 24.0;

    private static final Object SCOPE_LOCK = new Object();
    private static volatile String scope = null;

    /** Сырой кандидат ячейки поля: {@code {jx, jz, sx, sy, sz, water}} (water: 1 — вода). */
    private static final ConcurrentHashMap<Long, int[]> FIELD = new ConcurrentHashMap<>();
    /** Пересекает ли хорда море, по каноническим координатам концов. */
    private static final ConcurrentHashMap<String, Boolean> SEA = new ConcurrentHashMap<>();
    /** Результат A* по ключу ребра. */
    private static final ConcurrentHashMap<String, GeoEntry> GEOMETRY = new ConcurrentHashMap<>();
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);

    /**
     * Запись кэша дороги.
     *
     * @param fromId id узла, координаты которого записаны в {@code ax, az}
     * @param edge   готовое ребро или {@code null}, если сухой дороги нет
     */
    public record GeoEntry(UUID fromId, int ax, int az, int bx, int bz, RoadEdge edge) {
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

    /**
     * Ключ области: сид + измерение.
     *
     * @param worldSeed сид мира
     * @param dimension id измерения (например {@code minecraft:overworld})
     */
    public static String scopeKey(long worldSeed, String dimension) {
        return worldSeed + "@" + dimension;
    }

    /**
     * Переключает область. При смене очищает кэш и закреплённые рёбра.
     *
     * @return {@code true}, если область сменилась (кэш пуст — пора загрузить с диска)
     */
    public static boolean ensureScope(long worldSeed, String dimension) {
        String key = scopeKey(worldSeed, dimension);
        if (key.equals(scope)) {
            return false;
        }
        synchronized (SCOPE_LOCK) {
            if (key.equals(scope)) {
                return false;
            }
            FIELD.clear();
            SEA.clear();
            GEOMETRY.clear();
            RoadCommitStore.clear();
            RoadPavingLedger.clear();
            DIRTY.set(false);
            scope = key;
            return true;
        }
    }

    /** Текущая область или {@code null}. */
    public static String scope() {
        return scope;
    }

    // ----- поле -----

    static int[] field(int gx, int gz) {
        return FIELD.get(cellKey(gx, gz));
    }

    static void putField(int gx, int gz, int[] raw) {
        FIELD.put(cellKey(gx, gz), raw);
        DIRTY.set(true);
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
        DIRTY.set(true);
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
        DIRTY.set(true);
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

    // ----- сохранение -----

    /** Были ли изменения (кэш или закрепления) с прошлого сохранения; сбрасывает флаги. */
    public static boolean takeDirty() {
        boolean cache = DIRTY.getAndSet(false);
        boolean commits = RoadCommitStore.takeDirty();
        boolean ledger = RoadPavingLedger.takeDirty();
        boolean obstacles = dev.sixik.artisans_delight.mod.core.generation.path.RoadObstacleStore.takeDirty();
        return cache || commits || ledger || obstacles;
    }

    /** Вернуть флаг изменений (сохранение не удалось). */
    public static void markDirty() {
        DIRTY.set(true);
    }

    /** Живой вид поля для сохранения: ключ ячейки → сырой кандидат. */
    public static Map<Long, int[]> fieldView() {
        return FIELD;
    }

    /** Живой вид проверок воды для сохранения. */
    public static Map<String, Boolean> seaView() {
        return SEA;
    }

    /** Живой вид геометрии для сохранения. */
    public static Map<String, GeoEntry> geometryView() {
        return GEOMETRY;
    }

    /**
     * Загрузка с диска в текущую область. Флаг изменений не ставится.
     *
     * @param scopeKey область, для которой записан файл — должна совпадать с текущей
     */
    public static void restore(String scopeKey,
                               Map<Long, int[]> field,
                               Map<String, Boolean> sea,
                               Map<String, GeoEntry> geometry,
                               java.util.Collection<RoadCommitStore.Committed> committed) {
        if (!Objects.equals(scopeKey, scope)) {
            return;
        }
        FIELD.putAll(field);
        SEA.putAll(sea);
        GEOMETRY.putAll(geometry);
        RoadCommitStore.restore(committed);
    }

    /** Размеры кэша для логов. */
    public static String stats() {
        return "поле " + FIELD.size() + ", вода " + SEA.size() + ", дороги " + GEOMETRY.size()
            + ", закреплено " + RoadCommitStore.size() + ", уложено (чанк×ребро) " + RoadPavingLedger.size();
    }
}
