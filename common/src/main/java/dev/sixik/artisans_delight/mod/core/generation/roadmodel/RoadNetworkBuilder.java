package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import dev.sixik.artisans_delight.mod.core.generation.grid.SectorWaypointPicker;
import dev.sixik.artisans_delight.mod.core.generation.path.CoarsePathfinder;
import dev.sixik.artisans_delight.mod.core.generation.path.GenerationProgress;
import dev.sixik.artisans_delight.mod.core.generation.terrain.TerrainSampler;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Чистый расчёт виртуальной дорожной сети в стиле BDO-меша.
 *
 * <p>Ничего не пишет в мир: не создаёт {@code WorldObject}, не трогает
 * менеджер, не ставит блоки. Результат — {@link RoadNetwork}, который
 * публикуется в {@link RoadNetworkStore} и читается картой.
 *
 * <p><b>Алгоритм (BDO-меш):</b>
 * <ol>
 *   <li>Равномерное поле кандидатов: джиттер-сетка поверх bbox городов,
 *       только суша (вода отсекается), шаг адаптивный 250–400.</li>
 *   <li>Связка узел–узел: кандидаты — соседи в радиусе {@code LINK_RADIUS},
 *       сортировка по длине, лимит степени {@code MAX_DEGREE}, запрет
 *       пересечений отрезков, вероятностный пропуск для ветвлений.</li>
 *   <li>Ветви: waypoint-only компоненты с шансом остаются isolated-ветками,
 *       компоненты с городами всегда пришиваются к главной.</li>
 *   <li>Геометрия каждого ребра — {@code CoarsePathfinder.findLandRoad}.
 *       Нет дороги — ребро отбрасывается (никаких прямых через воду).</li>
 * </ol>
 *
 * <p><b>Параллелизм:</b> тяжёлая геометрия считается в пуле
 * {@code CALC_THREADS}. Топология детерминирована (сортировка + хеши),
 * собирается в порядке принятия, поэтому повторный расчёт даёт тот же результат.
 */
public final class RoadNetworkBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadNetworkBuilder.class);

    /**
     * Размер пула для параллельного расчёта рёбер.
     * Рёбра независимы: у каждого своя геометрия.
     */
    private static final int CALC_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));

    /** Целевое число промежуточных узлов (адаптация шага сетки). */
    private static final int MESH_TARGET_WAYPOINTS = 250;
    /** Минимальный шаг поля (плотный BDO-меш). */
    private static final int MESH_SPACING_MIN = 250;
    /** Максимальный шаг поля (при широко разбросанных городах). */
    private static final int MESH_SPACING_MAX = 400;
    /** Жёсткий лимит числа промежуточных узлов (защита от взрывного перфа). */
    private static final int MESH_MAX_WAYPOINTS = 400;
    /** Отступ bbox городов для поля кандидатов. */
    private static final int MESH_MARGIN = 600;
    /** Джиттер ячейки (± доля шага). */
    private static final double MESH_JITTER = 0.35;

    /** Радиус связки узел–узел (только короткие рёбра, без диагоналей через полкарты). */
    private static final double LINK_RADIUS = 600.0;
    /** Минимальная длина ребра (защита от кластеров-дублей). */
    private static final double MIN_LINK_DIST = 90.0;
    /** Максимум связей на узел (петли разрешены, паутина — нет). */
    private static final int MAX_DEGREE = 4;
    /** Базовая вероятность связки, когда оба конца уже имеют ≥2 связей (ветвления/пропуски). */
    private static final double LINK_KEEP_PROB = 0.55;
    /** Шанс waypoint-only компоненты остаться отдельной веткой (не пришиваться к главной). */
    private static final double LEAVE_ISOLATED_BRANCH = 0.25;
    /** Радиус пришивки обычных веток к главной компоненте. */
    private static final double BRANCH_ATTACH_RADIUS = LINK_RADIUS * 1.5;
    /** Радиус пришивки компонент с городами (города обязаны быть связаны). */
    private static final double TOWN_ATTACH_RADIUS = 3000.0;
    /** Не ставить перекрёсток ближе к городу (убирает скученность у деревень). */
    private static final double TOWN_CLEARANCE = 140.0;
    /** Не ставить перекрёстки ближе друг к другу (убирает дубовые кластеры). */
    private static final double WAYPOINT_SEPARATION = 150.0;
    /** Отброс геометрии-детуров длиннее этого (эвклидово ребро короткое, а объезд — нет). */
    private static final double MAX_ROAD_LENGTH = LINK_RADIUS * 2.5;

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
     * Строит BDO-меш для городов.
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

        // 1. Поле промежуточных узлов (равномерно, только суша).
        GenerationProgress.setStage(GenerationProgress.Stage.COARSE_GRAPH);
        List<int[]> field = buildMeshField(townList, worldSeed, generator, height, randomState);
        int waypointIndex = 0;
        for (int[] c : field) {
            waypointIndex++;
            UUID nodeId = waypointId(worldSeed, waypointIndex);
            nodes.put(nodeId, new RoadNode(nodeId, RoadNode.Kind.WAYPOINT,
                "Перекрёсток #" + waypointIndex, c[0], c[1], c[2]));
        }
        LOGGER.info("[RoadNetworkBuilder] Поле меша: {} перекрёстков (шаг адаптивный {}–{})",
            field.size(), MESH_SPACING_MIN, MESH_SPACING_MAX);

        // 2. Топология узел–узел (короткие рёбра, степень ≤ 4, без пересечений, с ветвлениями).
        Set<UUID> townIds = new HashSet<>();
        for (TownSeed t : townList) {
            townIds.add(t.id());
        }
        List<UUID[]> topoPairs = selectMeshLinks(nodes, townIds, worldSeed);
        LOGGER.info("[RoadNetworkBuilder] Топология меша: {} рёбер для {} узлов",
            topoPairs.size(), nodes.size());

        // 3. Геометрия рёбер ПАРАЛЛЕЛЬНО. Без дороги — ребро отбрасывается.
        java.util.concurrent.ExecutorService calcPool = java.util.concurrent.Executors.newFixedThreadPool(
            CALC_THREADS, r -> {
                Thread t = new Thread(r, "ArtisansDelight-RoadCalc");
                t.setDaemon(true);
                return t;
            });
        try {
            Map<String, RoadEdge> edges = buildMeshEdgesParallel(
                topoPairs, nodes, worldSeed, generator, height, randomState, calcPool);

            GenerationProgress.finish();
            RoadNetwork network = new RoadNetwork(worldSeed, nodes, edges);
            LOGGER.info("[RoadNetworkBuilder] Сеть-меш посчитана (потоков: {}): {} узлов, {} рёбер",
                CALC_THREADS, network.nodeCount(), network.edgeCount());
            return network;
        } finally {
            calcPool.shutdownNow();
        }
    }

    // =========================================================================
    // Поле узлов: джиттер-сетка поверх bbox городов, только суша
    // =========================================================================

    private static List<int[]> buildMeshField(List<TownSeed> townList,
                                              long worldSeed,
                                              ChunkGenerator generator,
                                              LevelHeightAccessor height,
                                              RandomState randomState) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (TownSeed t : townList) {
            if (t.x() < minX) minX = t.x();
            if (t.x() > maxX) maxX = t.x();
            if (t.z() < minZ) minZ = t.z();
            if (t.z() > maxZ) maxZ = t.z();
        }

        long areaW = (long) (maxX - minX) + 2L * MESH_MARGIN;
        long areaH = (long) (maxZ - minZ) + 2L * MESH_MARGIN;
        int step = (int) Math.sqrt((double) areaW * (double) areaH / MESH_TARGET_WAYPOINTS);
        step = Math.max(MESH_SPACING_MIN, Math.min(MESH_SPACING_MAX, step));

        int gx0 = Math.floorDiv(minX - MESH_MARGIN, step);
        int gx1 = Math.floorDiv(maxX + MESH_MARGIN, step);
        int gz0 = Math.floorDiv(minZ - MESH_MARGIN, step);
        int gz1 = Math.floorDiv(maxZ + MESH_MARGIN, step);
        int totalCells = (gx1 - gx0 + 1) * (gz1 - gz0 + 1);

        List<int[]> accepted = new ArrayList<>();
        int visited = 0;
        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gz = gz0; gz <= gz1; gz++) {
                visited++;
                if (visited % 25 == 0) {
                    GenerationProgress.setProgress(visited, totalCells, "Поле узлов");
                }
                if (accepted.size() >= MESH_MAX_WAYPOINTS) {
                    continue;
                }

                Random cellRng = new Random(worldSeed ^ (gx * 73856093L ^ gz * 19349663L));
                int baseX = gx * step + step / 2;
                int baseZ = gz * step + step / 2;
                int jx = baseX + (int) Math.round((cellRng.nextDouble() * 2.0 - 1.0) * step * MESH_JITTER);
                int jz = baseZ + (int) Math.round((cellRng.nextDouble() * 2.0 - 1.0) * step * MESH_JITTER);

                if (nearTown(townList, jx, jz, TOWN_CLEARANCE)) {
                    continue;
                }
                if (nearAccepted(accepted, jx, jz, WAYPOINT_SEPARATION)) {
                    continue;
                }

                BlockPos snapped = SectorWaypointPicker.pickWaypointNear(
                    jx, jz, 24,
                    worldSeed ^ ((long) jx * 31L + jz),
                    generator, height, randomState);
                if (snapped == null) {
                    continue;
                }
                int sx = snapped.getX();
                int sz = snapped.getZ();
                if (nearTown(townList, sx, sz, TOWN_CLEARANCE)) {
                    continue;
                }
                if (nearAccepted(accepted, sx, sz, WAYPOINT_SEPARATION)) {
                    continue;
                }
                if (TerrainSampler.isWater(generator, height, randomState, sx, sz)) {
                    continue;
                }
                accepted.add(new int[]{sx, snapped.getY(), sz});
            }
        }
        GenerationProgress.setProgress(totalCells, totalCells, "Поле узлов: " + accepted.size());
        return accepted;
    }

    private static boolean nearTown(List<TownSeed> towns, int x, int z, double radius) {
        double r2 = radius * radius;
        for (TownSeed t : towns) {
            double dx = (double) t.x() - x;
            double dz = (double) t.z() - z;
            if (dx * dx + dz * dz < r2) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearAccepted(List<int[]> accepted, int x, int z, double radius) {
        double r2 = radius * radius;
        for (int[] c : accepted) {
            double dx = (double) c[0] - x;
            double dz = (double) c[2] - z;
            if (dx * dx + dz * dz < r2) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Топология узел–узел: короткие рёбра, степень ≤ 4, без пересечений
    // =========================================================================

    private record LinkCandidate(UUID a, UUID b, double dist) {
    }

    private static List<UUID[]> selectMeshLinks(Map<UUID, RoadNode> nodes,
                                                Set<UUID> townIds,
                                                long worldSeed) {
        List<UUID> ids = new ArrayList<>(nodes.keySet());
        Map<UUID, double[]> pos = new HashMap<>(ids.size() * 2);
        for (UUID id : ids) {
            RoadNode n = nodes.get(id);
            pos.put(id, new double[]{n.x(), n.z()});
        }

        // Все пары в радиусе связки, сортировка по длине (детерминированно).
        List<LinkCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                UUID a = ids.get(i);
                UUID b = ids.get(j);
                double[] pa = pos.get(a);
                double[] pb = pos.get(b);
                double dist = Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
                if (dist >= MIN_LINK_DIST && dist <= LINK_RADIUS) {
                    candidates.add(new LinkCandidate(a, b, dist));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(LinkCandidate::dist)
            .thenComparing(c -> c.a().toString() + c.b().toString()));

        Map<UUID, Integer> degree = new HashMap<>();
        for (UUID id : ids) {
            degree.put(id, 0);
        }
        List<UUID[]> accepted = new ArrayList<>();
        UnionFind uf = new UnionFind(ids);

        for (LinkCandidate c : candidates) {
            int degA = degree.get(c.a());
            int degB = degree.get(c.b());
            if (degA >= MAX_DEGREE || degB >= MAX_DEGREE) {
                continue;
            }
            if (crossesAccepted(c.a(), c.b(), pos, accepted)) {
                continue;
            }
            double keep;
            if (degA < 2 && degB < 2) {
                keep = 0.95;
            } else if (degA < 2 || degB < 2) {
                keep = 0.8;
            } else {
                keep = LINK_KEEP_PROB * (1.0 - 0.4 * (c.dist() / LINK_RADIUS));
            }
            Random roll = new Random(worldSeed ^ ((long) c.a().hashCode() * 31L + c.b().hashCode()));
            if (roll.nextDouble() >= keep) {
                continue; // Ветвление: пропуск создаёт отростки и отдельные ветки.
            }
            accepted.add(new UUID[]{c.a(), c.b()});
            degree.put(c.a(), degA + 1);
            degree.put(c.b(), degB + 1);
            uf.union(c.a(), c.b());
        }

        // Компоненты: главная (с первым городом) + пришивка остальных.
        Map<Integer, List<UUID>> components = new HashMap<>();
        for (UUID id : ids) {
            components.computeIfAbsent(uf.find(id), k -> new ArrayList<>()).add(id);
        }
        UUID firstTown = townIds.iterator().next();
        int mainRoot = uf.find(firstTown);
        Set<UUID> mainSet = new HashSet<>(components.getOrDefault(mainRoot, List.of()));

        // Сначала компоненты с городами, потом крупные waypoint-ветки.
        List<List<UUID>> others = new ArrayList<>();
        for (Map.Entry<Integer, List<UUID>> e : components.entrySet()) {
            if (e.getKey() == mainRoot) continue;
            others.add(e.getValue());
        }
        others.sort((m1, m2) -> {
            boolean t1 = m1.stream().anyMatch(townIds::contains);
            boolean t2 = m2.stream().anyMatch(townIds::contains);
            if (t1 != t2) return t1 ? -1 : 1;
            return Integer.compare(m2.size(), m1.size());
        });

        Random branchRng = new Random(worldSeed ^ 0x9E3779B9L);
        for (List<UUID> members : others) {
            boolean hasTown = members.stream().anyMatch(townIds::contains);
            if (!hasTown && branchRng.nextDouble() < LEAVE_ISOLATED_BRANCH) {
                continue; // Отдельная ветка со своим шансом — как в BDO.
            }
            double limit = hasTown ? TOWN_ATTACH_RADIUS : BRANCH_ATTACH_RADIUS;
            UUID[] bridge = findBridge(members, mainSet, pos, accepted, degree, limit);
            if (bridge != null) {
                accepted.add(bridge);
                degree.put(bridge[0], degree.get(bridge[0]) + 1);
                degree.put(bridge[1], degree.get(bridge[1]) + 1);
                uf.union(bridge[0], bridge[1]);
                mainSet.addAll(members);
            } else if (hasTown) {
                LOGGER.warn("[RoadNetworkBuilder] Компонента с городом осталась без пришивки ({} узлов)", members.size());
            }
        }
        return accepted;
    }

    /** Лучшая перемычка между компонентой и главной сетью (короткая, без пересечений, со свободной степенью). */
    private static UUID[] findBridge(List<UUID> members,
                                     Set<UUID> mainSet,
                                     Map<UUID, double[]> pos,
                                     List<UUID[]> accepted,
                                     Map<UUID, Integer> degree,
                                     double limit) {
        List<LinkCandidate> cross = new ArrayList<>();
        for (UUID m : members) {
            for (UUID g : mainSet) {
                double[] pm = pos.get(m);
                double[] pg = pos.get(g);
                if (pm == null || pg == null) continue;
                double dist = Math.hypot(pm[0] - pg[0], pm[1] - pg[1]);
                if (dist >= MIN_LINK_DIST && dist <= limit) {
                    cross.add(new LinkCandidate(m, g, dist));
                }
            }
        }
        cross.sort(Comparator.comparingDouble(LinkCandidate::dist)
            .thenComparing(c -> c.a().toString() + c.b().toString()));
        int tried = 0;
        for (LinkCandidate c : cross) {
            if (tried++ >= 8) break;
            if (degree.getOrDefault(c.a(), MAX_DEGREE) >= MAX_DEGREE) continue;
            if (degree.getOrDefault(c.b(), MAX_DEGREE) >= MAX_DEGREE) continue;
            if (crossesAccepted(c.a(), c.b(), pos, accepted)) continue;
            return new UUID[]{c.a(), c.b()};
        }
        return null;
    }

    private static boolean crossesAccepted(UUID a,
                                           UUID b,
                                           Map<UUID, double[]> pos,
                                           List<UUID[]> accepted) {
        double[] pa = pos.get(a);
        double[] pb = pos.get(b);
        for (UUID[] e : accepted) {
            if (e[0].equals(a) || e[1].equals(a) || e[0].equals(b) || e[1].equals(b)) {
                continue;
            }
            double[] pc = pos.get(e[0]);
            double[] pd = pos.get(e[1]);
            if (segmentsIntersect(pa[0], pa[1], pb[0], pb[1], pc[0], pc[1], pd[0], pd[1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                             double cx, double cy, double dx, double dy) {
        double o1 = orient(ax, ay, bx, by, cx, cy);
        double o2 = orient(ax, ay, bx, by, dx, dy);
        double o3 = orient(cx, cy, dx, dy, ax, ay);
        double o4 = orient(cx, cy, dx, dy, bx, by);
        return o1 * o2 < 0.0 && o3 * o4 < 0.0;
    }

    private static double orient(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static final class UnionFind {
        private final Map<UUID, Integer> index = new HashMap<>();
        private final int[] parent;

        UnionFind(List<UUID> ids) {
            parent = new int[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                index.put(ids.get(i), i);
                parent[i] = i;
            }
        }

        int find(UUID id) {
            int i = index.get(id);
            while (parent[i] != i) {
                parent[i] = parent[parent[i]];
                i = parent[i];
            }
            return i;
        }

        void union(UUID a, UUID b) {
            int ra = find(a);
            int rb = find(b);
            if (ra != rb) {
                parent[ra] = rb;
            }
        }
    }

    // =========================================================================
    // Геометрия рёбер меша (параллельно). Без дороги — ребро отбрасывается.
    // =========================================================================

    private static Map<String, RoadEdge> buildMeshEdgesParallel(List<UUID[]> pairs,
                                                                Map<UUID, RoadNode> nodes,
                                                                long worldSeed,
                                                                ChunkGenerator generator,
                                                                LevelHeightAccessor height,
                                                                RandomState randomState,
                                                                java.util.concurrent.ExecutorService pool) {
        GenerationProgress.setStage(GenerationProgress.Stage.FINE_GEOMETRY);

        int total = pairs.size();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        List<java.util.concurrent.Future<RoadEdge>> futures = new ArrayList<>(total);
        for (UUID[] pair : pairs) {
            final RoadNode from = nodes.get(pair[0]);
            final RoadNode to = nodes.get(pair[1]);
            futures.add(pool.submit(() -> {
                RoadEdge edge = (from == null || to == null) ? null
                    : buildEdge(from, to, worldSeed, generator, height, randomState);
                int d = done.incrementAndGet();
                GenerationProgress.setProgress(d, total, edge != null ? ((int) edge.distance() + "м") : "пропуск");
                return edge;
            }));
        }

        Map<String, RoadEdge> edges = new LinkedHashMap<>();
        for (java.util.concurrent.Future<RoadEdge> f : futures) {
            try {
                RoadEdge edge = f.get();
                if (edge != null) {
                    edges.put(edge.key(), edge);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Edge calc interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Edge calc failed", e.getCause());
            }
        }
        return edges;
    }

    /**
     * Геометрия одного ребра меша. Возвращает {@code null}, если сухопутной
     * дороги нет (океан/скалы) или объезд вышел слишком длинным — такое ребро
     * отбрасывается, а не рисуется прямой через воду.
     */
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

        if (found.isEmpty() || found.size() < 2) {
            return null;
        }
        List<RoadPoint2D> geometry = new ArrayList<>(found.size());
        for (RoadPoint2D pt : found) {
            geometry.add(new RoadPoint2D(pt.x(), pt.z(), pt.isBridge()));
        }
        double distance = 0.0;
        for (int i = 0; i < geometry.size() - 1; i++) {
            distance += geometry.get(i).distance(geometry.get(i + 1));
        }
        if (distance > MAX_ROAD_LENGTH) {
            return null;
        }
        return new RoadEdge(from.id(), to.id(), geometry, false, distance);
    }
}
