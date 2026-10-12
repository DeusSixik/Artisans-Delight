package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import dev.sixik.artisans_delight.mod.core.generation.grid.CoarseGraphBuilder;
import dev.sixik.artisans_delight.mod.core.generation.grid.SectorWaypointPicker;
import dev.sixik.artisans_delight.mod.core.generation.path.CoarsePathfinder;
import dev.sixik.artisans_delight.mod.core.generation.path.GenerationProgress;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Чистый расчёт виртуальной дорожной сети.
 *
 * <p>Ничего не пишет в мир: не создаёт {@code WorldObject}, не трогает
 * менеджер, не ставит блоки. Результат — {@link RoadNetwork}, который
 * публикуется в {@link RoadNetworkStore} и читается картой.
 *
 * <p><b>Алгоритм</b> (перенесён из {@code CoarseGraphBuilder} +
 * {@code NetworkMigrationAdapter.buildFineLayerGeometry}):
 * <ol>
 *   <li>MST Прима между городами.</li>
 *   <li>Для каждого ребра MST — грубая дорога ({@code COARSE_GRID_STEP=48}),
 *       вдоль неё цепочка промежуточных узлов (рандом [3..max] на длинных,
 *       1-2 на близких, {@code NODE_SPACING=400}).</li>
 *   <li>Геометрия каждого сегмента цепочки — {@code CoarsePathfinder.findLandRoad}
 *       (мосты, обход воды). Прямая линия — только fallback.</li>
 * </ol>
 *
 * <p><b>Детерминированность:</b> при тех же городах и сиде результат
 * побитово тот же (id городов — от сида+позиции, порядок узлов — от MST,
 * рандом — от сида+хэшей id).
 */
public final class RoadNetworkBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadNetworkBuilder.class);

    private RoadNetworkBuilder() {
    }

    /**
     * Входной город для расчёта: минимум данных, без привязки к миру.
     *
     * @param id       детерминированный id узла в сети (от сида+позиции)
     * @param sourceId UUID игрового объекта города (для связки с миром) или {@code null}
     */
    public record TownSeed(UUID id, UUID sourceId, String name, int x, int y, int z) {
        public TownSeed(UUID id, String name, int x, int y, int z) {
            this(id, null, name, x, y, z);
        }
    }

    /**
     * Детерминированный id города от сида мира и координат.
     * Повторный расчёт для того же мира даёт те же id.
     */
    public static UUID townId(long worldSeed, int x, int z) {
        String src = worldSeed + ":" + x + "," + z;
        return UUID.nameUUIDFromBytes(src.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Детерминированный id промежуточного узла от сида и порядкового номера.
     */
    public static UUID waypointId(long worldSeed, int index) {
        String src = worldSeed + ":waypoint:" + index;
        return UUID.nameUUIDFromBytes(src.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Строит сеть для городов.
     *
     * @param towns      города (позиции и имена)
     * @param worldSeed  сид мира
     * @param generator  генератор мира (только чтение высот/биомов)
     * @param height     границы высоты мира
     * @param randomState состояние шума
     * @return посчитанная сеть (может быть пустой, если городов &lt; 2)
     */
    public static RoadNetwork build(Collection<TownSeed> towns,
                                    long worldSeed,
                                    ChunkGenerator generator,
                                    LevelHeightAccessor height,
                                    RandomState randomState) {
        GenerationProgress.begin();

        List<TownSeed> townList = towns == null ? List.of() : new ArrayList<>(towns);
        if (townList.size() < 2) {
            LOGGER.warn("[RoadNetworkBuilder] Недостаточно городов для построения сети: {}", townList.size());
            GenerationProgress.finish();
            return RoadNetwork.empty(worldSeed);
        }

        Map<UUID, RoadNode> nodes = new LinkedHashMap<>();
        for (TownSeed t : townList) {
            nodes.put(t.id(), new RoadNode(t.id(), RoadNode.Kind.TOWN, t.name(), t.x(), t.y(), t.z(), t.sourceId()));
        }

        // 1. MST между городами
        GenerationProgress.setStage(GenerationProgress.Stage.COARSE_GRAPH);
        List<TownSeed[]> mstEdges = calculateMst(townList);
        LOGGER.info("[RoadNetworkBuilder] MST построен: {} рёбер для {} городов", mstEdges.size(), townList.size());

        // 2. Цепочки узлов + соседства (топология)
        // key ребра -> упорядоченная цепочка id узлов (включая оба города)
        List<List<UUID>> chains = new ArrayList<>();
        int waypointIndex = 0;
        for (int i = 0; i < mstEdges.size(); i++) {
            TownSeed a = mstEdges.get(i)[0];
            TownSeed b = mstEdges.get(i)[1];
            GenerationProgress.setProgress(i + 1, mstEdges.size(), a.name() + " -> " + b.name());

            ChainResult chain = createNodeChain(a, b, nodes, worldSeed, generator, height, randomState, waypointIndex);
            waypointIndex += chain.newWaypoints();
            if (chain.nodeIds().size() >= 2) {
                chains.add(chain.nodeIds());
            }
        }

        // 3. Геометрия каждого сегмента цепочки
        GenerationProgress.setStage(GenerationProgress.Stage.FINE_GEOMETRY);
        Map<String, RoadEdge> edges = new LinkedHashMap<>();
        int totalSegments = 0;
        for (List<UUID> chain : chains) {
            totalSegments += chain.size() - 1;
        }
        int doneSegments = 0;
        for (List<UUID> chain : chains) {
            for (int i = 0; i < chain.size() - 1; i++) {
                RoadNode from = nodes.get(chain.get(i));
                RoadNode to = nodes.get(chain.get(i + 1));
                if (from == null || to == null) continue;

                doneSegments++;
                RoadEdge edge = buildEdge(from, to, worldSeed, generator, height, randomState);
                edges.put(edge.key(), edge);
                GenerationProgress.setProgress(doneSegments, totalSegments, (int) edge.distance() + "м");
            }
        }

        GenerationProgress.finish();
        RoadNetwork network = new RoadNetwork(worldSeed, nodes, edges);
        LOGGER.info("[RoadNetworkBuilder] Сеть посчитана: {} узлов, {} рёбер",
            network.nodeCount(), network.edgeCount());
        return network;
    }

    // =========================================================================
    // Топология: MST + цепочки
    // =========================================================================

    private static List<TownSeed[]> calculateMst(List<TownSeed> towns) {
        List<TownSeed[]> result = new ArrayList<>();
        Set<TownSeed> visited = new HashSet<>();
        PriorityQueue<MstEdge> pq = new PriorityQueue<>(Comparator.comparingDouble(e -> e.distance));

        TownSeed start = towns.get(0);
        visited.add(start);
        for (TownSeed t : towns) {
            if (!t.equals(start)) {
                pq.add(new MstEdge(start, t, dist(start, t)));
            }
        }

        while (!pq.isEmpty() && visited.size() < towns.size()) {
            MstEdge edge = pq.poll();
            if (visited.contains(edge.b)) continue;
            result.add(new TownSeed[]{edge.a, edge.b});
            visited.add(edge.b);
            for (TownSeed t : towns) {
                if (!visited.contains(t)) {
                    pq.add(new MstEdge(edge.b, t, dist(edge.b, t)));
                }
            }
        }
        return result;
    }

    private record MstEdge(TownSeed a, TownSeed b, double distance) {
    }

    private static double dist(TownSeed a, TownSeed b) {
        double dx = (double) a.x() - b.x();
        double dz = (double) a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private record ChainResult(List<UUID> nodeIds, int newWaypoints) {
    }

    /**
     * Создаёт цепочку промежуточных узлов между двумя городами вдоль грубой дороги.
     * Логика — 1:1 из {@code CoarseGraphBuilder.createNodeChain}.
     */
    private static ChainResult createNodeChain(TownSeed townA,
                                               TownSeed townB,
                                               Map<UUID, RoadNode> nodes,
                                               long worldSeed,
                                               ChunkGenerator generator,
                                               LevelHeightAccessor height,
                                               RandomState randomState,
                                               int startIndex) {
        ObjectList<RoadPoint2D> roadPath = CoarsePathfinder.findCoarseLandRoad(
            generator, height, randomState,
            townA.x(), townA.z(), townB.x(), townB.z(),
            CoarseGraphBuilder.COARSE_GRID_STEP
        );

        if (roadPath.isEmpty()) {
            LOGGER.warn("[RoadNetworkBuilder] Нет сухопутной дороги '{}' -> '{}', пропускаю",
                townA.name(), townB.name());
            return new ChainResult(List.of(), 0);
        }

        double roadLength = 0.0;
        for (int i = 0; i < roadPath.size() - 1; i++) {
            roadLength += roadPath.get(i).distance(roadPath.get(i + 1));
        }

        int maxNodes = (int) Math.ceil(roadLength / CoarseGraphBuilder.NODE_SPACING);
        Random random = new Random(worldSeed ^ ((long) townA.id().hashCode() * 31L + townB.id().hashCode()));

        int nodeCount;
        if (maxNodes <= CoarseGraphBuilder.RANDOMIZE_THRESHOLD) {
            nodeCount = Math.max(CoarseGraphBuilder.MIN_NODES_BETWEEN_TOWNS, maxNodes);
        } else {
            int minNodes = CoarseGraphBuilder.MIN_NODES_LONG_ROUTE;
            nodeCount = minNodes + random.nextInt(maxNodes - minNodes + 1);
        }

        List<UUID> chain = new ArrayList<>();
        chain.add(townA.id());

        for (int i = 1; i <= nodeCount; i++) {
            double segLen = roadLength / (nodeCount + 1);
            double margin = segLen * 0.15;
            double lo = segLen * (i - 1) + margin;
            double hi = segLen * i - margin;
            double targetDistance = lo + random.nextDouble() * Math.max(0.0, hi - lo);

            double accumulated = 0.0;
            int segmentIndex = 0;
            for (int j = 0; j < roadPath.size() - 1; j++) {
                double segmentDist = roadPath.get(j).distance(roadPath.get(j + 1));
                if (accumulated + segmentDist >= targetDistance) {
                    segmentIndex = j;
                    break;
                }
                accumulated += segmentDist;
            }

            RoadPoint2D p1 = roadPath.get(segmentIndex);
            RoadPoint2D p2 = roadPath.get(Math.min(segmentIndex + 1, roadPath.size() - 1));
            double segmentDist = p1.distance(p2);
            double t = segmentDist > 0 ? (targetDistance - accumulated) / segmentDist : 0.0;
            t = Math.max(0.0, Math.min(1.0, t));

            int x = (int) Math.round(p1.x() + (p2.x() - p1.x()) * t);
            int z = (int) Math.round(p1.z() + (p2.z() - p1.z()) * t);

            BlockPos waypointPos = SectorWaypointPicker.pickWaypointNear(
                x, z, 24,
                worldSeed ^ ((long) x * 31L + z),
                generator, height, randomState
            );
            if (waypointPos == null) {
                waypointPos = new BlockPos(x, townA.y(), z);
            }

            int index = startIndex + i;
            UUID nodeId = waypointId(worldSeed, index);
            RoadNode node = new RoadNode(nodeId, RoadNode.Kind.WAYPOINT,
                "Перекрёсток #" + index, waypointPos.getX(), waypointPos.getY(), waypointPos.getZ());
            nodes.put(nodeId, node);
            chain.add(nodeId);
        }

        chain.add(townB.id());
        return new ChainResult(chain, nodeCount);
    }

    // =========================================================================
    // Геометрия сегмента
    // =========================================================================

    private static RoadEdge buildEdge(RoadNode from,
                                      RoadNode to,
                                      long worldSeed,
                                      ChunkGenerator generator,
                                      LevelHeightAccessor height,
                                      RandomState randomState) {
        ObjectList<RoadPoint2D> found = CoarsePathfinder.findLandRoad(
            generator, height, randomState,
            from.x(), from.z(), to.x(), to.z()
        );

        List<RoadPoint2D> geometry;
        double distance;
        if (!found.isEmpty() && found.size() >= 2) {
            List<RoadPoint2D> copy = new ArrayList<>(found.size());
            for (RoadPoint2D pt : found) {
                copy.add(new RoadPoint2D(pt.x(), pt.z(), pt.isBridge()));
            }
            geometry = copy;
            distance = 0.0;
            for (int i = 0; i < geometry.size() - 1; i++) {
                distance += geometry.get(i).distance(geometry.get(i + 1));
            }
        } else {
            geometry = List.of(
                new RoadPoint2D(from.x(), from.z(), false),
                new RoadPoint2D(to.x(), to.z(), false)
            );
            double dx = (double) from.x() - to.x();
            double dz = (double) from.z() - to.z();
            distance = Math.sqrt(dx * dx + dz * dz);
        }

        return new RoadEdge(from.id(), to.id(), geometry, false, distance);
    }
}
