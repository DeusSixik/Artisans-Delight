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

    /**
     * Фиксированный шаг поля узлов. Раньше шаг подстраивался под bbox городов,
     * а это глобальное решение: поле зависело от того, какие города известны.
     * Фиксированный шаг + хеш ячейки делают узел функцией только (сид, ячейка).
     */
    private static final int FIELD_STEP = 300;
    /** Радиус поиска точки привязки узла вокруг джиттер-точки. */
    private static final int FIELD_SNAP_RADIUS = 24;
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
    /** Зазор слияния: дороги ближе — кандидаты в дубликат (нужно ещё перекрытие). */
    private static final double CORRIDOR_MERGE_GAP = 90.0;
    /** Доля длины для счёта параллельности (медиана по сэмплам). */
    private static final double CORRIDOR_SAMPLE_STEP = 40.0;
    /** Минимальная доля перекрытия проекций, чтобы параллельные дороги считать дублем. */
    private static final double CORRIDOR_OVERLAP_FRACTION = 0.4;
    /** Ячейка spatial-индекса деконфликтации (O(1) соседи вместо O(E²)). */
    private static final double DECONFLICT_CELL = 250.0;

    /** Алгоритм топологии узел–узел. */
    public enum TopologyMode {
        /** Граф относительного соседства: локальный, порядко-независимый (см. {@link MeshTopology}). */
        RNG,
        /** Старый жадный отбор: сортировка, лимит степени, ролл, пришивка компонент. Глобальный. */
        GREEDY
    }

    /** Текущий алгоритм топологии (дебаг-переключатель Ctrl+T на карте). */
    private static volatile TopologyMode topologyMode = TopologyMode.RNG;

    /** Когда считается реальная геометрия дорог (A*). */
    public enum GeometryMode {
        /**
         * Потоково: build() отдаёт узлы и ожидающие рёбра за секунды,
         * дороги досчитываются по мере приближения игрока ({@code RoadStreamer}).
         */
        STREAM,
        /** Вся карта сразу: A* всех рёбер + деконфликтация + гарантия связности (долго). */
        FULL
    }

    /** Текущий режим геометрии (дебаг-переключатель Ctrl+G на карте). */
    private static volatile GeometryMode geometryMode = GeometryMode.STREAM;

    /**
     * Самый длинный непрерывный участок воды на хорде, который ещё допускается.
     * Реки уже́ — их A* перекрывает мостом; длиннее — море/широкое озеро,
     * сухой дороги почти наверняка нет, ребро отсекается ещё до A*.
     */
    private static final double SEA_RUN_MAX = 96.0;
    /** Шаг выборки воды вдоль хорды. */
    private static final double SEA_SAMPLE_STEP = 16.0;

    private RoadNetworkBuilder() {
    }

    public static TopologyMode topologyMode() {
        return topologyMode;
    }

    public static void topologyMode(TopologyMode mode) {
        topologyMode = mode == null ? TopologyMode.RNG : mode;
    }

    public static GeometryMode geometryMode() {
        return geometryMode;
    }

    public static void geometryMode(GeometryMode mode) {
        geometryMode = mode == null ? GeometryMode.STREAM : mode;
    }

    /**
     * Реальная геометрия одного ребра (A* по суше). Детерминирована: зависит
     * только от концов и рельефа, поэтому порядок досчёта рёбер не важен.
     *
     * @return ребро с дорогой или {@code null}, если сухопутной дороги нет
     */
    public static RoadEdge computeEdgeGeometry(RoadNode from, RoadNode to,
                                               ChunkGenerator generator,
                                               LevelHeightAccessor height,
                                               RandomState randomState) {
        // worldSeed в buildEdge не участвует в расчёте пути (только рельеф).
        return buildEdge(from, to, 0L, generator, height, randomState);
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
        // По чанку, а не по блоку: предсказание ставит деревню в центр чанка
        // структуры (cx·16+8), а реальная генерация — в его угол (cx·16).
        // Чанк у обоих один, поэтому id не меняется при слиянии/уточнении.
        String src = worldSeed + ":town:" + Math.floorDiv(x, 16) + "," + Math.floorDiv(z, 16);
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
     * Детерминированный id промежуточного узла от сида и ячейки поля.
     * В отличие от {@link #waypointId(long, int)} не зависит от порядка
     * обхода и размера области — узел ячейки всегда получает один и тот же id.
     */
    public static UUID waypointCellId(long worldSeed, int gx, int gz) {
        String src = worldSeed + ":wp:" + gx + "," + gz;
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
        RoadCalcCache.ensureSeed(worldSeed);

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

        // Ядро — область, за которую отвечает этот расчёт; ореол (LINK_RADIUS)
        // нужен, чтобы рёбра узлов ядра у границы видели всех своих соседей.
        // Так результат внутри ядра не зависит от размера области — основа
        // будущей потоковой генерации по регионам.
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (TownSeed t : townList) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
        }
        final int coreMinX = minX - MESH_MARGIN;
        final int coreMaxX = maxX + MESH_MARGIN;
        final int coreMinZ = minZ - MESH_MARGIN;
        final int coreMaxZ = maxZ + MESH_MARGIN;
        int halo = (int) Math.ceil(LINK_RADIUS);

        TopologyMode mode = topologyMode;
        boolean preview = geometryMode == GeometryMode.STREAM;
        long tStart = System.currentTimeMillis();

        java.util.concurrent.ExecutorService calcPool = java.util.concurrent.Executors.newFixedThreadPool(
            CALC_THREADS, r -> {
                Thread t = new Thread(r, "ArtisansDelight-RoadCalc");
                t.setDaemon(true);
                return t;
            });
        try {
            // 1. Поле промежуточных узлов: функция (сид, ячейка), только суша.
            GenerationProgress.setStage(GenerationProgress.Stage.COARSE_GRAPH);
            List<FieldPoint> field = buildLocalField(
                coreMinX - halo, coreMinZ - halo, coreMaxX + halo, coreMaxZ + halo,
                townList, worldSeed, generator, height, randomState, calcPool);
            for (FieldPoint p : field) {
                UUID nodeId = waypointCellId(worldSeed, p.gx(), p.gz());
                nodes.put(nodeId, new RoadNode(nodeId, RoadNode.Kind.WAYPOINT,
                    "Перекрёсток " + p.gx() + ":" + p.gz(), p.x(), p.y(), p.z()));
            }
            long tField = System.currentTimeMillis();
            LOGGER.info("[RoadNetworkBuilder] Поле меша: {} перекрёстков (шаг {}, ядро+ореол {}) за {} мс",
                field.size(), FIELD_STEP, halo, tField - tStart);

            // 2. Топология узел–узел.
            Set<UUID> townIds = new HashSet<>();
            for (TownSeed t : townList) {
                townIds.add(t.id());
            }
            List<UUID[]> allPairs = mode == TopologyMode.RNG
                ? rngLinks(nodes)
                : selectMeshLinks(nodes, townIds, worldSeed);

            // 2b. Обрезка по ядру: ребро наше, если хоть один конец в ядре.
            // Узлы ореола остаются, только если на них опирается такое ребро.
            List<UUID[]> topoPairs = new ArrayList<>(allPairs.size());
            Set<UUID> referenced = new HashSet<>();
            for (UUID[] pair : allPairs) {
                RoadNode a = nodes.get(pair[0]);
                RoadNode b = nodes.get(pair[1]);
                if (inRect(a, coreMinX, coreMinZ, coreMaxX, coreMaxZ)
                    || inRect(b, coreMinX, coreMinZ, coreMaxX, coreMaxZ)) {
                    topoPairs.add(pair);
                    referenced.add(pair[0]);
                    referenced.add(pair[1]);
                }
            }
            nodes.values().removeIf(n -> !n.isTown()
                && !inRect(n, coreMinX, coreMinZ, coreMaxX, coreMaxZ)
                && !referenced.contains(n.id()));
            long tTopo = System.currentTimeMillis();
            LOGGER.info("[RoadNetworkBuilder] Топология {} : {} рёбер для {} узлов за {} мс",
                mode, topoPairs.size(), nodes.size(), tTopo - tField);

            // 2c. Отсечение морских хорд: длинный сплошной участок воды на
            // прямой — сухой дороги почти наверняка нет. Дёшево (выборка воды),
            // параллельно. Окончательно ребро проверит A*.
            topoPairs = dropSeaChords(topoPairs, nodes, generator, height, randomState, calcPool);
            // Перекрёсток, у которого не осталось рёбер, — островок посреди воды.
            Set<UUID> stillReferenced = new HashSet<>();
            for (UUID[] pair : topoPairs) {
                stillReferenced.add(pair[0]);
                stillReferenced.add(pair[1]);
            }
            int before = nodes.size();
            nodes.values().removeIf(n -> !n.isTown() && !stillReferenced.contains(n.id()));
            long tSea = System.currentTimeMillis();
            LOGGER.info("[RoadNetworkBuilder] Отсечение моря: осталось {} рёбер, убрано {} узлов-островков за {} мс",
                topoPairs.size(), before - nodes.size(), tSea - tTopo);

            // 2d. Потоковый режим: узлы + ожидающие рёбра (хорды-заглушки).
            // Реальные дороги досчитает RoadStreamer по мере приближения игрока.
            if (preview) {
                Map<String, RoadEdge> pendingEdges = new LinkedHashMap<>();
                for (UUID[] pair : topoPairs) {
                    RoadEdge e = straightEdge(nodes.get(pair[0]), nodes.get(pair[1]));
                    pendingEdges.put(e.key(), e);
                }
                GenerationProgress.finish();
                RoadNetwork network = new RoadNetwork(worldSeed, nodes, pendingEdges);
                LOGGER.info("[RoadNetworkBuilder] Каркас сети {} : {} узлов, {} ожидающих рёбер, всего {} мс",
                    mode, network.nodeCount(), network.edgeCount(), System.currentTimeMillis() - tStart);
                return network;
            }

            // 3. Геометрия рёбер ПАРАЛЛЕЛЬНО. Без дороги — ребро отбрасывается.
            Map<String, RoadEdge> edges = buildMeshEdgesParallel(
                topoPairs, nodes, worldSeed, generator, height, randomState, calcPool);

            // 3b. Деконфликтация по РЕАЛЬНОЙ геометрии: хорды топологии не видят
            // кривизну дорог — две хорды расходятся, а полилинии по факту крестятся,
            // идут дублями или петляют. Проверяем полилинии друг о друга: кресты,
            // сближения и петли выкидываются до гарантии связности.
            // Spatial-индекс: соседи ищутся по ячейкам, а не перебором всех пар.
            GenerationProgress.setStage(GenerationProgress.Stage.FINE_GEOMETRY);
            List<RoadEdge> geoList = new ArrayList<>(edges.values());
            geoList.sort(Comparator.comparingDouble((RoadEdge e) -> chordLength(nodes, e))
                .thenComparing(RoadEdge::key));
            java.util.Set<String> dropped = deconflictGeometry(geoList, (done, total) ->
                GenerationProgress.setProgress(done, total, "Деконфликтация"));
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
    // Поле узлов: функция (сид, ячейка), порядко-независимое прореживание
    // =========================================================================

    /** Узел поля: ячейка сетки и привязанная к суше позиция. */
    private record FieldPoint(int gx, int gz, int x, int y, int z) {
    }

    /**
     * Строит поле промежуточных узлов в прямоугольнике.
     *
     * <p><b>Локальность.</b> Кандидат ячейки {@code (gx, gz)} — функция только
     * от сида, ячейки и рельефа:
     * <pre>
     *   base = (gx·S + S/2,  gz·S + S/2)
     *   jitter = (2u − 1) · S · J,   u ∈ [0, 1) из хеша ячейки
     * </pre>
     * где {@code S} — {@link #FIELD_STEP}, {@code J} — {@link #MESH_JITTER}.
     * Затем точка привязывается к удобному рельефу в радиусе
     * {@link #FIELD_SNAP_RADIUS}; вода и окрестность городов отбрасываются.
     *
     * <p><b>Прореживание.</b> Раньше узел отбрасывался, если рядом уже принят
     * другой — результат зависел от порядка обхода. Теперь у каждой ячейки
     * хеш-приоритет, и кандидат выживает, если среди валидных кандидатов
     * ближе {@link #WAYPOINT_SEPARATION} нет более приоритетного. Такие
     * соседи лежат только в соседних ячейках (3×3): максимальный сдвиг от
     * центра ячейки {@code S·J + snap ≈ 129 < (2S − 150)/2}.
     *
     * @return узлы поля в прямоугольнике (порядок: gx, затем gz)
     */
    private static List<FieldPoint> buildLocalField(int minX, int minZ, int maxX, int maxZ,
                                                    List<TownSeed> towns,
                                                    long worldSeed,
                                                    ChunkGenerator generator,
                                                    LevelHeightAccessor height,
                                                    RandomState randomState,
                                                    java.util.concurrent.ExecutorService pool) {
        int gx0 = Math.floorDiv(minX, FIELD_STEP);
        int gx1 = Math.floorDiv(maxX, FIELD_STEP);
        int gz0 = Math.floorDiv(minZ, FIELD_STEP);
        int gz1 = Math.floorDiv(maxZ, FIELD_STEP);
        // +1 кольцо ячеек: нужно только для проверки прореживания у края.
        int rx0 = gx0 - 1;
        int rz0 = gz0 - 1;
        int w = gx1 - gx0 + 3;
        int h = gz1 - gz0 + 3;
        FieldPoint[][] cand = new FieldPoint[w][h];

        // Кандидаты считаются параллельно по столбцам: ячейки независимы.
        java.util.concurrent.atomic.AtomicInteger cols = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>(w);
        for (int ix = 0; ix < w; ix++) {
            final int col = ix;
            futures.add(pool.submit(() -> {
                int gx = rx0 + col;
                for (int iz = 0; iz < h; iz++) {
                    cand[col][iz] = fieldCandidate(gx, rz0 + iz, towns, worldSeed,
                        generator, height, randomState);
                }
                GenerationProgress.setProgress(cols.incrementAndGet(), w, "Поле узлов");
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Field calc interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Field calc failed", e.getCause());
            }
        }

        double sep2 = WAYPOINT_SEPARATION * WAYPOINT_SEPARATION;
        List<FieldPoint> out = new ArrayList<>();
        for (int ix = 1; ix < w - 1; ix++) {
            for (int iz = 1; iz < h - 1; iz++) {
                FieldPoint c = cand[ix][iz];
                if (c == null) {
                    continue;
                }
                long pc = fieldPriority(worldSeed, c);
                boolean suppressed = false;
                for (int dx = -1; dx <= 1 && !suppressed; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        FieldPoint o = cand[ix + dx][iz + dz];
                        if (o == null) {
                            continue;
                        }
                        double ddx = (double) o.x() - c.x();
                        double ddz = (double) o.z() - c.z();
                        if (ddx * ddx + ddz * ddz >= sep2) {
                            continue;
                        }
                        if (fieldPriority(worldSeed, o) > pc) {
                            suppressed = true;
                            break;
                        }
                    }
                }
                if (!suppressed) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Кандидат ячейки или {@code null} (вода / рядом город). */
    private static FieldPoint fieldCandidate(int gx, int gz,
                                             List<TownSeed> towns,
                                             long worldSeed,
                                             ChunkGenerator generator,
                                             LevelHeightAccessor height,
                                             RandomState randomState) {
        // Сырой кандидат (рельеф) кэшируется: он не зависит от городов.
        // Отступ от городов применяется поверх — города могут добавляться.
        int[] raw = RoadCalcCache.field(gx, gz);
        if (raw == null) {
            raw = rawFieldCandidate(gx, gz, worldSeed, generator, height, randomState);
            RoadCalcCache.putField(gx, gz, raw);
        }
        if (raw[5] != 0) {
            return null; // вода
        }
        if (nearTown(towns, raw[0], raw[1], TOWN_CLEARANCE)
            || nearTown(towns, raw[2], raw[4], TOWN_CLEARANCE)) {
            return null;
        }
        return new FieldPoint(gx, gz, raw[2], raw[3], raw[4]);
    }

    /** Кандидат ячейки только по рельефу: {@code {jx, jz, sx, sy, sz, water}}. */
    private static int[] rawFieldCandidate(int gx, int gz,
                                           long worldSeed,
                                           ChunkGenerator generator,
                                           LevelHeightAccessor height,
                                           RandomState randomState) {
        long hc = MeshTopology.cellHash(worldSeed, gx, gz);
        double u1 = (hc >>> 11) * 0x1.0p-53;
        double u2 = (MeshTopology.mix64(hc) >>> 11) * 0x1.0p-53;
        int jx = gx * FIELD_STEP + FIELD_STEP / 2
            + (int) Math.round((u1 * 2.0 - 1.0) * FIELD_STEP * MESH_JITTER);
        int jz = gz * FIELD_STEP + FIELD_STEP / 2
            + (int) Math.round((u2 * 2.0 - 1.0) * FIELD_STEP * MESH_JITTER);
        BlockPos snapped = SectorWaypointPicker.pickWaypointNear(
            jx, jz, FIELD_SNAP_RADIUS, hc, generator, height, randomState);
        int sx = snapped.getX();
        int sz = snapped.getZ();
        boolean water = TerrainSampler.isWater(generator, height, randomState, sx, sz);
        return new int[]{jx, jz, sx, snapped.getY(), sz, water ? 1 : 0};
    }

    /** Приоритет прореживания: хеш ячейки; ничья невозможна для разных ячеек на практике. */
    private static long fieldPriority(long worldSeed, FieldPoint p) {
        return MeshTopology.mix64(MeshTopology.cellHash(worldSeed, p.gx(), p.gz()) ^ 0x5DEECE66DL);
    }

    /**
     * Рёбра RNG по всем узлам. Порядок узлов фиксирован (сортировка по id),
     * поэтому и порядок рёбер детерминирован.
     */
    private static List<UUID[]> rngLinks(Map<UUID, RoadNode> nodes) {
        List<UUID> ids = new ArrayList<>(nodes.keySet());
        ids.sort(Comparator.comparing(UUID::toString));
        double[] xs = new double[ids.size()];
        double[] zs = new double[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            RoadNode n = nodes.get(ids.get(i));
            xs[i] = n.x();
            zs[i] = n.z();
        }
        List<int[]> idx = MeshTopology.relativeNeighborhoodEdges(xs, zs, MIN_LINK_DIST, LINK_RADIUS);
        List<UUID[]> out = new ArrayList<>(idx.size());
        for (int[] e : idx) {
            out.add(new UUID[]{ids.get(e[0]), ids.get(e[1])});
        }
        return out;
    }

    private static boolean inRect(RoadNode n, int minX, int minZ, int maxX, int maxZ) {
        return n != null && n.x() >= minX && n.x() <= maxX && n.z() >= minZ && n.z() <= maxZ;
    }

    /** Ожидающее ребро: прямая хорда-заглушка до расчёта реальной дороги. */
    private static RoadEdge straightEdge(RoadNode a, RoadNode b) {
        List<RoadPoint2D> g = List.of(new RoadPoint2D(a.x(), a.z(), false),
            new RoadPoint2D(b.x(), b.z(), false));
        double d = Math.hypot((double) a.x() - b.x(), (double) a.z() - b.z());
        return new RoadEdge(a.id(), b.id(), g, false, d, true);
    }

    /**
     * Убирает рёбра, чья прямая хорда пересекает воду сплошным участком
     * длиннее {@link #SEA_RUN_MAX}. Проверка параллельная, порядок рёбер
     * в результате сохраняется.
     */
    private static List<UUID[]> dropSeaChords(List<UUID[]> pairs,
                                              Map<UUID, RoadNode> nodes,
                                              ChunkGenerator generator,
                                              LevelHeightAccessor height,
                                              RandomState randomState,
                                              java.util.concurrent.ExecutorService pool) {
        int total = pairs.size();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>(total);
        for (UUID[] pair : pairs) {
            RoadNode a = nodes.get(pair[0]);
            RoadNode b = nodes.get(pair[1]);
            futures.add(pool.submit(() -> {
                boolean sea;
                if (a == null || b == null) {
                    sea = true;
                } else {
                    Boolean cached = RoadCalcCache.sea(a, b);
                    if (cached != null) {
                        sea = cached;
                    } else {
                        sea = chordCrossesSea(a, b, generator, height, randomState);
                        RoadCalcCache.putSea(a, b, sea);
                    }
                }
                int d = done.incrementAndGet();
                if (d % 20 == 0 || d == total) {
                    GenerationProgress.setProgress(d, total, "Проверка воды");
                }
                return sea;
            }));
        }
        List<UUID[]> out = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            try {
                if (!futures.get(i).get()) {
                    out.add(pairs.get(i));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Sea check interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new RuntimeException("Sea check failed", e.getCause());
            }
        }
        return out;
    }

    /**
     * Есть ли на хорде {@code a–b} сплошной участок воды длиннее
     * {@link #SEA_RUN_MAX}. Выборка с шагом {@link #SEA_SAMPLE_STEP}, концы
     * не проверяются (узлы и так на суше):
     * <pre>
     *   p(t) = a + t·(b − a),   t = i / n,   n = ⌈|ab| / step⌉
     *   run  += step, пока p(t) в воде;  иначе run = 0
     * </pre>
     */
    private static boolean chordCrossesSea(RoadNode a, RoadNode b,
                                           ChunkGenerator generator,
                                           LevelHeightAccessor height,
                                           RandomState randomState) {
        double len = Math.hypot((double) a.x() - b.x(), (double) a.z() - b.z());
        int n = Math.max(2, (int) Math.ceil(len / SEA_SAMPLE_STEP));
        double step = len / n;
        double run = 0.0;
        for (int i = 1; i < n; i++) {
            double t = (double) i / n;
            int x = (int) Math.round(a.x() + (b.x() - a.x()) * t);
            int z = (int) Math.round(a.z() + (b.z() - a.z()) * t);
            if (TerrainSampler.isWater(generator, height, randomState, x, z)) {
                run += step;
                if (run > SEA_RUN_MAX) {
                    return true;
                }
            } else {
                run = 0.0;
            }
        }
        return false;
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
        // Живой spatial-индекс: ремонт сирот идёт через него, а не перебором всех пар.
        Map<Long, List<RoadEdge>> liveGrid = new HashMap<>(liveEdges.size() * 2 + 16);
        Map<String, List<double[]>> livePolys = new HashMap<>(liveEdges.size() * 2 + 16);
        Map<String, double[]> liveBoxes = new HashMap<>(liveEdges.size() * 2 + 16);
        for (RoadEdge e : liveEdges) {
            List<double[]> poly = toPoly(e);
            livePolys.put(e.key(), poly);
            double[] box = polyBbox(poly);
            liveBoxes.put(e.key(), box);
            insertIntoGrid(liveGrid, e, box);
        }
        int orphanDone = 0;
        int orphanTotal = orphans.size();
        for (UUID orphan : orphans) {
            orphanDone++;
            GenerationProgress.setProgress(orphanDone, orphanTotal, "Ремонт сирот");
            RoadEdge fixed = repairOrphan(orphan, townIds, nodes, pos, degree, liveEdges,
                liveGrid, livePolys, liveBoxes,
                worldSeed, generator, height, randomState);
            if (fixed != null) {
                edges.put(fixed.key(), fixed);
                liveEdges.add(fixed);
                List<double[]> poly = toPoly(fixed);
                livePolys.put(fixed.key(), poly);
                double[] box = polyBbox(poly);
                liveBoxes.put(fixed.key(), box);
                insertIntoGrid(liveGrid, fixed, box);
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
                                         Map<Long, List<RoadEdge>> liveGrid,
                                         Map<String, List<double[]>> livePolys,
                                         Map<String, double[]> liveBoxes,
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
            if (edge != null && !conflictsLiveGeometry(edge, liveEdges, liveGrid, livePolys, liveBoxes)) {
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
        int townCompDone = 0;
        int townCompTotal = townComponents.size();
        for (List<UUID> members : townComponents) {
            townCompDone++;
            GenerationProgress.setProgress(townCompDone, townCompTotal, "Сшивка городов");
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
     * Соседи берутся из spatial-индекса, полилинии/bbox — из кэшей.
     */
    private static boolean conflictsLiveGeometry(RoadEdge edge, List<RoadEdge> liveEdges) {
        return conflictsLiveGeometry(edge, liveEdges, null, null, null);
    }

    private static boolean conflictsLiveGeometry(RoadEdge edge,
                                                 List<RoadEdge> liveEdges,
                                                 Map<Long, List<RoadEdge>> liveGrid,
                                                 Map<String, List<double[]>> livePolys,
                                                 Map<String, double[]> liveBoxes) {
        List<double[]> poly = livePolys != null
            ? livePolys.computeIfAbsent(edge.key(), k -> toPoly(edge))
            : toPoly(edge);
        double[] box = liveBoxes != null
            ? liveBoxes.computeIfAbsent(edge.key(), k -> polyBbox(poly))
            : polyBbox(poly);
        java.util.Collection<RoadEdge> candidates;
        if (liveGrid != null) {
            java.util.Set<RoadEdge> neighbours = collectNeighbours(liveGrid, box, CORRIDOR_MERGE_GAP);
            if (!neighbours.contains(edge)) {
                // Рёбра с общим узлом могут лежать вне ячеек bbox — их проверяем отдельно.
                List<RoadEdge> withShared = new ArrayList<>(neighbours.size() + 4);
                for (RoadEdge k : liveEdges) {
                    if (k.key().equals(edge.key())) {
                        continue;
                    }
                    if (edge.connects(k.fromId()) || edge.connects(k.toId())) {
                        withShared.add(k);
                    }
                }
                withShared.addAll(neighbours);
                withShared.sort(Comparator.comparing(RoadEdge::key));
                candidates = withShared;
            } else {
                List<RoadEdge> sorted = new ArrayList<>(neighbours);
                sorted.sort(Comparator.comparing(RoadEdge::key));
                candidates = sorted;
            }
        } else {
            candidates = liveEdges;
        }
        for (RoadEdge k : candidates) {
            if (k.key().equals(edge.key())) {
                continue;
            }
            if (edge.connects(k.fromId()) || edge.connects(k.toId())) {
                if (loopsAtFreeEnd(poly, edge, k, livePolys)) {
                    return true;
                }
                continue;
            }
            List<double[]> kPoly = livePolys != null
                ? livePolys.computeIfAbsent(k.key(), kk -> toPoly(k))
                : toPoly(k);
            double[] kBox = liveBoxes != null
                ? liveBoxes.computeIfAbsent(k.key(), kk -> polyBbox(kPoly))
                : polyBbox(kPoly);
            if (!boxesOverlap(box, kBox, CORRIDOR_MERGE_GAP)) {
                continue;
            }
            if (polysCross(poly, kPoly) || corridorsOverlap(poly, kPoly)) {
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
     * @param progress обратный вызов прогресса (done, total), может быть {@code null}
     * @return ключи выкинутых рёбер
     */
    private static java.util.Set<String> deconflictGeometry(List<RoadEdge> ordered,
                                                            java.util.function.BiConsumer<Integer, Integer> progress) {
        java.util.Set<String> dropped = new HashSet<>();
        List<RoadEdge> kept = new ArrayList<>();
        // Полилинии, bbox и ячейки индекса — всё предрасчитано один раз.
        Map<String, List<double[]>> polyCache = new HashMap<>(ordered.size() * 2);
        Map<String, double[]> bboxCache = new HashMap<>(ordered.size() * 2);
        for (RoadEdge e : ordered) {
            List<double[]> poly = toPoly(e);
            polyCache.put(e.key(), poly);
            bboxCache.put(e.key(), polyBbox(poly));
        }
        Map<Long, List<RoadEdge>> grid = new HashMap<>(kept.size() * 2 + 16);
        int done = 0;
        int total = ordered.size();
        for (RoadEdge e : ordered) {
            done++;
            if (progress != null && (done % 10 == 0 || done == total)) {
                progress.accept(done, total);
            }
            List<double[]> poly = polyCache.get(e.key());
            double[] box = bboxCache.get(e.key());
            boolean conflict = false;
            // Соседи только из перекрывающихся ячеек (с запасом на коридор).
            java.util.Set<RoadEdge> neighbours = collectNeighbours(grid, box, CORRIDOR_MERGE_GAP);
            List<RoadEdge> sortedNeighbours = new ArrayList<>(neighbours);
            sortedNeighbours.sort(Comparator.comparing(RoadEdge::key));
            for (RoadEdge k : sortedNeighbours) {
                if (e.connects(k.fromId()) || e.connects(k.toId())) {
                    // Общий узел: слияние дорог в точке — норма (скрин 1 справа).
                    // Проверяем только петлю: заход чужой геометрии вплотную к
                    // нашему свободному концу (скрин 1 слева).
                    if (loopsAtFreeEnd(poly, e, k, polyCache)) {
                        conflict = true;
                        break;
                    }
                    continue;
                }
                List<double[]> kPoly = polyCache.get(k.key());
                if (!boxesOverlap(box, bboxCache.get(k.key()), CORRIDOR_MERGE_GAP)) {
                    continue;
                }
                if (polysCross(poly, kPoly) || corridorsOverlap(poly, kPoly)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                dropped.add(e.key());
            } else {
                kept.add(e);
                insertIntoGrid(grid, e, box);
            }
        }
        return dropped;
    }

    /** Bbox полилинии [minX, minY, maxX, maxY]. */
    private static double[] polyBbox(List<double[]> poly) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] p : poly) {
            if (p[0] < minX) minX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] > maxY) maxY = p[1];
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /** Пересекаются ли bbox с запасом margin. */
    private static boolean boxesOverlap(double[] a, double[] b, double margin) {
        if (a == null || b == null) {
            return true;
        }
        return a[2] + margin >= b[0] && b[2] + margin >= a[0]
            && a[3] + margin >= b[1] && b[3] + margin >= a[1];
    }

    private static long cellKey(long cx, long cz) {
        return (cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Кладёт ребро во все ячейки, перекрываемые его bbox (+запас на коридор). */
    private static void insertIntoGrid(Map<Long, List<RoadEdge>> grid, RoadEdge e, double[] box) {
        long x0 = (long) Math.floor((box[0] - CORRIDOR_MERGE_GAP) / DECONFLICT_CELL);
        long x1 = (long) Math.floor((box[2] + CORRIDOR_MERGE_GAP) / DECONFLICT_CELL);
        long z0 = (long) Math.floor((box[1] - CORRIDOR_MERGE_GAP) / DECONFLICT_CELL);
        long z1 = (long) Math.floor((box[3] + CORRIDOR_MERGE_GAP) / DECONFLICT_CELL);
        for (long cx = x0; cx <= x1; cx++) {
            for (long cz = z0; cz <= z1; cz++) {
                grid.computeIfAbsent(cellKey(cx, cz), k -> new ArrayList<>()).add(e);
            }
        }
    }

    /** Собирает уникальных соседей из ячеек, перекрываемых bbox (+запас). */
    private static java.util.Set<RoadEdge> collectNeighbours(Map<Long, List<RoadEdge>> grid,
                                                             double[] box, double margin) {
        java.util.Set<RoadEdge> out = new HashSet<>();
        long x0 = (long) Math.floor((box[0] - margin) / DECONFLICT_CELL);
        long x1 = (long) Math.floor((box[2] + margin) / DECONFLICT_CELL);
        long z0 = (long) Math.floor((box[1] - margin) / DECONFLICT_CELL);
        long z1 = (long) Math.floor((box[3] + margin) / DECONFLICT_CELL);
        for (long cx = x0; cx <= x1; cx++) {
            for (long cz = z0; cz <= z1; cz++) {
                List<RoadEdge> cell = grid.get(cellKey(cx, cz));
                if (cell != null) {
                    out.addAll(cell);
                }
            }
        }
        return out;
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
     * Слипание коридоров: дороги идут рядом (медиана &lt; {@link #CORRIDOR_MERGE_GAP})
     * И перекрываются вдоль ({@link #CORRIDOR_OVERLAP_FRACTION}) — это дубль одной
     * дороги (скрин 3). Просто близкие, но расходящиеся пути (Y-развилка) не трогаем:
     * у них перекрытие проекций маленькое.
     */
    private static boolean corridorsOverlap(List<double[]> a, List<double[]> b) {
        double med = medianMinDistance(a, b);
        if (med >= CORRIDOR_MERGE_GAP) {
            return false;
        }
        return projectionOverlap(a, b) >= CORRIDOR_OVERLAP_FRACTION
            || projectionOverlap(b, a) >= CORRIDOR_OVERLAP_FRACTION;
    }

    /**
     * Доля сэмплов A, чья проекция падает на отрезки B в пределах зазора слияния.
     * Параллельный дубль даёт ~1.0, Y-развилка — маленькую долю (общий ствол).
     */
    private static double projectionOverlap(List<double[]> a, List<double[]> b) {
        List<double[]> samples = samplePoly(a, CORRIDOR_SAMPLE_STEP);
        if (samples.isEmpty() || b.size() < 2) {
            return 0.0;
        }
        int hit = 0;
        for (double[] s : samples) {
            if (distToPoly(s, b) < CORRIDOR_MERGE_GAP) {
                hit++;
            }
        }
        return (double) hit / samples.size();
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
    private static boolean loopsAtFreeEnd(List<double[]> poly, RoadEdge e, RoadEdge kept,
                                           Map<String, List<double[]>> polyCache) {
        List<double[]> kPoly = polyCache != null
            ? polyCache.computeIfAbsent(kept.key(), kk -> toPoly(kept))
            : toPoly(kept);
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
