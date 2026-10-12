package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Движок связующих узлов-развилок (Junction Engine).
 *
 * <p>Проблема: каждый маршрут {@code A-B} строится независимым вызовом A* ({@link CoarsePathfinder}),
 * поэтому две дороги из разных городов, вынужденные пройти через одно узкое место (мост, перевал,
 * долина), идут двумя параллельными линиями, визуально сливаются перед сужением и тут же расходятся
 * («сошлись-разошлись»). {@link RouteBundler} чинит только частный случай — маршруты с общим
 * городом отправления, — склеивая при этом лишь геометрию без настоящего узла в графе.
 *
 * <p>Решение: явные узлы-{@link KnotType#WAYPOINT} в местах схождения:
 * <ul>
 *   <li><b>Протяжка через деревню:</b> если готовый маршрут проходит в радиусе
 *   {@link #TOWN_SNAP_RADIUS} от чужого города, ребро {@code A-B} разрезается на
 *   {@code A-T} + {@code T-B} с повторным использованием исходной геометрии
 *   (без нового A* и без дорог-призраков).</li>
 *   <li><b>Развилка вне городов:</b> если два ребра делят коридор длиной от
 *   {@link #MIN_SHARED_LEN} в пределах {@link #CORRIDOR_WIDTH}, в начале и конце общего
 *   участка создаются WAYPOINT-узлы {@code J1}/{@code J2}, а ребра перешиваются в
 *   {@code A-J1}, {@code J1-J2} (общий ствол), {@code J2-B} и аналогично для второй дороги.
 *   Геометрия ствола берется из первой дороги целиком, второй — только подъезды.</li>
 * </ul>
 *
 * <p>Все операции — чистая хирургия полилиний: новые A* не запускаются, старые координаты
 * переиспользуются, поэтому ранее запеченные в чанки блоки дорог остаются валидными.
 * Потокобезопасность: только операции {@link WorldObject}/{@link WorldObjectManager},
 * которые потокобезопасны по построению; вызывается из фонового {@code ROUTE_EXECUTOR}.
 */
public final class JunctionEngine {

    /** Ширина общего коридора: дороги ближе друг к другу считаются одним стволом. */
    public static final double CORRIDOR_WIDTH = 48.0;

    /** Минимальная длина общего участка, оправдывающая создание развилки. */
    public static final double MIN_SHARED_LEN = 120.0;

    /**
     * Минимальный косинус угла между направлениями дорог для признания их параллельными.
     * cos(30°) ≈ 0.866 — дороги должны идти в пределах 30° от параллельного направления.
     * Это предотвращает создание развилок для дорог, которые просто проходят рядом,
     * но идут в разных направлениях (перпендикулярно или под углом).
     */
    public static final double MIN_PARALLEL_COSINE = 0.866;

    /** Радиус дедупликации развилок: повторная вилка рядом переиспользует узел. */
    public static final double FORK_MERGE_RADIUS = 64.0;

    /** Радиус протяжки маршрута через попутную деревню. */
    public static final double TOWN_SNAP_RADIUS = 96.0;

    /** Зона у концов ребер (внутри городов), где развилки не ставятся. */
    public static final double ENDPOINT_EXCLUDE_RADIUS = 32.0;

    /**
     * Радиус запрета развилок вплотную к городу.
     * Намеренно меньше {@link #TOWN_SNAP_RADIUS}: коридор в 30–90 блоках от деревни
     * уже не должен идти через неё (иначе был бы снап), но ему всё ещё нужна
     * связующая развилка — иначе дороги остаются параллельными
     * («сошлись-разошлись» как на скриншоте).
     * Держим маленьким (размер застройки деревни): запрет действует только
     * внутри самого поселения, а не на подходах к нему.
     */
    public static final double JUNCTION_NEAR_TOWN_RADIUS = 20.0;

    /** Минимальная длина огрызка ребра после разреза (короче — разрез не делаем). */
    public static final double MIN_STUB_LEN = 24.0;

    /** Лимит новых узлов за один проход (защита от взрывного роста графа). */
    public static final int MAX_JUNCTIONS_PER_PASS = 8;

    /**
     * Debug-режим: если true, выводит подробные логи в консоль.
     * Включить в конфиге или через команду для отладки проблем с развилками.
     */
    /** 
     * Режим отладки: включает детальные логи в консоль.
     * Установите true для диагностики проблем с дорогами и развилками.
     */
    public static boolean DEBUG_MODE = true;

    private static final ResourceLocation JUNCTION_ICON =
            ResourceLocation.tryBuild("minecraft", "air");

    /**
     * Глобальная блокировка хирургии сети. Движок вызывается из двух потоков:
     * фонового {@code ROUTE_EXECUTOR} (инкрементально, через {@link #onNewRoute})
     * и главного потока (полный проход через {@link #resolveNetwork} при
     * пересборке графа). Без сериализации два потока разрезали бы одно ребро
     * одновременно и получали расходящиеся снимки {@code byId}.
     */
    private static final Object NETWORK_LOCK = new Object();

    private JunctionEngine() {
    }

    private static void debugLog(String message) {
        if (DEBUG_MODE) {
            System.out.println("[JunctionEngine DEBUG] " + message);
        }
    }

    /**
     * Целевая протяжка существующих дорог через newly зарегистрированный город.
     *
     * <p>Вызывается из {@code KnotNetworkGraph.connectNewKnot} в момент, когда
     * деревня физически сгенерировалась. Инкрементальный путь
     * ({@link #onNewRoute}) здесь не помогает: он обрабатывает только свежие
     * маршруты, а уже построенные дороги, прошедшие мимо будущей деревни,
     * оставались без протяжки навсегда — ровно кейс «дорога прошла выше деревни,
     * хотя должна была зайти в неё».
     *
     * @param town    новый город (уже добавлен в менеджер)
     * @param manager менеджер мира
     * @return число выполненных протяжек
     */
    public static int snapEdgesViaTown(WorldObject town, WorldObjectManager manager) {
        if (town == null || manager == null) return 0;
        if (typeOf(town) != KnotType.TOWN) return 0;

        synchronized (NETWORK_LOCK) {
            Map<UUID, WorldObject> byId = snapshotById(manager);
            byId.putIfAbsent(town.id(), town);

            int total = 0;
            for (EdgeView edge : collectEdges(byId)) {
                if (edge.a().id().equals(town.id()) || edge.b().id().equals(town.id())) continue;
                if (!edgeExists(edge)) continue;
                EdgeView fresh = refreshEdge(edge, byId);
                if (fresh == null) continue;
                if (trySnapEdgeViaTown(fresh, byId)) {
                    total++;
                }
            }
            return total;
        }
    }

    /**
     * Инкрементальная обработка одного свежепостроенного маршрута {@code a-B}.
     * Вызывается из {@link RouteGenerator} после готовности геометрии.
     *
     * @return 1, если ребро было перешито (снэп/развилка), иначе 0
     */
    public static int onNewRoute(WorldObject a, WorldObject b, WorldObjectManager manager) {
        if (a == null || b == null || manager == null) return 0;

        synchronized (NETWORK_LOCK) {
            Map<UUID, WorldObject> byId = snapshotById(manager);
            byId.putIfAbsent(a.id(), a);
            byId.putIfAbsent(b.id(), b);

            pruneOrphanJunctions(byId, manager);

            EdgeView edge = buildEdge(a, b, byId);
            if (edge == null) return 0;

            if (trySnapEdgeViaTown(edge, byId)) {
                return 1;
            }

            List<EdgeView> others = collectEdges(byId);
            JunctionBudget budget = new JunctionBudget(2);
            for (EdgeView other : others) {
                if (other.key().equals(edge.key())) continue;
                if (!edgeExists(other)) continue;
                if (tryJunctionPair(edge, other, byId, manager, budget)) {
                    return 1;
                }
                if (budget.remaining() <= 0) break;
            }
            return 0;
        }
    }

    /**
     * Полный проход по сети: протяжки через деревни + развилки + чистка сирот.
     *
     * @return число выполненных перешивок ребер
     */
    public static int resolveNetwork(Collection<WorldObject> knots, WorldObjectManager manager) {
        if (knots == null || manager == null) return 0;

        synchronized (NETWORK_LOCK) {
            Map<UUID, WorldObject> byId = new HashMap<>();
            for (WorldObject o : knots) {
                byId.put(o.id(), o);
            }
            for (WorldObject o : manager.all()) {
                byId.putIfAbsent(o.id(), o);
            }

            int total = pruneOrphanJunctions(byId, manager);

            for (int restart = 0; restart < 6; restart++) {
                List<EdgeView> edges = collectEdges(byId);
                boolean changed = false;
                for (EdgeView edge : edges) {
                    if (!edgeExists(edge)) continue;
                    if (trySnapEdgeViaTown(edge, byId)) {
                        total++;
                        changed = true;
                        break;
                    }
                }
                if (!changed) break;
            }

            JunctionBudget budget = new JunctionBudget(MAX_JUNCTIONS_PER_PASS);
            for (int restart = 0; restart < 10 && budget.remaining() > 0; restart++) {
                List<EdgeView> edges = collectEdges(byId);
                boolean changed = false;
                for (int i = 0; i < edges.size(); i++) {
                    for (int j = i + 1; j < edges.size(); j++) {
                        EdgeView e1 = edges.get(i);
                        EdgeView e2 = edges.get(j);
                        if (!edgeExists(e1) || !edgeExists(e2)) continue;
                        if (tryJunctionPair(e1, e2, byId, manager, budget)) {
                            total++;
                            changed = true;
                            break;
                        }
                        if (budget.remaining() <= 0) break;
                    }
                    if (changed || budget.remaining() <= 0) break;
                }
                if (!changed) break;
            }

            return total;
        }
    }

    // =====================================================================
    // Внутренняя модель ребра
    // =====================================================================

    private record EdgeView(WorldObject a, WorldObject b, ObjectList<RoadPoint2D> pts) {
        String key() {
            return a.id().compareTo(b.id()) < 0 ? a.id() + "_" + b.id() : b.id() + "_" + a.id();
        }
    }

    private static final class JunctionBudget {
        private int remaining;

        JunctionBudget(int limit) {
            this.remaining = limit;
        }

        int remaining() {
            return remaining;
        }

        boolean consume() {
            if (remaining <= 0) return false;
            remaining--;
            return true;
        }
    }

    private static BlockPos posOf(WorldObject o) {
        if (o == null) return null;
        return o.getSingle(KnotMetaComponent.class).map(m -> m.position.get()).orElse(null);
    }

    private static KnotType typeOf(WorldObject o) {
        if (o == null) return KnotType.NONE;
        return o.getSingle(KnotMetaComponent.class).map(m -> m.type.get()).orElse(KnotType.NONE);
    }

    private static boolean hasRoute(WorldObject from, UUID target) {
        if (from == null || target == null) return false;
        return from.getSingle(KnotRoutesComponent.class)
                .map(r -> r.getRouteTo(target).isPresent())
                .orElse(false);
    }

    private static boolean edgeExists(EdgeView edge) {
        return hasRoute(edge.a(), edge.b().id()) || hasRoute(edge.b(), edge.a().id());
    }

    private static String edgeKey(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    private static Map<UUID, WorldObject> snapshotById(WorldObjectManager manager) {
        Map<UUID, WorldObject> byId = new HashMap<>();
        for (WorldObject o : manager.all()) {
            byId.put(o.id(), o);
        }
        return byId;
    }

    private static EdgeView buildEdge(WorldObject a, WorldObject b, Map<UUID, WorldObject> byId) {
        BlockPos posA = posOf(a);
        if (posA == null || posOf(b) == null) return null;

        KnotRouteEntry entry = a.getSingle(KnotRoutesComponent.class)
                .flatMap(r -> r.getRouteTo(b.id()))
                .orElseGet(() -> b.getSingle(KnotRoutesComponent.class)
                        .flatMap(r -> r.getRouteTo(a.id()))
                        .map(rev -> new KnotRouteEntry(
                                a.id(), reversedCopy(rev.waypoints()), rev.isSeaRoute(), rev.distance()))
                        .orElse(null));
        if (entry == null || entry.isSeaRoute() || entry.waypoints().size() < 2) return null;

        ObjectList<RoadPoint2D> pts = new ObjectArrayList<>(entry.waypoints());
        double dFirst = pts.getFirst().distSqr(posA.getX(), posA.getZ());
        double dLast = pts.getLast().distSqr(posA.getX(), posA.getZ());
        if (dLast < dFirst) {
            Collections.reverse(pts);
        }
        byId.putIfAbsent(a.id(), a);
        byId.putIfAbsent(b.id(), b);
        return new EdgeView(a, b, pts);
    }

    private static List<EdgeView> collectEdges(Map<UUID, WorldObject> byId) {
        List<EdgeView> edges = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (WorldObject knot : byId.values()) {
            KnotRoutesComponent routes = knot.getSingle(KnotRoutesComponent.class).orElse(null);
            BlockPos knotPos = posOf(knot);
            if (routes == null || knotPos == null) continue;
            List<KnotRouteEntry> copy = new ArrayList<>(routes.routes.get());
            for (KnotRouteEntry entry : copy) {
                if (entry.isSeaRoute() || entry.waypoints().size() < 2) continue;
                WorldObject other = byId.get(entry.targetKnotId());
                if (other == null || posOf(other) == null) continue;
                if (!seen.add(edgeKey(knot.id(), other.id()))) continue;
                ObjectList<RoadPoint2D> pts = new ObjectArrayList<>(entry.waypoints());
                double dFirst = pts.getFirst().distSqr(knotPos.getX(), knotPos.getZ());
                double dLast = pts.getLast().distSqr(knotPos.getX(), knotPos.getZ());
                if (dLast < dFirst) {
                    Collections.reverse(pts);
                }
                edges.add(new EdgeView(knot, other, pts));
            }
        }
        return edges;
    }

    private static ObjectList<RoadPoint2D> reversedCopy(ObjectList<RoadPoint2D> src) {
        ObjectList<RoadPoint2D> rev = new ObjectArrayList<>(src);
        Collections.reverse(rev);
        return rev;
    }

    // =====================================================================
    // Протяжка через попутную деревню
    // =====================================================================

    private static boolean trySnapEdgeViaTown(EdgeView edge, Map<UUID, WorldObject> byId) {
        if (edge.pts().size() < 3) return false;
        if (RouteBundler.calculateDistance(edge.pts()) < 200.0) return false;

        for (WorldObject town : byId.values()) {
            if (typeOf(town) != KnotType.TOWN) continue;
            if (town.id().equals(edge.a().id()) || town.id().equals(edge.b().id())) continue;
            BlockPos tp = posOf(town);
            if (tp == null) continue;

            if (hasRoute(edge.a(), town.id()) || hasRoute(edge.b(), town.id())) continue;

            RouteBundler.PolylineProjection proj = RouteBundler.projectOnPolyline(
                    new RoadPoint2D(tp.getX(), tp.getZ()), edge.pts());
            if (proj.dist > TOWN_SNAP_RADIUS || proj.dist < 8.0) continue;

            // Защита от «стягивания» дороги на город, который лежит за её концом:
            // тогда крюк A-T длиннее всей дороги и снап плодит петли/зигзаги.
            // Сравниваем именно с концом, где состыковка короче.
            double townX = tp.getX();
            double townZ = tp.getZ();
            RoadPoint2D headPt = edge.pts().getFirst();
            RoadPoint2D tailPt = edge.pts().getLast();
            double headHook = Math.hypot(headPt.x() - townX, headPt.z() - townZ);
            double tailHook = Math.hypot(tailPt.x() - townX, tailPt.z() - townZ);

            double arcBefore = arcLengthTo(edge.pts(), proj.segmentIndex, proj.projX, proj.projZ);
            double total = RouteBundler.calculateDistance(edge.pts());
            double arcAfter = total - arcBefore;
            if (arcBefore < 48.0 || arcAfter < 48.0) continue;

            // Узел, лежащий за концом дороги, не может быть промежуточным:
            // снап обязан делить дорогу на два куска, а не пришивать крюк снаружи.
            BlockPos aPos = posOf(edge.a());
            BlockPos bPos = posOf(edge.b());
            if (aPos != null && bPos != null
                    && !KnotNetworkGraph.isIntermediateNode(aPos, bPos, tp)) {
                continue;
            }
            if (Math.min(headHook, tailHook) > total * 1.28) {
                continue;
            }

            ObjectList<RoadPoint2D> ptsAT = new ObjectArrayList<>();
            for (int i = 0; i <= proj.segmentIndex; i++) {
                appendDedup(ptsAT, edge.pts().get(i));
            }
            appendDedup(ptsAT, new RoadPoint2D(tp.getX(), tp.getZ(),
                    edge.pts().get(proj.segmentIndex).isBridge()));

            ObjectList<RoadPoint2D> ptsTB = new ObjectArrayList<>();
            int next = Math.min(proj.segmentIndex + 1, edge.pts().size() - 1);
            appendDedup(ptsTB, new RoadPoint2D(tp.getX(), tp.getZ(),
                    edge.pts().get(next).isBridge()));
            for (int i = next; i < edge.pts().size(); i++) {
                appendDedup(ptsTB, edge.pts().get(i));
            }

            if (RouteBundler.calculateDistance(ptsAT) < MIN_STUB_LEN
                    || RouteBundler.calculateDistance(ptsTB) < MIN_STUB_LEN) {
                continue;
            }

            RouteGenerator.disconnectKnots(edge.a(), edge.b());
            connectPair(edge.a(), town, ptsAT);
            connectPair(town, edge.b(), ptsTB);
            return true;
        }
        return false;
    }

    // =====================================================================
    // Развилки на общих коридорах
    // =====================================================================

    private static boolean tryJunctionPair(EdgeView e1, EdgeView e2,
                                           Map<UUID, WorldObject> byId,
                                           WorldObjectManager manager,
                                           JunctionBudget budget) {
        if (e1.key().equals(e2.key())) {
            debugLog("tryJunctionPair: skipped (same key: " + e1.key() + ")");
            return false;
        }
        if (e1.pts().size() < 2 || e2.pts().size() < 2) return false;

        // ✅ ИСПРАВЛЕНИЕ: Не создавать развилку между рёбрами, у которых оба конца — WAYPOINT
        // Это предотвращает создание развилок на уже разрезанных дорогах (Скрин 2)
        KnotType typeA1 = typeOf(e1.a());
        KnotType typeB1 = typeOf(e1.b());
        KnotType typeA2 = typeOf(e2.a());
        KnotType typeB2 = typeOf(e2.b());
        
        boolean e1HasWaypoint = typeA1 == KnotType.WAYPOINT || typeB1 == KnotType.WAYPOINT;
        boolean e2HasWaypoint = typeA2 == KnotType.WAYPOINT || typeB2 == KnotType.WAYPOINT;
        
        if (e1HasWaypoint && e2HasWaypoint) {
            debugLog("tryJunctionPair: skipped (both edges already have WAYPOINT nodes)");
            debugLog("  e1: " + typeA1 + " -> " + typeB1);
            debugLog("  e2: " + typeA2 + " -> " + typeB2);
            return false;
        }

        if (!boxesOverlap(e1.pts(), e2.pts(), CORRIDOR_WIDTH + 32.0)) return false;

        BlockPos a1 = posOf(e1.a());
        BlockPos b1 = posOf(e1.b());
        BlockPos a2 = posOf(e2.a());
        BlockPos b2 = posOf(e2.b());
        if (a1 == null || b1 == null || a2 == null || b2 == null) return false;

        double excludeSq = ENDPOINT_EXCLUDE_RADIUS * ENDPOINT_EXCLUDE_RADIUS;
        int n1 = e1.pts().size();
        boolean[] shared = new boolean[n1];
        int[] projX = new int[n1];
        int[] projZ = new int[n1];

        for (int i = 0; i < n1; i++) {
            RoadPoint2D pt = e1.pts().get(i);
            if (pt.distSqr(a1.getX(), a1.getZ()) < excludeSq
                    || pt.distSqr(b1.getX(), b1.getZ()) < excludeSq
                    || pt.distSqr(a2.getX(), a2.getZ()) < excludeSq
                    || pt.distSqr(b2.getX(), b2.getZ()) < excludeSq) {
                continue;
            }
            RouteBundler.PolylineProjection proj =
                    RouteBundler.projectOnPolyline(pt, e2.pts());
            if (proj.dist <= CORRIDOR_WIDTH) {
                // Проверка направления: дороги должны идти параллельно, а не просто быть рядом
                if (i > 0 && i < n1 - 1 && proj.segmentIndex >= 0 && proj.segmentIndex < e2.pts().size() - 1) {
                    // Направление дороги e1 в текущей точке
                    RoadPoint2D prev1 = e1.pts().get(i - 1);
                    RoadPoint2D next1 = e1.pts().get(i + 1);
                    double dx1 = next1.x() - prev1.x();
                    double dz1 = next1.z() - prev1.z();
                    double len1 = Math.sqrt(dx1 * dx1 + dz1 * dz1);
                    
                    // Направление дороги e2 в проецируемом сегменте
                    RoadPoint2D seg2Start = e2.pts().get(proj.segmentIndex);
                    RoadPoint2D seg2End = e2.pts().get(proj.segmentIndex + 1);
                    double dx2 = seg2End.x() - seg2Start.x();
                    double dz2 = seg2End.z() - seg2Start.z();
                    double len2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);
                    
                    // Косинус угла между направлениями (скалярное произведение нормализованных векторов)
                    if (len1 > 1e-6 && len2 > 1e-6) {
                        double cosAngle = Math.abs((dx1 * dx2 + dz1 * dz2) / (len1 * len2));
                        
                        // Дороги параллельны только если угол между ними < 30° (cos > 0.866)
                        if (cosAngle < MIN_PARALLEL_COSINE) {
                            continue; // Дороги идут под углом — не считаем их общим коридором
                        }
                    }
                }
                
                shared[i] = true;
                projX[i] = proj.projX;
                projZ[i] = proj.projZ;
            }
        }

        int runStart = -1;
        for (int i = 0; i <= n1; i++) {
            boolean on = i < n1 && shared[i];
            if (on && runStart < 0) {
                runStart = i;
            }
            if (!on && runStart >= 0) {
                if (handleSharedRun(e1, e2, runStart, i - 1, projX, projZ, byId, manager, budget)) {
                    return true;
                }
                runStart = -1;
            }
        }
        return false;
    }

    private static boolean handleSharedRun(EdgeView e1, EdgeView e2,
                                          int runStart, int runEnd,
                                          int[] projX, int[] projZ,
                                          Map<UUID, WorldObject> byId,
                                          WorldObjectManager manager,
                                          JunctionBudget budget) {
        double runLen = 0.0;
        for (int i = runStart; i < runEnd; i++) {
            runLen += e1.pts().get(i).distance(e1.pts().get(i + 1));
        }
        if (runLen < MIN_SHARED_LEN) {
            debugLog("handleSharedRun: rejected (e1 runLen " + String.format("%.1f", runLen) + " < " + MIN_SHARED_LEN + ")");
            return false;
        }

        double e2RunLen = Math.hypot(projX[runEnd] - projX[runStart], projZ[runEnd] - projZ[runStart]);
        if (e2RunLen < MIN_SHARED_LEN * 0.5) {
            debugLog("handleSharedRun: rejected (e2 runLen " + String.format("%.1f", e2RunLen) + " < " + (MIN_SHARED_LEN * 0.5) + ")");
            return false;
        }

        RoadPoint2D s1 = e1.pts().get(runStart);
        RoadPoint2D t1 = e1.pts().get(runEnd);
        int pinX = (int) Math.round((s1.x() + projX[runStart]) * 0.5);
        int pinZ = (int) Math.round((s1.z() + projZ[runStart]) * 0.5);
        int poutX = (int) Math.round((t1.x() + projX[runEnd]) * 0.5);
        int poutZ = (int) Math.round((t1.z() + projZ[runEnd]) * 0.5);
        boolean bridge = s1.isBridge() || t1.isBridge();

        debugLog("handleSharedRun: found shared corridor");
        debugLog("  e1 runLen: " + String.format("%.1f", runLen) + "m, e2 runLen: " + String.format("%.1f", e2RunLen) + "m");
        debugLog("  pin: (" + pinX + ", " + pinZ + "), pout: (" + poutX + ", " + poutZ + ")");

        // Проверка 1: дороги должны реально сходиться/расходиться, а не просто идти параллельно.
        // Если расстояние между концами (по прямой) слишком близко к длине коридора (по ломаной),
        // это означает, что дороги идут почти параллельно без схождения.
        double junctionDist = Math.hypot(poutX - pinX, poutZ - pinZ);
        if (junctionDist > runLen * 0.95) {
            debugLog("handleSharedRun: rejected (roads are parallel, not converging: junction distance " + 
                     String.format("%.1f", junctionDist) + "m > " + String.format("%.1f", runLen * 0.95) + "m)");
            return false;
        }

        // Проверка 2: средняя ширина коридора не должна быть слишком узкой на всём протяжении.
        // Если дороги идут очень близко друг к другу (< 20 блоков) на всём протяжении,
        // это случайное параллельное следование, а не реальная развилка Y-образного схождения.
        double totalWidth = 0.0;
        int widthSamples = 0;
        for (int i = runStart; i <= runEnd; i++) {
            RoadPoint2D pt = e1.pts().get(i);
            double width = Math.hypot(pt.x() - projX[i], pt.z() - projZ[i]);
            totalWidth += width;
            widthSamples++;
        }
        double avgWidth = widthSamples > 0 ? totalWidth / widthSamples : 0.0;
        
        // Если средняя ширина коридора < 20 блоков, дороги идут слишком близко параллельно
        if (avgWidth < 20.0) {
            debugLog("handleSharedRun: rejected (corridor too narrow: avg width " + 
                     String.format("%.1f", avgWidth) + "m < 20.0m - roads are too close parallel)");
            return false;
        }

        // Развилка рядом с городом разрешена: снап уже проверен раньше
        // (trySnapEdgeViaTown) и не сработал — значит, общий коридор реально
        // проходит мимо деревни, и без связующего узла дороги останутся
        // двумя параллельными линиями («сошлись-разошлись»).
        // Единственный запрет — вплотную к центру (узел развилки внутри застройки).
        if (nearTown(byId, pinX, pinZ, JUNCTION_NEAR_TOWN_RADIUS)
                || nearTown(byId, poutX, poutZ, JUNCTION_NEAR_TOWN_RADIUS)) {
            debugLog("handleSharedRun: rejected (too close to town)");
            return false;
        }

        // Полный дубликат — это тоже общий коридор, а не повод молча переписать
        // чужую геометрию: создаём на нём связующие развилки как обычно.
        // Иначе дороги-дубликаты остаются параллельными рёбрами A-B без узла
        // и на карте рисуются как «сошлись-разошлись» (кейс со скриншота).
        if (coversWholeEdge(e1, runStart, runEnd) && coversWholeEdgeProjection(e2, projX, projZ, runStart, runEnd)) {
            debugLog("handleSharedRun: detected duplicate pair, splitting...");
            return splitDuplicatePair(e1, e2, byId, manager, budget);
        }

        if (budget.remaining() < 2
                && findNearbyWaypoint(byId, pinX, pinZ) == null) {
            debugLog("handleSharedRun: rejected (budget exhausted)");
            return false;
        }

        WorldObject j1 = findOrCreateJunction(byId, manager, budget, pinX, pinZ, bridge);
        WorldObject j2 = findOrCreateJunction(byId, manager, budget, poutX, poutZ, bridge);
        if (j1 == null || j2 == null) {
            debugLog("handleSharedRun: failed to create junctions");
            return false;
        }

        debugLog("handleSharedRun: created junctions J1=" + j1.id().toString().substring(0, 8) + 
                 ", J2=" + j2.id().toString().substring(0, 8));

        boolean changed = false;
        if (j1.id().equals(j2.id())) {
            debugLog("handleSharedRun: J1 == J2, splitting via single junction");
            if (edgeExists(e1) && splitEdgeViaSingle(e1, j1)) changed = true;
            EdgeView e2fresh = refreshEdge(e2, byId);
            if (e2fresh != null && edgeExists(e2fresh) && splitEdgeViaSingle(e2fresh, j1)) changed = true;
        } else {
            debugLog("handleSharedRun: J1 != J2, splitting via junction pair");
            if (edgeExists(e1) && splitEdgeViaJunctions(e1, j1, j2)) changed = true;
            EdgeView e2fresh = refreshEdge(e2, byId);
            if (e2fresh != null && edgeExists(e2fresh) && splitEdgeViaJunctions(e2fresh, j1, j2)) {
                changed = true;
            }
        }
        
        if (changed) {
            debugLog("handleSharedRun: SUCCESS - junction created");
        } else {
            debugLog("handleSharedRun: FAILED - no edges were split");
        }
        
        return changed;
    }

    private static EdgeView refreshEdge(EdgeView stale, Map<UUID, WorldObject> byId) {
        WorldObject a = byId.get(stale.a().id());
        WorldObject b = byId.get(stale.b().id());
        if (a == null || b == null) return null;
        return buildEdge(a, b, byId);
    }

    private static boolean coversWholeEdge(EdgeView edge, int runStart, int runEnd) {
        double total = RouteBundler.calculateDistance(edge.pts());
        double run = 0.0;
        for (int i = runStart; i < runEnd; i++) {
            run += edge.pts().get(i).distance(edge.pts().get(i + 1));
        }
        return run >= total * 0.9;
    }

    private static boolean coversWholeEdgeProjection(EdgeView e2, int[] projX, int[] projZ,
                                                     int runStart, int runEnd) {
        double e2Run = Math.hypot(projX[runEnd] - projX[runStart], projZ[runEnd] - projZ[runStart]);
        return e2Run >= RouteBundler.calculateDistance(e2.pts()) * 0.9;
    }

    /**
     * Разрезание пары полных дубликатов (два ребра идут по одному коридору от начала до конца).
     * Вместо молчаливой перезаписи геометрии создаём связующие развилки у концов
     * общего участка — иначе параллельные рёбра рисуются как «сошлись-разошлись».
     */
    private static boolean splitDuplicatePair(EdgeView e1, EdgeView e2,
                                              Map<UUID, WorldObject> byId,
                                              WorldObjectManager manager,
                                              JunctionBudget budget) {
        if (e1.pts().size() < 4) return false;

        BlockPos a1 = posOf(e1.a());
        BlockPos b1 = posOf(e1.b());
        BlockPos a2 = posOf(e2.a());
        BlockPos b2 = posOf(e2.b());
        if (a1 == null || b1 == null || a2 == null || b2 == null) return false;

        // Ищем первый и последний сегменты общего ствола, не задевающие
        // ни один из четырех городов-концов (чтобы развилки встали у выездов,
        // а не внутри поселений).
        double excludeSq = ENDPOINT_EXCLUDE_RADIUS * ENDPOINT_EXCLUDE_RADIUS;
        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < e1.pts().size(); i++) {
            RoadPoint2D pt = e1.pts().get(i);
            boolean nearEndpoint = pt.distSqr(a1.getX(), a1.getZ()) < excludeSq
                    || pt.distSqr(b1.getX(), b1.getZ()) < excludeSq
                    || pt.distSqr(a2.getX(), a2.getZ()) < excludeSq
                    || pt.distSqr(b2.getX(), b2.getZ()) < excludeSq;
            if (!nearEndpoint) {
                if (startIdx < 0) startIdx = i;
                endIdx = i;
            }
        }
        if (startIdx < 0 || endIdx <= startIdx) return false;

        RoadPoint2D s = e1.pts().get(startIdx);
        RoadPoint2D t = e1.pts().get(endIdx);
        boolean bridge = s.isBridge() || t.isBridge();

        WorldObject j1 = findOrCreateJunction(byId, manager, budget, s.x(), s.z(), bridge);
        WorldObject j2 = findOrCreateJunction(byId, manager, budget, t.x(), t.z(), bridge);
        if (j1 == null || j2 == null) return false;

        // Оба ребра перешиваются на общий ствол J1-J2 с собственными подъездами.
        // refreshEdge обязателен: после перешивки первого ребра геометрия второго
        // могла обновиться в обход snapshot'а.
        boolean changed = false;
        if (j1.id().equals(j2.id())) {
            if (edgeExists(e1)) {
                EdgeView fresh = refreshEdge(e1, byId);
                if (fresh != null && splitEdgeViaSingle(fresh, j1)) changed = true;
            }
            if (edgeExists(e2)) {
                EdgeView fresh = refreshEdge(e2, byId);
                if (fresh != null && splitEdgeViaSingle(fresh, j1)) changed = true;
            }
        } else {
            if (edgeExists(e1)) {
                EdgeView fresh = refreshEdge(e1, byId);
                if (fresh != null && splitEdgeViaJunctions(fresh, j1, j2)) changed = true;
            }
            if (edgeExists(e2)) {
                EdgeView fresh = refreshEdge(e2, byId);
                if (fresh != null && splitEdgeViaJunctions(fresh, j1, j2)) changed = true;
            }
        }
        return changed;
    }

    private static boolean nearTown(Map<UUID, WorldObject> byId, int x, int z, double radius) {
        double rSq = radius * radius;
        for (WorldObject o : byId.values()) {
            if (typeOf(o) != KnotType.TOWN) continue;
            BlockPos p = posOf(o);
            if (p == null) continue;
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            if (dx * dx + dz * dz <= rSq) return true;
        }
        return false;
    }

    private static WorldObject findNearbyWaypoint(Map<UUID, WorldObject> byId, int x, int z) {
        double rSq = FORK_MERGE_RADIUS * FORK_MERGE_RADIUS;
        for (WorldObject o : byId.values()) {
            if (typeOf(o) != KnotType.WAYPOINT) continue;
            BlockPos p = posOf(o);
            if (p == null) continue;
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            if (dx * dx + dz * dz <= rSq) return o;
        }
        return null;
    }

    private static WorldObject findOrCreateJunction(Map<UUID, WorldObject> byId,
                                                   WorldObjectManager manager,
                                                   JunctionBudget budget,
                                                   int x, int z, boolean bridge) {
        WorldObject existing = findNearbyWaypoint(byId, x, z);
        if (existing != null) return existing;
        if (!budget.consume()) return null;

        BlockPos pos = new BlockPos(x, 64, z);
        WorldObject junction = new WorldObject();
        junction.attach(new KnotMetaComponent(
                "Развилка [" + x + ", " + z + "]", JUNCTION_ICON, KnotType.WAYPOINT, pos));
        KnotConnectionComponent conn = new KnotConnectionComponent();
        junction.attach(conn);
        conn.investmentCost.set(0);
        junction.attach(new KnotRoutesComponent());
        manager.add(junction);
        byId.put(junction.id(), junction);

        if (bridge) {
            markJunctionBridge(junction);
        }
        return junction;
    }

    private static void markJunctionBridge(WorldObject junction) {
        junction.getSingle(KnotMetaComponent.class).ifPresent(meta -> {
            BlockPos p = meta.position.get();
            meta.position.set(new BlockPos(p.getX(), 64, p.getZ()));
        });
    }

    private static boolean splitEdgeViaSingle(EdgeView edge, WorldObject junction) {
        BlockPos jp = posOf(junction);
        if (jp == null) return false;
        int idx = nearestVertex(edge.pts(), jp.getX(), jp.getZ());
        if (idx <= 0 || idx >= edge.pts().size() - 1) return false;

        ObjectList<RoadPoint2D> part1 = new ObjectArrayList<>();
        for (int i = 0; i <= idx; i++) {
            appendDedup(part1, edge.pts().get(i));
        }
        appendDedup(part1, new RoadPoint2D(jp.getX(), jp.getZ(), edge.pts().get(idx).isBridge()));

        ObjectList<RoadPoint2D> part2 = new ObjectArrayList<>();
        appendDedup(part2, new RoadPoint2D(jp.getX(), jp.getZ(), edge.pts().get(idx).isBridge()));
        for (int i = idx + 1; i < edge.pts().size(); i++) {
            appendDedup(part2, edge.pts().get(i));
        }

        if (RouteBundler.calculateDistance(part1) < MIN_STUB_LEN
                || RouteBundler.calculateDistance(part2) < MIN_STUB_LEN) {
            return false;
        }

        RouteGenerator.disconnectKnots(edge.a(), edge.b());
        connectPair(edge.a(), junction, part1);
        connectPair(junction, edge.b(), part2);
        return true;
    }

    private static boolean splitEdgeViaJunctions(EdgeView edge, WorldObject j1, WorldObject j2) {
        BlockPos p1 = posOf(j1);
        BlockPos p2 = posOf(j2);
        if (p1 == null || p2 == null) return false;

        int i1 = nearestVertex(edge.pts(), p1.getX(), p1.getZ());
        int i2 = nearestVertex(edge.pts(), p2.getX(), p2.getZ());

        WorldObject first = j1;
        WorldObject second = j2;
        BlockPos fp = p1;
        BlockPos sp = p2;
        if (i1 > i2) {
            int t = i1;
            i1 = i2;
            i2 = t;
            first = j2;
            second = j1;
            fp = p2;
            sp = p1;
        }
        if (i2 - i1 < 1) {
            return splitEdgeViaSingle(edge, first);
        }

        ObjectList<RoadPoint2D> part1 = new ObjectArrayList<>();
        for (int i = 0; i <= i1; i++) {
            appendDedup(part1, edge.pts().get(i));
        }
        appendDedup(part1, new RoadPoint2D(fp.getX(), fp.getZ(), edge.pts().get(i1).isBridge()));

        ObjectList<RoadPoint2D> trunk = new ObjectArrayList<>();
        appendDedup(trunk, new RoadPoint2D(fp.getX(), fp.getZ(), edge.pts().get(i1).isBridge()));
        for (int i = i1 + 1; i < i2; i++) {
            appendDedup(trunk, edge.pts().get(i));
        }
        appendDedup(trunk, new RoadPoint2D(sp.getX(), sp.getZ(), edge.pts().get(i2).isBridge()));

        ObjectList<RoadPoint2D> part2 = new ObjectArrayList<>();
        appendDedup(part2, new RoadPoint2D(sp.getX(), sp.getZ(), edge.pts().get(i2).isBridge()));
        for (int i = i2 + 1; i < edge.pts().size(); i++) {
            appendDedup(part2, edge.pts().get(i));
        }

        if (RouteBundler.calculateDistance(part1) < MIN_STUB_LEN
                || RouteBundler.calculateDistance(part2) < MIN_STUB_LEN
                || RouteBundler.calculateDistance(trunk) < 8.0) {
            return false;
        }

        RouteGenerator.disconnectKnots(edge.a(), edge.b());
        connectPair(edge.a(), first, part1);
        if (!hasRoute(first, second.id()) && !hasRoute(second, first.id())) {
            connectPair(first, second, trunk);
        }
        connectPair(second, edge.b(), part2);
        return true;
    }

    // =====================================================================
    // Утилиты геометрии и графа
    // =====================================================================

    private static void connectPair(WorldObject x, WorldObject y, ObjectList<RoadPoint2D> ptsXY) {
        if (x == null || y == null || x.id().equals(y.id())) return;
        if (ptsXY == null || ptsXY.size() < 2) return;
        double dist = RouteBundler.calculateDistance(ptsXY);

        KnotConnectionComponent connX = x.getSingle(KnotConnectionComponent.class).orElseGet(() -> {
            KnotConnectionComponent c = new KnotConnectionComponent();
            x.attach(c);
            return c;
        });
        connX.addNeighbor(y.id());

        KnotConnectionComponent connY = y.getSingle(KnotConnectionComponent.class).orElseGet(() -> {
            KnotConnectionComponent c = new KnotConnectionComponent();
            y.attach(c);
            return c;
        });
        connY.addNeighbor(x.id());

        KnotRoutesComponent routesX = x.getSingle(KnotRoutesComponent.class).orElseGet(() -> {
            KnotRoutesComponent r = new KnotRoutesComponent();
            x.attach(r);
            return r;
        });
        routesX.addOrUpdateRoute(new KnotRouteEntry(y.id(), new ObjectArrayList<>(ptsXY), false, dist));

        KnotRoutesComponent routesY = y.getSingle(KnotRoutesComponent.class).orElseGet(() -> {
            KnotRoutesComponent r = new KnotRoutesComponent();
            y.attach(r);
            return r;
        });
        routesY.addOrUpdateRoute(new KnotRouteEntry(x.id(), reversedCopy(ptsXY), false, dist));
    }

    private static int nearestVertex(ObjectList<RoadPoint2D> pts, int x, int z) {
        int best = 0;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            double d = pts.get(i).distSqr(x, z);
            if (d < bestSq) {
                bestSq = d;
                best = i;
            }
        }
        return best;
    }

    private static double arcLengthTo(ObjectList<RoadPoint2D> pts, int segIdx, int projX, int projZ) {
        double len = 0.0;
        for (int i = 0; i < segIdx && i < pts.size() - 1; i++) {
            len += pts.get(i).distance(pts.get(i + 1));
        }
        if (segIdx >= 0 && segIdx < pts.size()) {
            len += pts.get(segIdx).distance(projX, projZ);
        }
        return len;
    }

    private static void appendDedup(ObjectList<RoadPoint2D> out, RoadPoint2D pt) {
        if (out.isEmpty()) {
            out.add(pt);
            return;
        }
        if (out.getLast().distSqr(pt) >= 4.0) {
            out.add(pt);
        }
    }

    private static boolean boxesOverlap(ObjectList<RoadPoint2D> a, ObjectList<RoadPoint2D> b, double pad) {
        int aMinX = Integer.MAX_VALUE;
        int aMaxX = Integer.MIN_VALUE;
        int aMinZ = Integer.MAX_VALUE;
        int aMaxZ = Integer.MIN_VALUE;
        for (int i = 0; i < a.size(); i++) {
            RoadPoint2D p = a.get(i);
            if (p.x() < aMinX) aMinX = p.x();
            if (p.x() > aMaxX) aMaxX = p.x();
            if (p.z() < aMinZ) aMinZ = p.z();
            if (p.z() > aMaxZ) aMaxZ = p.z();
        }
        int bMinX = Integer.MAX_VALUE;
        int bMaxX = Integer.MIN_VALUE;
        int bMinZ = Integer.MAX_VALUE;
        int bMaxZ = Integer.MIN_VALUE;
        for (int i = 0; i < b.size(); i++) {
            RoadPoint2D p = b.get(i);
            if (p.x() < bMinX) bMinX = p.x();
            if (p.x() > bMaxX) bMaxX = p.x();
            if (p.z() < bMinZ) bMinZ = p.z();
            if (p.z() > bMaxZ) bMaxZ = p.z();
        }
        return aMinX - pad <= bMaxX && bMinX - pad <= aMaxX
                && aMinZ - pad <= bMaxZ && bMinZ - pad <= aMaxZ;
    }

    private static int pruneOrphanJunctions(Map<UUID, WorldObject> byId, WorldObjectManager manager) {
        int removed = 0;
        for (WorldObject o : new ArrayList<>(byId.values())) {
            if (typeOf(o) != KnotType.WAYPOINT) continue;
            boolean hasNeighbors = o.getSingle(KnotConnectionComponent.class)
                    .map(c -> !c.neighbors.get().isEmpty()).orElse(false);
            boolean hasRoutes = o.getSingle(KnotRoutesComponent.class)
                    .map(r -> !r.routes.get().isEmpty()).orElse(false);
            if (!hasNeighbors && !hasRoutes) {
                if (manager.remove(o.id())) {
                    byId.remove(o.id());
                    removed++;
                }
            }
        }
        return removed;
    }
}
