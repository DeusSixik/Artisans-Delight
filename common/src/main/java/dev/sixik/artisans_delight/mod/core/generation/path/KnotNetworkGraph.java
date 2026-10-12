package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Топологический решатель сети узлов мира (Node Graph Network).
 * Строит реалистичный планарный граф дорог (RNG / Gabriel Graph / Spanner),
 * предотвращая прямые соединения в обход промежуточных деревень и узлов.
 *
 * Пример: если есть деревни A (0 км), B (1 км) и C (2 км), сеть формирует цепочку A -> B -> C,
 * исключая ложное прямое соединение A -> C через промежуточный узел B.
 */
public final class KnotNetworkGraph {

    public static final double MAX_CONNECTION_DISTANCE = 4000.0;

    private KnotNetworkGraph() {
    }

    public record GraphEdge(WorldObject a, WorldObject b, double distance) {
        public String key() {
            return a.id().compareTo(b.id()) < 0 ? a.id() + "_" + b.id() : b.id() + "_" + a.id();
        }
    }

    /**
     * Проверка, является ли узел C промежуточным между узлами A и B.
     * Если C лежит между A и B, прямое соединение A-B запрещено, так как путь должен
     * проходить последовательно через C (A -> C -> B).
     */
    public static boolean isIntermediateNode(BlockPos posA, BlockPos posB, BlockPos posC) {
        if (posA.equals(posC) || posB.equals(posC)) {
            return false;
        }

        double abX = (double) posB.getX() - posA.getX();
        double abZ = (double) posB.getZ() - posA.getZ();
        double distSqAB = abX * abX + abZ * abZ;
        if (distSqAB < 16.0) return false;

        double distAB = Math.sqrt(distSqAB);

        double acX = (double) posC.getX() - posA.getX();
        double acZ = (double) posC.getZ() - posA.getZ();
        double distSqAC = acX * acX + acZ * acZ;

        double bcX = (double) posC.getX() - posB.getX();
        double bcZ = (double) posC.getZ() - posB.getZ();
        double distSqBC = bcX * bcX + bcZ * bcZ;

        // 1. Узел C не может быть промежуточным, если он дальше от A или от B, чем само расстояние AB
        if (distSqAC >= distSqAB || distSqBC >= distSqAB) {
            return false;
        }

        double distAC = Math.sqrt(distSqAC);
        double distBC = Math.sqrt(distSqBC);

        // 2. Коридор / Spanner: путь через C (A -> C -> B) удлиняет прямое расстояние не более чем на 28%.
        // Это покрывает любые деревни и узлы, находящиеся между двумя точками.
        if ((distAC + distBC) <= distAB * 1.28) {
            return true;
        }

        // 3. Gabriel Disk: C лежит строго внутри круга диаметра AB (угол ACB > 90 градусов)
        if (distSqAC + distSqBC < distSqAB) {
            double dot = acX * abX + acZ * abZ;
            double u = dot / distSqAB;
            if (u > 0.05 && u < 0.95) {
                return true;
            }
        }

        return false;
    }

    /**
     * Проверка геометрического пересечения двух отрезков в 2D (на плоскости карты).
     */
    public static boolean doSegmentsIntersect(BlockPos a1, BlockPos a2, BlockPos b1, BlockPos b2) {
        if (a1.equals(b1) || a1.equals(b2) || a2.equals(b1) || a2.equals(b2)) {
            return false; // Общая вершина
        }

        double d1x = a2.getX() - a1.getX();
        double d1z = a2.getZ() - a1.getZ();
        double d2x = b2.getX() - b1.getX();
        double d2z = b2.getZ() - b1.getZ();

        double cross = d1x * d2z - d1z * d2x;
        if (Math.abs(cross) < 1e-5) return false;

        double dx = b1.getX() - a1.getX();
        double dz = b1.getZ() - a1.getZ();

        double t = (dx * d2z - dz * d2x) / cross;
        double u = (dx * d1z - dz * d1x) / cross;

        return (t > 0.01 && t < 0.99 && u > 0.01 && u < 0.99);
    }

    /**
     * Расчет эталонной топологии графа для заданной коллекции узлов.
     * Гарантирует отсутствие прямых связей в обход промежуточных узлов и исключает пересечения дорог.
     *
     * <p>WAYPOINT-развилки в топологии не участвуют: их связями владеет {@link JunctionEngine}.
     * Иначе планарный фильтр пытался бы строить A* между городом и чужой развилкой
     * и воссоздавал бы прямые ребра поверх разрезов.
     */
    public static List<GraphEdge> calculateTopology(Collection<WorldObject> knots) {
        if (knots == null || knots.size() < 2) return List.of();

        List<WorldObject> knotList = new ArrayList<>();
        Map<UUID, BlockPos> posMap = new HashMap<>();
        for (WorldObject obj : knots) {
            if (isWaypoint(obj)) continue;
            obj.getSingle(KnotMetaComponent.class).ifPresent(m -> {
                knotList.add(obj);
                posMap.put(obj.id(), m.position.get());
            });
        }

        if (knotList.size() < 2) return List.of();

        // 1. Собираем всех кандидатов на соединение в радиусе до MAX_CONNECTION_DISTANCE
        List<GraphEdge> candidates = new ArrayList<>();

        for (int i = 0; i < knotList.size(); i++) {
            WorldObject oA = knotList.get(i);
            BlockPos pA = posMap.get(oA.id());
            for (int j = i + 1; j < knotList.size(); j++) {
                WorldObject oB = knotList.get(j);
                BlockPos pB = posMap.get(oB.id());

                double dist = Math.hypot(pA.getX() - pB.getX(), pA.getZ() - pB.getZ());
                if (dist <= MAX_CONNECTION_DISTANCE) {
                    candidates.add(new GraphEdge(oA, oB, dist));
                }
            }
        }

        // Сортируем кандидатов по возрастанию расстояния (сначала самые короткие и локальные связи)
        candidates.sort(Comparator.comparingDouble(GraphEdge::distance));

        // 2. Фильтрация промежуточных узлов (RNG + Gabriel Disk + Spanner)
        List<GraphEdge> nonIntermediate = new ArrayList<>();
        for (GraphEdge edge : candidates) {
            BlockPos pA = posMap.get(edge.a().id());
            BlockPos pB = posMap.get(edge.b().id());

            boolean hasIntermediate = false;
            for (WorldObject other : knotList) {
                if (other == edge.a() || other == edge.b()) continue;
                BlockPos pC = posMap.get(other.id());
                if (isIntermediateNode(pA, pB, pC)) {
                    hasIntermediate = true;
                    break;
                }
            }

            if (!hasIntermediate) {
                nonIntermediate.add(edge);
            }
        }

        // 3. Планарная фильтрация (устранение пересечений отрезков в 2D)
        List<GraphEdge> planar = new ArrayList<>();
        for (GraphEdge edge : nonIntermediate) {
            BlockPos pA = posMap.get(edge.a().id());
            BlockPos pB = posMap.get(edge.b().id());

            boolean intersectsShorter = false;
            for (GraphEdge accepted : planar) {
                BlockPos a1 = posMap.get(accepted.a().id());
                BlockPos a2 = posMap.get(accepted.b().id());
                if (doSegmentsIntersect(pA, pB, a1, a2)) {
                    intersectsShorter = true;
                    break;
                }
            }

            if (!intersectsShorter) {
                planar.add(edge);
            }
        }

        return planar;
    }

    /**
     * Подключение нового сгенерированного узла (например, деревни при генерации чанка) к сети мира.
     * Если новый узел оказался прямо между ранее соединенными узлами A и B, старая прямая связь A-B
     * автоматически разрывается, и создаются два новых звена A -> newKnot и newKnot -> B.
     */
    public static void connectNewKnot(
            WorldObject newKnot, WorldObjectManager manager,
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState) {

        if (newKnot == null || manager == null) return;
        KnotMetaComponent newMeta = newKnot.getSingle(KnotMetaComponent.class).orElse(null);
        if (newMeta == null) return;

        BlockPos newPos = newMeta.position.get();
        Collection<WorldObject> allKnots = manager.all();

        // 1. Проверяем все существующие соединения. Если newKnot встал прямо между A и B, разрываем прямую связь A-B!
        // Ребра с WAYPOINT-развилками не трогаем: ими владеет JunctionEngine.
        for (WorldObject oA : allKnots) {
            if (oA == newKnot) continue;
            KnotConnectionComponent connA = oA.getSingle(KnotConnectionComponent.class).orElse(null);
            KnotMetaComponent metaA = oA.getSingle(KnotMetaComponent.class).orElse(null);
            if (connA == null || metaA == null) continue;

            for (UUID neighborId : new ArrayList<>(connA.neighbors.get())) {
                if (neighborId.equals(newKnot.id())) continue;
                WorldObject oB = manager.get(neighborId).orElse(null);
                if (oB == null || oB == newKnot) continue;
                KnotMetaComponent metaB = oB.getSingle(KnotMetaComponent.class).orElse(null);
                if (metaB == null) continue;
                if (isWaypoint(oA) || isWaypoint(oB)) continue;

                // Проверяем, встал ли newKnot между oA и oB
                if (isIntermediateNode(metaA.position.get(), metaB.position.get(), newPos)) {
                    RouteGenerator.disconnectKnots(oA, oB);
                }
            }
        }

        // 1.5 Целевая протяжка существующих дорог через новую деревню.
        // Дороги, построенные раньше, чем деревня сгенерировалась, прошли мимо
        // неё и оставались «мимо» навсегда — теперь разрезаются и заходят в город.
        if (newMeta.type.get() == KnotType.TOWN) {
            try {
                JunctionEngine.snapEdgesViaTown(newKnot, manager);
            } catch (Throwable ignored) {
            }
        }

        // 2. Находим, с какими узлами должен соединиться newKnot согласно топологии графа
        List<GraphEdge> validTopology = calculateTopology(allKnots);
        List<CompletableFuture<Void>> routeFutures = new ArrayList<>();
        for (GraphEdge edge : validTopology) {
            if (edge.a().id().equals(newKnot.id()) || edge.b().id().equals(newKnot.id())) {
                CompletableFuture<Void> future = RouteGenerator.connectKnotsAsync(edge.a(), edge.b(), generator, heightAccessor, randomState, manager);
                routeFutures.add(future);
            }
        }
        
        // Запускаем resolveNetwork после завершения всех маршрутов (через 3 секунды)
        if (!routeFutures.isEmpty() && manager != null) {
            CompletableFuture.allOf(routeFutures.toArray(new CompletableFuture[0]))
                .thenRunAsync(() -> {
                    try {
                        Thread.sleep(3000); // Даём время на завершение всех параллельных маршрутов
                        JunctionEngine.resolveNetwork(allKnots, manager);
                    } catch (Throwable ignored) {}
                }, RouteGenerator.ROUTE_EXECUTOR);
        }
    }

    /**
     * Полный пересчет сети узлов мира: удаляет любые устаревшие прямые срезки и строит правильный граф.
     */
    public static void rebuildNetwork(
            Collection<WorldObject> knots,
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState) {
        rebuildNetwork(knots, generator, heightAccessor, randomState, null, 0L);
    }

    /**
     * Полный пересчет сети узлов мира: удаляет любые устаревшие прямые срезки и строит правильный граф.
     *
     * @param manager менеджер мира для создания связующих узлов-развилок
     *                (см. {@link JunctionEngine}); может быть {@code null}.
     */
    public static void rebuildNetwork(
            Collection<WorldObject> knots,
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState,
            WorldObjectManager manager) {
        rebuildNetwork(knots, generator, heightAccessor, randomState, manager, 0L);
    }

    /**
     * Полный пересчет сети узлов мира с указанием сида мира.
     *
     * @param worldSeed сид мира (для детерминированности)
     */
    public static void rebuildNetwork(
            Collection<WorldObject> knots,
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState,
            WorldObjectManager manager,
            long worldSeed) {

        if (knots == null || knots.size() < 2) return;
        
        // ===== НОВАЯ ДВУХСЛОЙНАЯ СИСТЕМА ГЕНЕРАЦИИ =====
        // Включаем новую систему ТОЛЬКО с грубой сеткой (без детального A*)
        if (manager != null && worldSeed != 0L) {
            try {
                System.out.println("[KnotNetworkGraph] Активирую новую систему генерации узлов...");
                dev.sixik.artisans_delight.mod.core.generation.grid.NetworkMigrationAdapter.buildNetworkV2(
                    knots, manager, worldSeed, generator, heightAccessor, randomState
                );
                return; // Новая система обработала всё
            } catch (Throwable e) {
                e.printStackTrace();
                System.err.println("[KnotNetworkGraph] ОШИБКА в новой системе! Откатываемся на старую...");
                // Фолбэк на старую систему при ошибке
            }
        }
        // ================================================

        // 1. Вычисляем эталонную топологию графа
        List<GraphEdge> validTopology = calculateTopology(knots);
        Set<String> validEdgeKeys = new HashSet<>();
        for (GraphEdge e : validTopology) {
            validEdgeKeys.add(e.key());
        }

        Map<UUID, WorldObject> knotMap = new HashMap<>();
        for (WorldObject obj : knots) {
            knotMap.put(obj.id(), obj);
        }

        // 2. Удаляем все существующие связи, которые не входят в правильную топологию (лишние прямые срезки).
        // Связи через WAYPOINT-развилки ("A-J", "J-B") сохраняем всегда: топология строится
        // только по TOWN/PRODUCTION-узлам и не знает о связующих развилках.
        for (WorldObject objA : knots) {
            KnotConnectionComponent connA = objA.getSingle(KnotConnectionComponent.class).orElse(null);
            if (connA == null) continue;

            for (UUID neighborId : new ArrayList<>(connA.neighbors.get())) {
                WorldObject objB = knotMap.get(neighborId);
                if (objB == null) continue;

                if (isWaypoint(objA) || isWaypoint(objB)) {
                    continue;
                }

                String edgeKey = objA.id().compareTo(neighborId) < 0
                        ? objA.id() + "_" + neighborId
                        : neighborId + "_" + objA.id();

                if (!validEdgeKeys.contains(edgeKey)) {
                    RouteGenerator.disconnectKnots(objA, objB);
                }
            }
        }

        // 3. Строим недостающие правильные маршруты топологии.
        // Ребро A-B пропускаем, если города уже связаны через WAYPOINT-развилки
        // (A-J1-J2-B): иначе воссоздадим прямую дорогу-призрак поверх разреза.
        for (GraphEdge edge : validTopology) {
            boolean hasRoute = edge.a().getSingle(KnotRoutesComponent.class)
                    .flatMap(r -> r.getRouteTo(edge.b().id()))
                    .isPresent();

            if (!hasRoute && !sharesJunctionPath(edge.a(), edge.b(), knotMap)) {
                RouteGenerator.connectKnotsAsync(edge.a(), edge.b(), generator, heightAccessor, randomState, manager);
            }
        }

        // 4. Для уже готовых маршрутов создаем явные связующие узлы-развилки (WAYPOINT)
        // в местах схождения дорог: протяжки через попутные деревни и развилки
        // на общих коридорах вне городов. Новые A* не запускаются — только хирургия полилиний.
        if (manager != null) {
            try {
                JunctionEngine.resolveNetwork(knots, manager);
            } catch (Throwable ignored) {
            }
        }

        // 5. Для уже существующих маршрутов выполняем объединение сходящихся коридоров и развилок
        RouteBundler.bundleAllRoutes(knots);
    }

    private static boolean isWaypoint(WorldObject o) {
        if (o == null) return false;
        return o.getSingle(KnotMetaComponent.class)
                .map(m -> m.type.get() == KnotType.WAYPOINT)
                .orElse(false);
    }

    /**
     * Проверка, связаны ли два узла через цепочку WAYPOINT-развилок
     * (общий сосед-J либо ствол J1-J2: A-J1, J1-J2, J2-B).
     *
     * <p>Обход в ширину строго по WAYPOINT-звеньям глубиной до 4: после
     * нескольких проходов {@link JunctionEngine#resolveNetwork} между городами
     * образуются длинные стволы (A-J1-J2-J3-B), которые проверка «в лоб»
     * не видела и запускала повторный A*, кладя прямую дорогу-призрак
     * поверх разрезанного коридора.
     */
    private static boolean sharesJunctionPath(WorldObject a, WorldObject b, Map<UUID, WorldObject> knotMap) {
        if (a == null || b == null) return false;
        KnotConnectionComponent connB = b.getSingle(KnotConnectionComponent.class).orElse(null);
        if (connB == null) return false;

        Set<UUID> bWaypoints = new HashSet<>();
        for (UUID n : connB.neighbors.get()) {
            WorldObject o = knotMap.get(n);
            if (o != null && isWaypoint(o)) bWaypoints.add(n);
        }
        if (bWaypoints.isEmpty()) return false;

        KnotConnectionComponent connA = a.getSingle(KnotConnectionComponent.class).orElse(null);
        if (connA == null) return false;

        Set<UUID> visited = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>();
        for (UUID n : connA.neighbors.get()) {
            WorldObject o = knotMap.get(n);
            if (o != null && isWaypoint(o)) {
                if (bWaypoints.contains(n)) return true;
                queue.add(n);
                visited.add(n);
            }
        }
        if (queue.isEmpty()) return false;

        int depth = 1;
        while (!queue.isEmpty() && depth <= 4) {
            int levelSize = queue.size();
            for (int s = 0; s < levelSize; s++) {
                UUID j = queue.poll();
                WorldObject oJ = knotMap.get(j);
                if (oJ == null) continue;
                KnotConnectionComponent connJ = oJ.getSingle(KnotConnectionComponent.class).orElse(null);
                if (connJ == null) continue;
                for (UUID next : connJ.neighbors.get()) {
                    if (!visited.add(next)) continue;
                    if (bWaypoints.contains(next)) return true;
                    WorldObject oNext = knotMap.get(next);
                    if (oNext != null && isWaypoint(oNext)) {
                        queue.add(next);
                    }
                }
            }
            depth++;
        }
        return false;
    }
}
