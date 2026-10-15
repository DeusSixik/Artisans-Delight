package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadCalcCache;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadEdge;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetwork;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetworkStore;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNode;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Локальные объезды препятствий поверх виртуальной сети.
 *
 * <p><b>Модель.</b> Препятствия ({@link RoadObstacleStore}) не меняют базовую
 * сеть: построитель, стример и закреплённые рёбра продолжают жить своей
 * жизнью. Движок — слой {@link RoadNetworkStore#overlay}: при каждой
 * публикации для рёбер, задетых препятствиями, подставляется объездная
 * геометрия. Снятие препятствия — просто пересчёт слоя: ребро возвращается
 * к базовой геометрии, ничего не надо «восстанавливать».
 *
 * <p><b>Объезд.</b> Пересчитывается не всё ребро, а участок: от последней
 * точки до препятствия (Pin) до первой точки после (Pout) — A* с запретом
 * зоны препятствия, затем вшивка дуги в исходную полилинию. 95% дороги
 * остаётся как была, уже уложенные блоки не теряют смысла.
 *
 * <p><b>Асинхронность.</b> A* объезда идёт в отдельном пуле. Слой не ждёт:
 * пока объезд считается, ребро публикуется с базовой геометрией, после
 * расчёта делается {@link RoadNetworkStore#republish()}. Результат кэшируется
 * по подписи (геометрия ребра + задевающие его препятствия) — при каждой
 * публикации стримера объезд не пересчитывается.
 *
 * <p><b>Укладка.</b> Объездная геометрия укладывается как обычная дорога
 * ({@link RoadPaver}), но под отдельным ключом учёта ({@link #pavingKey}),
 * а закрепляется базовое ребро ({@link #baselineOf}).
 */
public final class RouteDetourEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDetourEngine.class);

    /** Запас к радиусу препятствия: ребро ближе — задето. */
    private static final double HIT_MARGIN = 4.0;
    /** Pin/Pout выбираются не ближе этого к краю препятствия. */
    private static final double ANCHOR_MARGIN = 32.0;
    /** Ядро запрета для A* (к радиусу). */
    private static final double FORBID_MARGIN = 6.0;
    /** Буферная зона со штрафом (к радиусу). */
    private static final double BUFFER_MARGIN = 24.0;
    /** Штраф буферной зоны. */
    private static final double BUFFER_COST = 400.0;
    /** Штраф прохода через опасную (не блокирующую) зону. */
    private static final double HAZARD_COST = 2000.0;
    /** Минимальный шаг между точками вшитой полилинии (квадрат, блоки²). */
    private static final double SPLICE_MIN_GAP2 = 16.0;

    /**
     * Результат объезда для ребра.
     *
     * @param baseline базовое ребро, от которого считался объезд
     * @param result   ребро с объездом или {@code null} (объезд не нужен/не найден)
     * @param sig      подпись (геометрия базы + препятствия) — признак актуальности
     */
    private record Detour(RoadEdge baseline, RoadEdge result, String sig) {
    }

    private static final ConcurrentHashMap<String, Detour> DETOURS = new ConcurrentHashMap<>();
    /** Сейчас считаются: ключ ребра + подпись. */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ArtisansDelight-Detour");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static volatile ServerLevel boundLevel;

    static {
        RoadNetworkStore.overlay(RouteDetourEngine::overlay);
    }

    private RouteDetourEngine() {
    }

    /**
     * Привязывает движок к миру, чью сеть он обслуживает (генератор/рельеф для A*).
     * Вызывается перед расчётом сети и при регистрации препятствий.
     */
    public static void bind(ServerLevel level) {
        if (level != boundLevel) {
            boundLevel = level;
            DETOURS.clear();
        }
    }

    // =========================================================================
    // Слой публикации
    // =========================================================================

    /**
     * Накладывает объезды на сеть. Быстрый: только проверка пересечений
     * (bbox-префильтр) и подстановка готовых объездов; A* — в пуле.
     */
    private static RoadNetwork overlay(RoadNetwork base) {
        ServerLevel level = boundLevel;
        if (level == null) {
            return base;
        }
        String scope = RoadCalcCache.scopeKey(level.getSeed(), level.dimension().location().toString());
        List<RoadObstacle> obstacles = RoadObstacleStore.get(scope);
        if (obstacles.isEmpty()) {
            return base;
        }
        Map<String, RoadEdge> edges = new LinkedHashMap<>();
        boolean changed = false;
        for (RoadEdge e : base.edges()) {
            if (e.pending() || e.seaRoute()) {
                edges.put(e.key(), e);
                continue;
            }
            List<RoadObstacle> hits = new ArrayList<>(2);
            for (RoadObstacle o : obstacles) {
                if (intersects(e.geometry(), o)) {
                    hits.add(o);
                }
            }
            if (hits.isEmpty()) {
                edges.put(e.key(), e);
                continue;
            }
            String sig = signature(e, hits);
            Detour d = DETOURS.get(e.key());
            if (d != null && d.sig().equals(sig)) {
                if (d.result() != null) {
                    edges.put(e.key(), d.result());
                    changed = true;
                } else {
                    edges.put(e.key(), e); // объезда нет — дорога как была
                }
                continue;
            }
            schedule(level, e, hits, sig);
            edges.put(e.key(), e);
        }
        if (!changed) {
            return base;
        }
        Map<java.util.UUID, RoadNode> nodes = new LinkedHashMap<>();
        for (RoadNode n : base.nodes()) {
            nodes.put(n.id(), n);
        }
        return new RoadNetwork(base.worldSeed(), nodes, edges);
    }

    private static void schedule(ServerLevel level, RoadEdge baseline, List<RoadObstacle> hits, String sig) {
        String flight = baseline.key() + "|" + sig;
        if (!IN_FLIGHT.add(flight)) {
            return;
        }
        POOL.submit(() -> {
            try {
                RoadEdge result = computeDetour(baseline, hits,
                    level.getChunkSource().getGenerator(), level, level.getChunkSource().randomState());
                if (level != boundLevel) {
                    return; // мир сменился, пока считали
                }
                DETOURS.put(baseline.key(), new Detour(baseline, result, sig));
                RoadNetworkStore.republish();
            } catch (Throwable t) {
                LOGGER.warn("[RouteDetourEngine] Объезд ребра {} не посчитался: {}", baseline.key(), t.toString());
            } finally {
                IN_FLIGHT.remove(flight);
            }
        });
    }

    /** Подпись: ключ + хеш геометрии базы + задевающие препятствия. */
    private static String signature(RoadEdge e, List<RoadObstacle> hits) {
        StringBuilder sb = new StringBuilder(64);
        sb.append(Integer.toHexString(geometryHash(e.geometry())));
        for (RoadObstacle o : hits) {
            sb.append('|').append(o.id()).append('@').append(o.center().getX()).append(',')
                .append(o.center().getZ()).append('r').append(o.radius()).append(o.severity().ordinal());
        }
        return sb.toString();
    }

    private static int geometryHash(List<RoadPoint2D> g) {
        int h = 1;
        for (RoadPoint2D p : g) {
            h = 31 * h + p.x();
            h = 31 * h + p.z();
            h = 31 * h + (p.isBridge() ? 1 : 0);
        }
        return h;
    }

    // =========================================================================
    // Для укладки
    // =========================================================================

    /**
     * Ключ учёта укладки для ребра из опубликованной сети. У объездной
     * геометрии свой ключ: в чанках, уже уложенных по базе, объезд надо
     * уложить заново.
     */
    public static String pavingKey(RoadEdge published) {
        Detour d = DETOURS.get(published.key());
        if (d != null && d.result() == published) {
            return published.key() + "#d" + Integer.toHexString(geometryHash(published.geometry()));
        }
        return published.key();
    }

    /** Базовое ребро для закрепления (объезд не закрепляется — он снимается вместе с препятствием). */
    public static RoadEdge baselineOf(RoadEdge published) {
        Detour d = DETOURS.get(published.key());
        if (d != null && d.result() == published) {
            return d.baseline();
        }
        return published;
    }

    // =========================================================================
    // Геометрия объезда
    // =========================================================================

    /** Задевает ли полилиния зону препятствия (с запасом {@link #HIT_MARGIN}). */
    public static boolean intersects(List<RoadPoint2D> pts, RoadObstacle obstacle) {
        if (pts.size() < 2) {
            return false;
        }
        double r = obstacle.radius() + HIT_MARGIN;
        double ox = obstacle.center().getX();
        double oz = obstacle.center().getZ();
        for (int i = 0; i < pts.size() - 1; i++) {
            RoadPoint2D p = pts.get(i);
            RoadPoint2D q = pts.get(i + 1);
            // bbox-префильтр отрезка
            if (Math.min(p.x(), q.x()) - r > ox || Math.max(p.x(), q.x()) + r < ox
                || Math.min(p.z(), q.z()) - r > oz || Math.max(p.z(), q.z()) + r < oz) {
                continue;
            }
            if (dist2ToSeg(ox, oz, p, q) <= r * r) {
                return true;
            }
        }
        return false;
    }

    /**
     * Объезд всех задевающих препятствий по очереди: каждое следующее
     * проверяется уже по новой геометрии.
     *
     * @return ребро с объездом или {@code null}, если ни один объезд не понадобился/не нашёлся
     */
    static RoadEdge computeDetour(RoadEdge baseline, List<RoadObstacle> hits,
                                  ChunkGenerator generator, LevelHeightAccessor height, RandomState randomState) {
        List<RoadPoint2D> pts = baseline.geometry();
        boolean any = false;
        for (RoadObstacle o : hits) {
            if (!intersects(pts, o)) {
                continue; // прошлый объезд уже обошёл и это
            }
            List<RoadPoint2D> spliced = rerouteAround(pts, o, generator, height, randomState);
            if (spliced == null) {
                LOGGER.warn("[RouteDetourEngine] Объезд {} для ребра {} не найден — дорога остаётся ({})",
                    o.id(), baseline.key(), o.severity());
                continue;
            }
            pts = spliced;
            any = true;
        }
        if (!any) {
            return null;
        }
        double dist = 0.0;
        for (int i = 0; i < pts.size() - 1; i++) {
            dist += pts.get(i).distance(pts.get(i + 1));
        }
        LOGGER.info("[RouteDetourEngine] Объезд ребра {}: точек {} → {}, длина {} → {}",
            baseline.key(), baseline.geometry().size(), pts.size(),
            (int) baseline.distance(), (int) dist);
        return new RoadEdge(baseline.fromId(), baseline.toId(), pts, false, dist);
    }

    /**
     * Локальная перестройка участка у одного препятствия.
     *
     * <p>Pin — последняя точка до препятствия дальше {@code r + ANCHOR_MARGIN},
     * Pout — первая такая после. Между ними A* со штрафом:
     * <pre>
     *   d ≤ r + 6   →  ∞ (BLOCKING) | HAZARD_COST (HAZARDOUS)
     *   d ≤ r + 24  →  BUFFER_COST
     * </pre>
     *
     * @return вшитая полилиния или {@code null}
     */
    private static List<RoadPoint2D> rerouteAround(List<RoadPoint2D> pts, RoadObstacle obstacle,
                                                   ChunkGenerator generator, LevelHeightAccessor height,
                                                   RandomState randomState) {
        double r = obstacle.radius() + HIT_MARGIN;
        int ox = obstacle.center().getX();
        int oz = obstacle.center().getZ();

        int firstSeg = -1;
        int lastSeg = -1;
        for (int i = 0; i < pts.size() - 1; i++) {
            if (dist2ToSeg(ox, oz, pts.get(i), pts.get(i + 1)) <= r * r) {
                if (firstSeg == -1) {
                    firstSeg = i;
                }
                lastSeg = i;
            }
        }
        if (firstSeg == -1) {
            return null;
        }

        double safe = obstacle.radius() + ANCHOR_MARGIN;
        double safe2 = safe * safe;
        int inIdx = firstSeg;
        while (inIdx > 0 && pts.get(inIdx).distSqr(ox, oz) < safe2) {
            inIdx--;
        }
        int outIdx = Math.min(pts.size() - 1, lastSeg + 1);
        while (outIdx < pts.size() - 1 && pts.get(outIdx).distSqr(ox, oz) < safe2) {
            outIdx++;
        }
        if (inIdx >= outIdx) {
            return null;
        }
        RoadPoint2D pIn = pts.get(inIdx);
        RoadPoint2D pOut = pts.get(outIdx);
        if (pIn.distSqr(ox, oz) < safe2 || pOut.distSqr(ox, oz) < safe2) {
            // Препятствие на самом узле — объехать нельзя.
            return null;
        }

        double forbid = obstacle.radius() + FORBID_MARGIN;
        double buffer = obstacle.radius() + BUFFER_MARGIN;
        double forbid2 = forbid * forbid;
        double buffer2 = buffer * buffer;
        boolean blocking = obstacle.severity() == RoadObstacle.ObstacleSeverity.BLOCKING;
        CoarsePathfinder.PathConstraint constraint = (x, z) -> {
            double dx = (double) ox - x;
            double dz = (double) oz - z;
            double d2 = dx * dx + dz * dz;
            if (d2 <= forbid2) {
                return blocking ? Double.POSITIVE_INFINITY : HAZARD_COST;
            }
            return d2 <= buffer2 ? BUFFER_COST : 0.0;
        };

        ObjectList<RoadPoint2D> arc = CoarsePathfinder.findLandRoad(
            generator, height, randomState, pIn.x(), pIn.z(), pOut.x(), pOut.z(), constraint);
        if (arc.isEmpty()) {
            return null;
        }

        List<RoadPoint2D> out = new ArrayList<>(pts.size() + arc.size());
        for (int i = 0; i <= inIdx; i++) {
            out.add(pts.get(i));
        }
        for (int i = 1; i < arc.size() - 1; i++) {
            addSpaced(out, arc.get(i));
        }
        for (int i = outIdx; i < pts.size(); i++) {
            // Концы ребра обязаны остаться на месте — добавляем без прореживания.
            if (i == pts.size() - 1) {
                out.add(pts.get(i));
            } else {
                addSpaced(out, pts.get(i));
            }
        }
        return out;
    }

    private static void addSpaced(List<RoadPoint2D> out, RoadPoint2D p) {
        if (out.get(out.size() - 1).distSqr(p) > SPLICE_MIN_GAP2) {
            out.add(p);
        }
    }

    private static double dist2ToSeg(double px, double pz, RoadPoint2D a, RoadPoint2D b) {
        double dx = b.x() - a.x();
        double dz = b.z() - a.z();
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0.0 : ((px - a.x()) * dx + (pz - a.z()) * dz) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double ex = a.x() + dx * t - px;
        double ez = a.z() + dz * t - pz;
        return ex * ex + ez * ez;
    }
}
