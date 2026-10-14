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
 *       пересечений отрезков, вероятностный пропуск для ветвлений.
 *       Узел без соседей в радиусе не оставляется на шансе: ему безусловно
 *       назначается кратчайшее непересекающееся ребро (топологическая гарантия).</li>
 *   <li>Ветви: waypoint-only компоненты с шансом остаются isolated-ветками,
 *       компоненты с городами всегда пришиваются к главной.</li>
 *   <li>Геометрия каждого ребра — {@code CoarsePathfinder.findLandRoad}.
 *       Нет дороги — ребро отбрасывается (никаких прямых через воду).</li>
 *   <li>Финальная гарантия: сироты с нулевой степенью после геометрии
 *       ремонтируются (ближайший сосед с проверенной дорогой, пересечения
 *       и степень — по возможности), неремонтируемые вырезаются из узлов,
 *       компоненты с городами сшиваются проверенными мостами.</li>
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
    /** Радиус гарантированного добива сирот после геометрии (второй шанс длиннее обычного). */
    private static final double ORPHAN_LINK_RADIUS = 1200.0;
    /** Радиус сшивки изолированных компонент городов после финальной гарантии. */
    private static final double ORPHAN_TOWN_RADIUS = 3000.0;
    /** Не ставить перекрёсток ближе к городу (убирает скученность у деревень). */
    private static final double TOWN_CLEARANCE = 140.0;
    /** Не ставить перекрёстки ближе друг к другу (убирает дубовые кластеры). */
    private static final double WAYPOINT_SEPARATION = 150.0;
    /** Отброс геометрии-детуров длиннее этого (эвклидово ребро короткое, а объезд — нет). */
    private static final double MAX_ROAD_LENGTH = LINK_RADIUS * 2.5;
    /** Допуск почти-прямого пути: извилистость ниже — дорога идёт почти по хорде. */
    private static final double STRAIGHT_TORTUOSITY = 1.08;
    /** Предел извилистости: выше — петля/объезд, ребро отбрасывается. */
    private static final double MAX_TORTUOSITY = 1.6;
    /** Минимальный зазор между рёбрами — уже считается дублем/слипанием. */
    private static final double CORRIDOR_MIN_GAP = 35.0;
    /** Зазор слияния: дороги ближе — это одна дорога, дубликат выкидывается. */
    private static final double CORRIDOR_MERGE_GAP = 90.0;
    /** Доля длины для счёта параллельности (медиана по сэмплам). */
    private static final double CORRIDOR_SAMPLE_STEP = 40.0;

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

            // 3b. Деконфликтация по РЕАЛЬНОЙ геометрии: хорды топологии не видят
            // кривизну дорог — две хорды расходятся, а полилинии по факту крестятся,
            // идут дублями или петляют. Проверяем полилинии друг о друга: кресты,
            // сближения и петли выкидываются до гарантии связности.
            // Порядок детерминирован: сначала бывшие хорд-пересечения, потом короткие.
            List<RoadEdge> geoList = new ArrayList<>(edges.values());
            geoList.sort(Comparator.comparingDouble((RoadEdge e) -> chordLength(nodes, e))
                .thenComparing(RoadEdge::key));
            java.util.Set<String> dropped = deconflictGeometry(geoList);
            for (String key : dropped) {
                edges.remove(key);
            }
            if (!dropped.isEmpty()) {
                LOGGER.info("[RoadNetworkBuilder] Деконфликтация геометрии: выкинуто {} рёбер (кресты/дубли/петли)",
                    dropped.size());
            }

            // 4. Финальная гарантия: топологическая связность + проверенная геометрия.
            // Ремонт и сшивка тоже идут через геометрическую проверку: кандидат
            // не должен креститься, дублировать или петлять относительно живых.
            ensureConnectivity(nodes, edges, townIds, worldSeed, generator, height, randomState);

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
            boolean degZero = degA == 0 || degB == 0;
            double keep;
            if (degA < 2 && degB < 2) {
                keep = 0.95;
            } else if (degA < 2 || degB < 2) {
                keep = 0.8;
            } else {
                keep = LINK_KEEP_PROB * (1.0 - 0.4 * (c.dist() / LINK_RADIUS));
            }
            if (!degZero) {
                // Ветвление: пропуск создаёт отростки и отдельные ветки.
                // Но узел со степенью 0 роллу не подлежит — ему нужна хоть одна связь.
                Random roll = new Random(worldSeed ^ ((long) c.a().hashCode() * 31L + c.b().hashCode()));
                if (roll.nextDouble() >= keep) {
                    continue;
                }
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

    // =========================================================================
    // Финальная гарантия: ремонт сирот и чистка после геометрии
    // =========================================================================

    /**
     * Доводит сеть до гарантированного состояния после отброса рёбер без геометрии.
     *
     * <p>Проблема: топология приняла ребро (степень поднята), а
     * {@link #buildEdge} потом вернул {@code null} — узел остаётся сиротой
     * на карте. Здесь каждый waypoint со степенью 0 получает второй шанс:
     * ближайший сосед с реально построенной дорогой. Неремонтируемые
     * перекрёстки вырезаются из узлов. Компоненты с городами сшиваются
     * проверенными мостами, чтобы города не висели изолированно.
     */
    private static void ensureConnectivity(Map<UUID, RoadNode> nodes,
                                           Map<String, RoadEdge> edges,
                                           Set<UUID> townIds,
                                           long worldSeed,
                                           ChunkGenerator generator,
                                           LevelHeightAccessor height,
                                           RandomState randomState) {
        Map<UUID, Integer> degree = new HashMap<>();
        for (UUID id : nodes.keySet()) {
            degree.put(id, 0);
        }
        for (RoadEdge e : edges.values()) {
            degree.put(e.fromId(), degree.getOrDefault(e.fromId(), 0) + 1);
            degree.put(e.toId(), degree.getOrDefault(e.toId(), 0) + 1);
        }

        Map<UUID, double[]> pos = new HashMap<>(nodes.size() * 2);
        for (RoadNode n : nodes.values()) {
            pos.put(n.id(), new double[]{n.x(), n.z()});
        }

        // Сироты: перекрёстки со степенью 0. Города не чистим никогда.
        List<UUID> orphans = new ArrayList<>();
        for (RoadNode n : nodes.values()) {
            if (!n.isTown() && degree.getOrDefault(n.id(), 0) == 0) {
                orphans.add(n.id());
            }
        }
        orphans.sort(Comparator.comparing(UUID::toString));

        List<RoadEdge> liveEdges = new ArrayList<>(edges.values());
        int repaired = 0;
        List<UUID> unrepairable = new ArrayList<>();
        for (UUID orphan : orphans) {
            RoadEdge fixed = repairOrphan(orphan, townIds, nodes, pos, degree, liveEdges,
                worldSeed, generator, height, randomState);
            if (fixed != null) {
                edges.put(fixed.key(), fixed);
                liveEdges.add(fixed);
                degree.put(fixed.fromId(), degree.getOrDefault(fixed.fromId(), 0) + 1);
                degree.put(fixed.toId(), degree.getOrDefault(fixed.toId(), 0) + 1);
                repaired++;
            } else {
                unrepairable.add(orphan);
            }
        }

        for (UUID id : unrepairable) {
            nodes.remove(id);
        }

        int stitched = stitchTownComponents(nodes, edges, townIds, pos, degree, liveEdges,
            worldSeed, generator, height, randomState);

        LOGGER.info("[RoadNetworkBuilder] Гарантия связности: сирот {}, починено {}, удалено {}, сшито мостов {}",
            orphans.size(), repaired, unrepairable.size(), stitched);
    }

    /** Чинит одного сироту: ближайший сосед с проверенной дорогой (без ролла, детерминированно). */
    private static RoadEdge repairOrphan(UUID orphan,
                                         Set<UUID> townIds,
                                         Map<UUID, RoadNode> nodes,
                                         Map<UUID, double[]> pos,
                                         Map<UUID, Integer> degree,
                                         List<RoadEdge> liveEdges,
                                         long worldSeed,
                                         ChunkGenerator generator,
                                         LevelHeightAccessor height,
                                         RandomState randomState) {
        RoadNode from = nodes.get(orphan);
        if (from == null) {
            return null;
        }
        List<LinkCandidate> cands = new ArrayList<>();
        for (Map.Entry<UUID, double[]> e : pos.entrySet()) {
            UUID other = e.getKey();
            if (other.equals(orphan)) {
                continue;
            }
            double[] po = e.getValue();
            double[] pf = pos.get(orphan);
            double dist = Math.hypot(pf[0] - po[0], pf[1] - po[1]);
            if (dist >= MIN_LINK_DIST && dist <= ORPHAN_LINK_RADIUS) {
                cands.add(new LinkCandidate(orphan, other, dist));
            }
        }
        cands.sort(Comparator.comparingDouble(LinkCandidate::dist)
            .thenComparing(c -> c.a().toString() + c.b().toString()));

        int tried = 0;
        for (LinkCandidate c : cands) {
            if (tried++ >= 12) {
                break;
            }
            UUID other = c.b();
            if (degree.getOrDefault(other, MAX_DEGREE) >= MAX_DEGREE) {
                continue; // Не вытесняем чужие рёбра ради сироты.
            }
            if (crossesLive(c.a(), c.b(), pos, liveEdges)) {
                continue;
            }
            RoadNode to = nodes.get(other);
            if (to == null || townIds.contains(other) && townIds.contains(orphan)) {
                continue;
            }
            RoadEdge edge = buildEdge(from, to, worldSeed, generator, height, randomState);
            if (edge != null && !conflictsLiveGeometry(edge, liveEdges)) {
                return edge;
            }
        }
        return null;
    }

    /** Сшивает изолированные компоненты с городами проверенными мостами (города не должны висеть). */
    private static int stitchTownComponents(Map<UUID, RoadNode> nodes,
                                            Map<String, RoadEdge> edges,
                                            Set<UUID> townIds,
                                            Map<UUID, double[]> pos,
                                            Map<UUID, Integer> degree,
                                            List<RoadEdge> liveEdges,
                                            long worldSeed,
                                            ChunkGenerator generator,
                                            LevelHeightAccessor height,
                                            RandomState randomState) {
        List<UUID> ids = new ArrayList<>(nodes.keySet());
        UnionFind uf = new UnionFind(ids);
        for (RoadEdge e : liveEdges) {
            uf.union(e.fromId(), e.toId());
        }
        Map<Integer, List<UUID>> components = new HashMap<>();
        for (UUID id : ids) {
            components.computeIfAbsent(uf.find(id), k -> new ArrayList<>()).add(id);
        }
        if (components.size() <= 1) {
            return 0;
        }
        List<UUID> townList = new ArrayList<>(townIds);
        townList.sort(Comparator.comparing(UUID::toString));
        int mainRoot = uf.find(townList.get(0));
        Set<UUID> mainSet = new HashSet<>(components.getOrDefault(mainRoot, List.of()));

        List<List<UUID>> townComponents = new ArrayList<>();
        List<List<UUID>> plainComponents = new ArrayList<>();
        for (Map.Entry<Integer, List<UUID>> e : components.entrySet()) {
            if (e.getKey() == mainRoot) {
                continue;
            }
            if (e.getValue().stream().anyMatch(townIds::contains)) {
                townComponents.add(e.getValue());
            } else {
                plainComponents.add(e.getValue());
            }
        }
        townComponents.sort(Comparator.comparingInt(List::size));
        // Маленькие waypoint-компоненты без городов (одиночки/пары от ремонта)
        // не трогаем: это и есть разрешённые BDO-ветки. Сшиваем только города.
        int stitched = 0;
        for (List<UUID> members : townComponents) {
            UUID[] pair = findLiveBridge(members, mainSet, pos, liveEdges, degree, ORPHAN_TOWN_RADIUS);
            if (pair == null) {
                LOGGER.warn("[RoadNetworkBuilder] Компонента с городом осталась изолированной ({} узлов)", members.size());
                continue;
            }
            RoadNode from = nodes.get(pair[0]);
            RoadNode to = nodes.get(pair[1]);
            if (from == null || to == null) {
                continue;
            }
            RoadEdge edge = buildEdge(from, to, worldSeed, generator, height, randomState);
            if (edge == null || conflictsLiveGeometry(edge, liveEdges)) {
                continue;
            }
            edges.put(edge.key(), edge);
            liveEdges.add(edge);
            degree.put(edge.fromId(), degree.getOrDefault(edge.fromId(), 0) + 1);
            degree.put(edge.toId(), degree.getOrDefault(edge.toId(), 0) + 1);
            uf.union(edge.fromId(), edge.toId());
            mainSet.addAll(members);
            stitched++;
        }
        return stitched;
    }

    /** Перемычка между компонентой и главной сетью (без пересечений с живыми рёбрами). */
    private static UUID[] findLiveBridge(List<UUID> members,
                                         Set<UUID> mainSet,
                                         Map<UUID, double[]> pos,
                                         List<RoadEdge> liveEdges,
                                         Map<UUID, Integer> degree,
                                         double limit) {
        List<LinkCandidate> cross = new ArrayList<>();
        for (UUID m : members) {
            for (UUID g : mainSet) {
                double[] pm = pos.get(m);
                double[] pg = pos.get(g);
                if (pm == null || pg == null) {
                    continue;
                }
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
            if (tried++ >= 12) {
                break;
            }
            if (degree.getOrDefault(c.a(), MAX_DEGREE) >= MAX_DEGREE
                || degree.getOrDefault(c.b(), MAX_DEGREE) >= MAX_DEGREE) {
                continue;
            }
            if (crossesLive(c.a(), c.b(), pos, liveEdges)) {
                continue;
            }
            return new UUID[]{c.a(), c.b()};
        }
        return null;
    }

    /**
     * Проверяет кандидата с готовой геометрией против живых рёбер: крест
     * полилиний, дубль коридоров или петля у чужого узла — кандидат отклоняется.
     * Общий узел — не конфликт (слияние в точке — норма, скрин 1 справа).
     */
    private static boolean conflictsLiveGeometry(RoadEdge edge, List<RoadEdge> liveEdges) {
        List<double[]> poly = toPoly(edge);
        for (RoadEdge k : liveEdges) {
            if (edge.connects(k.fromId()) || edge.connects(k.toId())) {
                if (loopsAtFreeEnd(poly, edge, k)) {
                    return true;
                }
                continue;
            }
            if (polysCross(poly, toPoly(k)) || corridorsOverlap(poly, toPoly(k))) {
                return true;
            }
        }
        return false;
    }

    private static boolean crossesLive(UUID a,
                                       UUID b,
                                       Map<UUID, double[]> pos,
                                       List<RoadEdge> liveEdges) {
        double[] pa = pos.get(a);
        double[] pb = pos.get(b);
        if (pa == null || pb == null) {
            return true;
        }
        for (RoadEdge e : liveEdges) {
            if (e.connects(a) || e.connects(b)) {
                continue;
            }
            // Координаты концов живого ребра берём из pos по id (узлы ещё не вычищены).
            double[] pc = pos.get(e.fromId());
            double[] pd = pos.get(e.toId());
            if (pc == null || pd == null) {
                continue;
            }
            if (segmentsIntersect(pa[0], pa[1], pb[0], pb[1], pc[0], pc[1], pd[0], pd[1])) {
                return true;
            }
        }
        return false;
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
     * дороги нет (океан/скалы), объезд вышел слишком длинным или путь
     * петляет (извилистость выше предела) — такое ребро отбрасывается,
     * а не рисуется прямой через воду или петлёй вокруг узла.
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
        double chord = Math.hypot((double) from.x() - to.x(), (double) from.z() - to.z());
        double tortuosity = chord > 1.0 ? distance / chord : 1.0;
        if (tortuosity > MAX_TORTUOSITY) {
            return null; // Петля/дикий объезд вместо дороги.
        }
        return new RoadEdge(from.id(), to.id(), geometry, false, distance);
    }

    // =========================================================================
    // Деконфликтация по реальной геометрии (кресты, дубли, петли)
    // =========================================================================

    /**
     * Выкидывает рёбра, чья реальная геометрия конфликтует с уже принятыми.
     *
     * <p>Топология проверяет только прямые хорды, а дороги — кривые: пара хорд
     * может расходиться, а полилинии при этом крестятся, слипаются в дубль или
     * петляют. Каждое ребро (в порядке: сначала хорд-пересечения, потом короткие)
     * проверяется против принятых: крест полилиний, сближение коридоров или
     * петля вблизи чужого узла — повод выкинуть. Общий конец — не конфликт
     * (слияние в узле — это хорошо, скрин 1 справа).
     *
     * @param ordered рёбра в порядке рассмотрения (мутируется только чтением)
     * @return ключи выкинутых рёбер
     */
    private static java.util.Set<String> deconflictGeometry(List<RoadEdge> ordered) {
        java.util.Set<String> dropped = new HashSet<>();
        List<RoadEdge> kept = new ArrayList<>();
        Map<String, List<double[]>> polyCache = new HashMap<>();
        for (RoadEdge e : ordered) {
            List<double[]> poly = polyCache.computeIfAbsent(e.key(), k -> toPoly(e));
            boolean conflict = false;
            for (RoadEdge k : kept) {
                if (e.connects(k.fromId()) || e.connects(k.toId())) {
                    // Общий узел: слияние дорог в точке — норма (скрин 1 справа).
                    // Проверяем только петлю: заход чужой геометрии вплотную к
                    // нашему свободному концу (скрин 1 слева).
                    if (loopsAtFreeEnd(poly, e, k)) {
                        conflict = true;
                        break;
                    }
                    continue;
                }
                List<double[]> kPoly = polyCache.computeIfAbsent(k.key(), kk -> toPoly(k));
                if (polysCross(poly, kPoly) || corridorsOverlap(poly, kPoly)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                dropped.add(e.key());
            } else {
                kept.add(e);
            }
        }
        return dropped;
    }

    /** Длина прямой хорды ребра (для детерминированного порядка деконфликтации). */
    private static double chordLength(Map<UUID, RoadNode> nodes, RoadEdge e) {
        RoadNode a = nodes.get(e.fromId());
        RoadNode b = nodes.get(e.toId());
        if (a == null || b == null) {
            return Double.MAX_VALUE;
        }
        return Math.hypot((double) a.x() - b.x(), (double) a.z() - b.z());
    }

    /** Полилиния ребра как пары double (кешируется на время деконфликтации). */
    private static List<double[]> toPoly(RoadEdge e) {
        List<double[]> poly = new ArrayList<>(e.geometry().size());
        for (RoadPoint2D p : e.geometry()) {
            poly.add(new double[]{p.x(), p.z()});
        }
        return poly;
    }

    /** Пересекаются ли полилинии (без учёта общих концов — их здесь нет). */
    private static boolean polysCross(List<double[]> a, List<double[]> b) {
        for (int i = 0; i < a.size() - 1; i++) {
            double[] a1 = a.get(i);
            double[] a2 = a.get(i + 1);
            for (int j = 0; j < b.size() - 1; j++) {
                double[] b1 = b.get(j);
                double[] b2 = b.get(j + 1);
                if (segmentsIntersect(a1[0], a1[1], a2[0], a2[1], b1[0], b1[1], b2[0], b2[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Слипание коридоров: медианное расстояние между полилиниями меньше
     * {@link #CORRIDOR_MERGE_GAP} — это дубль одной дороги (скрин 3),
     * а не два независимых пути.
     */
    private static boolean corridorsOverlap(List<double[]> a, List<double[]> b) {
        double med = medianMinDistance(a, b);
        return med < CORRIDOR_MERGE_GAP;
    }

    /** Медиана минимальных расстояний от сэмплов A до полилинии B. */
    private static double medianMinDistance(List<double[]> a, List<double[]> b) {
        List<double[]> samples = samplePoly(a, CORRIDOR_SAMPLE_STEP);
        if (samples.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double[] dists = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            dists[i] = distToPoly(samples.get(i), b);
        }
        java.util.Arrays.sort(dists);
        return dists[dists.length / 2];
    }

    /** Равномерные сэмплы вдоль полилинии с шагом (плюс концы). */
    private static List<double[]> samplePoly(List<double[]> poly, double step) {
        List<double[]> out = new ArrayList<>();
        if (poly.isEmpty()) {
            return out;
        }
        out.add(poly.get(0));
        double acc = 0.0;
        for (int i = 0; i < poly.size() - 1; i++) {
            double[] p1 = poly.get(i);
            double[] p2 = poly.get(i + 1);
            double seg = Math.hypot(p2[0] - p1[0], p2[1] - p1[1]);
            double t = step - acc;
            while (t < seg) {
                double f = t / seg;
                out.add(new double[]{p1[0] + (p2[0] - p1[0]) * f, p1[1] + (p2[1] - p1[1]) * f});
                t += step;
            }
            acc = (acc + seg) % step;
        }
        double[] last = poly.get(poly.size() - 1);
        double[] prev = out.get(out.size() - 1);
        if (Math.hypot(last[0] - prev[0], last[1] - prev[1]) > 1e-6) {
            out.add(last);
        }
        return out;
    }

    /** Минимальное расстояние от точки до полилинии. */
    private static double distToPoly(double[] p, List<double[]> poly) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < poly.size() - 1; i++) {
            best = Math.min(best, distToSegment(p, poly.get(i), poly.get(i + 1)));
        }
        return best;
    }

    private static double distToSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dz = b[1] - a[1];
        double len2 = dx * dx + dz * dz;
        if (len2 < 1e-9) {
            return Math.hypot(p[0] - a[0], p[1] - a[1]);
        }
        double t = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dz) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(p[0] - (a[0] + dx * t), p[1] - (a[1] + dz * t));
    }

    /**
     * Петля у чужого узла: геометрия ребра подходит вплотную
     * ({@link #CORRIDOR_MIN_GAP}) к свободному концу соседа — узел петли
     * (скрин 1 слева). Общий конец игнорируется: слияние в нём — норма.
     */
    private static boolean loopsAtFreeEnd(List<double[]> poly, RoadEdge e, RoadEdge kept) {
        List<double[]> kPoly = toPoly(kept);
        UUID sharedA = e.fromId().equals(kept.fromId()) || e.fromId().equals(kept.toId()) ? e.fromId() : null;
        UUID sharedB = e.toId().equals(kept.fromId()) || e.toId().equals(kept.toId()) ? e.toId() : null;
        for (double[] p : samplePoly(poly, CORRIDOR_SAMPLE_STEP)) {
            for (double[] q : samplePoly(kPoly, CORRIDOR_SAMPLE_STEP)) {
                double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
                if (d >= CORRIDOR_MIN_GAP) {
                    continue;
                }
                boolean nearShared = (sharedA != null && nearEnd(q, kept, sharedA))
                    || (sharedB != null && nearEnd(q, kept, sharedB));
                if (!nearShared) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Лежит ли точка q вблизи конца ребра с id (в пределах зазора слияния). */
    private static boolean nearEnd(double[] q, RoadEdge e, UUID endId) {
        List<RoadPoint2D> g = e.geometry();
        if (g.isEmpty()) {
            return false;
        }
        RoadPoint2D end = e.fromId().equals(endId) ? g.get(0) : g.get(g.size() - 1);
        return Math.hypot(q[0] - end.x(), q[1] - end.z()) < CORRIDOR_MERGE_GAP;
    }
}
