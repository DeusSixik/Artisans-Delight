package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Движок локальной динамической перестройки дорог и огибания препятствий (Route Detour & Splice Engine).
 *
 * Если на участке дороги возникает препятствие (лагерь разбойников, обвал, блокпост или ивент из аддона),
 * движок НЕ пересчитывает весь 2000-блочный маршрут, а вычисляет аккуратный локальный объезд (detour arc)
 * строго вокруг препятствия и бесшовно вшивает его в существующую полилинию дороги.
 *
 * При этом 95% дороги остаётся неизменной, предотвращая появление дорог-призраков и сохраняя
 * ранее сгенерированные блоки в мире.
 */
public final class RouteDetourEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDetourEngine.class);

    private RouteDetourEngine() {
    }

    public record DetourResult(
            KnotRouteEntry updatedRoute,
            int anchorInIndex,
            int anchorOutIndex,
            double addedDistance
    ) {
    }

    /**
     * Проверка, пересекает ли дорога зону действия препятствия.
     */
    public static boolean doesRouteIntersectObstacle(KnotRouteEntry route, RoadObstacle obstacle) {
        if (route == null || obstacle == null || route.waypoints().size() < 2) return false;

        ObjectList<RoadPoint2D> pts = route.waypoints();
        double radius = obstacle.radius() + 4.0;
        int ox = obstacle.center().getX();
        int oz = obstacle.center().getZ();

        for (int i = 0; i < pts.size() - 1; i++) {
            RoadPoint2D p1 = pts.get(i);
            RoadPoint2D p2 = pts.get(i + 1);

            RouteBundler.ProjectionResult proj = RouteBundler.projectPointOnSegment(
                    ox, oz, p1.x(), p1.z(), p2.x(), p2.z()
            );

            if (proj.dist <= radius) {
                return true;
            }
        }

        return false;
    }

    /**
     * Попытка перестроить локальный участок дороги в обход препятствия.
     * Возвращает DetourResult с обновленной записью маршрута или null, если дорога не затронута
     * либо объезд не удалось проложить.
     */
    public static DetourResult tryRerouteAroundObstacle(
            KnotRouteEntry route,
            RoadObstacle obstacle,
            ChunkGenerator generator,
            LevelHeightAccessor heightAccessor,
            RandomState randomState) {

        if (route == null || obstacle == null || route.waypoints().size() < 2) {
            return null;
        }

        ObjectList<RoadPoint2D> pts = route.waypoints();
        double radius = obstacle.radius() + 4.0;
        int ox = obstacle.center().getX();
        int oz = obstacle.center().getZ();

        int firstAffectedSeg = -1;
        int lastAffectedSeg = -1;

        for (int i = 0; i < pts.size() - 1; i++) {
            RoadPoint2D p1 = pts.get(i);
            RoadPoint2D p2 = pts.get(i + 1);

            RouteBundler.ProjectionResult proj = RouteBundler.projectPointOnSegment(
                    ox, oz, p1.x(), p1.z(), p2.x(), p2.z()
            );

            if (proj.dist <= radius) {
                if (firstAffectedSeg == -1) {
                    firstAffectedSeg = i;
                }
                lastAffectedSeg = i;
            }
        }

        // Препятствие не пересекает дорогу
        if (firstAffectedSeg == -1) {
            return null;
        }

        // Выбираем опорную точку входа Pin (безопасное расстояние до препятствия)
        int inIdx = firstAffectedSeg;
        double safeDistanceSq = (obstacle.radius() + 32.0) * (obstacle.radius() + 32.0);
        while (inIdx > 0 && pts.get(inIdx).distSqr(ox, oz) < safeDistanceSq) {
            inIdx--;
        }

        // Выбираем опорную точку выхода Pout
        int outIdx = Math.min(pts.size() - 1, lastAffectedSeg + 1);
        while (outIdx < pts.size() - 1 && pts.get(outIdx).distSqr(ox, oz) < safeDistanceSq) {
            outIdx++;
        }

        if (inIdx >= outIdx) {
            return null;
        }

        RoadPoint2D pIn = pts.get(inIdx);
        RoadPoint2D pOut = pts.get(outIdx);

        // Ограничение для A*: запрет входа в зону препятствия
        final double impassableRadius = obstacle.radius() + 6.0;
        final double warningRadius = obstacle.radius() + 24.0;
        final double impassableSq = impassableRadius * impassableRadius;
        final double warningSq = warningRadius * warningRadius;

        CoarsePathfinder.PathConstraint obstacleConstraint = (x, z) -> {
            double dx = (double) ox - x;
            double dz = (double) oz - z;
            double distSq = dx * dx + dz * dz;

            if (distSq <= impassableSq) {
                return Double.POSITIVE_INFINITY;
            }
            if (distSq <= warningSq) {
                return 400.0; // Штрафная буферная зона
            }
            return 0.0;
        };

        // Запускаем локальный поиск пути строго между Pin и Pout
        ObjectList<RoadPoint2D> detourArc = CoarsePathfinder.findLandRoad(
                generator, heightAccessor, randomState,
                pIn.x(), pIn.z(), pOut.x(), pOut.z(),
                obstacleConstraint
        );

        if (detourArc.isEmpty()) {
            LOGGER.warn("RouteDetourEngine: Не удалось найти объезд препятствия {} между {} и {}",
                    obstacle.id(), pIn, pOut);
            return null;
        }

        // Вшиваем объездную дугу в оригинальный маршрут (Splice)
        ObjectList<RoadPoint2D> spliced = new ObjectArrayList<>();

        // 1. Неизменная часть до Pin
        for (int i = 0; i <= inIdx; i++) {
            spliced.add(pts.get(i));
        }

        // 2. Объездная дуга вокруг препятствия
        for (int i = 1; i < detourArc.size() - 1; i++) {
            RoadPoint2D dPt = detourArc.get(i);
            if (spliced.getLast().distSqr(dPt) > 16.0) {
                spliced.add(dPt);
            }
        }

        // 3. Неизменная часть после Pout
        for (int i = outIdx; i < pts.size(); i++) {
            RoadPoint2D remPt = pts.get(i);
            if (spliced.getLast().distSqr(remPt) > 16.0) {
                spliced.add(remPt);
            }
        }

        double newDistance = RouteBundler.calculateDistance(spliced);
        KnotRouteEntry updatedRoute = new KnotRouteEntry(
                route.targetKnotId(), spliced, route.isSeaRoute(), newDistance
        );

        LOGGER.info("RouteDetourEngine: Успешно построен локальный объезд препятствия {}! Было точек: {}, стало: {}",
                obstacle.id(), pts.size(), spliced.size());

        return new DetourResult(updatedRoute, inIdx, outIdx, newDistance - route.distance());
    }

    /**
     * Применение препятствия ко всей сети узлов мира.
     * Находит все пересекаемые дороги, строит локальные объезды и синхронно обновляет
     * как прямой, так и обратный маршруты.
     *
     * @return количество перестроенных маршрутов
     */
    public static int applyObstacleToNetwork(
            Collection<WorldObject> knots,
            RoadObstacle obstacle,
            ChunkGenerator generator,
            LevelHeightAccessor heightAccessor,
            RandomState randomState) {

        if (knots == null || knots.isEmpty() || obstacle == null) return 0;

        Map<UUID, WorldObject> knotMap = new HashMap<>();
        for (WorldObject k : knots) {
            knotMap.put(k.id(), k);
        }

        int affectedCount = 0;
        Set<String> processedEdges = new HashSet<>();

        for (WorldObject knotA : knots) {
            KnotRoutesComponent routesA = knotA.getSingle(KnotRoutesComponent.class).orElse(null);
            if (routesA == null) continue;

            for (KnotRouteEntry route : new ArrayList<>(routesA.routes.get())) {
                String edgeKey = knotA.id().compareTo(route.targetKnotId()) < 0
                        ? knotA.id() + "_" + route.targetKnotId()
                        : route.targetKnotId() + "_" + knotA.id();

                if (processedEdges.contains(edgeKey)) continue;

                if (doesRouteIntersectObstacle(route, obstacle)) {
                    DetourResult result = tryRerouteAroundObstacle(
                            route, obstacle, generator, heightAccessor, randomState
                    );

                    if (result != null) {
                        processedEdges.add(edgeKey);
                        affectedCount++;

                        routesA.addOrUpdateRoute(result.updatedRoute());

                        // Синхронно обновляем обратный путь в целевом узле
                        WorldObject knotB = knotMap.get(route.targetKnotId());
                        if (knotB == null) {
                            knotB = WorldObjectManagerApi.findObject(route.targetKnotId()).orElse(null);
                        }

                        if (knotB != null) {
                            knotB.getSingle(KnotRoutesComponent.class).ifPresent(routesB -> {
                                ObjectList<RoadPoint2D> rev = new ObjectArrayList<>(result.updatedRoute().waypoints());
                                Collections.reverse(rev);
                                routesB.addOrUpdateRoute(new KnotRouteEntry(
                                        knotA.id(), rev, route.isSeaRoute(), result.updatedRoute().distance()
                                ));
                            });
                        }
                    }
                }
            }
        }

        return affectedCount;
    }

    /**
     * Восстановление базовых исходных маршрутов после устранения препятствия.
     */
    public static int restoreNetworkRoutes(Collection<WorldObject> knots) {
        if (knots == null || knots.isEmpty()) return 0;

        int restored = 0;
        for (WorldObject knot : knots) {
            KnotRoutesComponent routes = knot.getSingle(KnotRoutesComponent.class).orElse(null);
            if (routes == null) continue;

            for (KnotRouteEntry base : new ArrayList<>(routes.baselineRoutes.get())) {
                if (routes.restoreBaselineRoute(base.targetKnotId())) {
                    restored++;
                }
            }
        }

        return restored;
    }
}
