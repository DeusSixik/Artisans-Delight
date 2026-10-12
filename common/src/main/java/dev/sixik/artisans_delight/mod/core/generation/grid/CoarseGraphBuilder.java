package dev.sixik.artisans_delight.mod.core.generation.grid;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;


/**
 * Строитель грубого графа узлов между городами.
 * 
 * <p>Этот класс отвечает за топологию сети — КТО с КЕМ соединён.
 * Физическая геометрия дорог (КАК проходит дорога) обрабатывается отдельно в {@link FinePathfinder}.
 * 
 * <p><b>Алгоритм построения:</b>
 * <ol>
 *   <li>Строим MST (Minimum Spanning Tree) между городами</li>
 *   <li>Для каждого ребра MST создаём цепочку промежуточных узлов (waypoints)</li>
 *   <li>Узлы размещаются в секторах вдоль прямой линии между городами</li>
 *   <li>Точные координаты узлов определяются через {@link SectorWaypointPicker}</li>
 * </ol>
 * 
 * <p><b>Гарантии:</b>
 * <ul>
 *   <li>Города НЕ соединяются напрямую — между ними всегда есть промежуточные узлы</li>
 *   <li>Узлы детерминированы — для одного сида мира всегда одинаковые</li>
 *   <li>Граф связен — все города достижимы друг от друга</li>
 * </ul>
 * 
 * <p><b>Пример использования:</b>
 * <pre>{@code
 * List<WorldObject> towns = manager.getAllByType(KnotType.TOWN);
 * CoarseGraphBuilder.buildNetwork(towns, manager, worldSeed, gen, h, state);
 * }</pre>
 */
public final class CoarseGraphBuilder {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(CoarseGraphBuilder.class);
    
    /**
     * Базовое расстояние между промежуточными узлами (в блоках).
     * Используется для вычисления МАКСИМАЛЬНОГО числа узлов на маршруте.
     */
    public static final int NODE_SPACING = 400;
    
    /**
     * Минимальное количество узлов между любой парой городов.
     * Даже если города рядом, между ними будет хотя бы один узел.
     */
    public static final int MIN_NODES_BETWEEN_TOWNS = 1;
    
    /**
     * Минимальное количество узлов на ДЛИННЫХ маршрутах.
     * Если максимально возможное число узлов больше {@link #MIN_NODES_LONG_ROUTE},
     * итоговое количество случайно выбирается в диапазоне
     * [{@link #MIN_NODES_LONG_ROUTE}, maxNodes].
     */
    public static final int MIN_NODES_LONG_ROUTE = 3;
    
    /**
     * Порог «длинного» маршрута: если maxNodes больше этого значения,
     * применяется рандомизация количества узлов.
     */
    public static final int RANDOMIZE_THRESHOLD = 3;
    
    /**
     * Максимальное расстояние для прямого соединения городов (в блоках).
     * Если расстояние больше, ищется промежуточный город.
     */
    public static final double MAX_DIRECT_DISTANCE = 4000.0;
    
    /**
     * Максимальный шаг грубого A* (в блоках).
     * Большой шаг = быстрее, но менее точно. Для грубого слоя этого достаточно.
     */
    public static final int COARSE_GRID_STEP = 48;
    
    private CoarseGraphBuilder() {
    }
    
    /**
     * Строит полную сеть узлов между городами.
     * 
     * <p>Этот метод — главная точка входа. Он:
     * <ol>
     *   <li>Вычисляет MST между городами</li>
     *   <li>Для каждого ребра MST создаёт цепочку промежуточных узлов</li>
     *   <li>Регистрирует узлы в {@link WorldObjectManager}</li>
     * </ol>
     * 
     * @param towns Список городов для соединения
     * @param manager Менеджер узлов мира
     * @param worldSeed Сид мира (для детерминированности)
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     */
    public static void buildNetwork(Collection<WorldObject> towns,
                                   WorldObjectManager manager,
                                   long worldSeed,
                                   ChunkGenerator generator,
                                   LevelHeightAccessor heightAccessor,
                                   RandomState randomState) {
        
        if (towns.size() < 2) {
            LOGGER.warn("[CoarseGraphBuilder] Недостаточно городов для построения сети: {}", towns.size());
            return; // Нечего соединять
        }
        
        LOGGER.info("[CoarseGraphBuilder] Начинаю построение грубого графа для {} городов", towns.size());
        
        // 1. Строим MST между городами
        long startMST = System.currentTimeMillis();
        List<TownEdge> mstEdges = calculateMST(towns);
        long mstTime = System.currentTimeMillis() - startMST;
        LOGGER.info("[CoarseGraphBuilder] MST построен: {} рёбер за {} мс", mstEdges.size(), mstTime);
        
        // 2. Для каждого ребра создаём цепочку узлов
        int totalNodes = 0;
        for (int i = 0; i < mstEdges.size(); i++) {
            TownEdge edge = mstEdges.get(i);
            BlockPos posA = getPosition(edge.townA);
            BlockPos posB = getPosition(edge.townB);
            
            String nameA = edge.townA.getSingle(dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent.class)
                .map(m -> m.name.get()).orElse("UNKNOWN");
            String nameB = edge.townB.getSingle(dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent.class)
                .map(m -> m.name.get()).orElse("UNKNOWN");
            
            LOGGER.info("[CoarseGraphBuilder] Обрабатываю ребро {}/{}: '{}' [{}, {}] -> '{}' [{}, {}] (расстояние: {} блоков)", 
                i + 1, mstEdges.size(), 
                nameA, posA.getX(), posA.getZ(),
                nameB, posB.getX(), posB.getZ(),
                (int) edge.distance);
            
            dev.sixik.artisans_delight.mod.core.generation.path.GenerationProgress.setProgress(
                i + 1, mstEdges.size(),
                nameA + " -> " + nameB
            );
            
            int nodes = createNodeChain(edge.townA, edge.townB, manager, worldSeed, generator, heightAccessor, randomState, totalNodes);
            totalNodes += nodes;
            LOGGER.info("[CoarseGraphBuilder] Создано {} промежуточных узлов для '{}' -> '{}'", nodes, nameA, nameB);
        }
        
        LOGGER.info("[CoarseGraphBuilder] Построение грубого графа завершено. Всего создано {} промежуточных узлов", totalNodes);
    }
    
    /**
     * Создаёт цепочку промежуточных узлов между двумя городами.
     * 
     * <p><b>Алгоритм:</b>
     * <ol>
     *   <li>Строим дорогу через {@code CoarsePathfinder.findLandRoad()} (учитывает рельеф, обходит воду)</li>
     *   <li>Если дороги нет — города разделены океаном, пропускаем</li>
     *   <li>Вычисляем длину дороги и количество узлов: {@code max(MIN_NODES_BETWEEN_TOWNS, roadLength / NODE_SPACING)}</li>
     *   <li>Размещаем узлы равномерно вдоль дороги (не по прямой!)</li>
     *   <li>Для каждого узла находим сектор и вызываем {@link SectorWaypointPicker}</li>
     *   <li>Соединяем цепочку: Town A -> Node1 -> Node2 -> ... -> Town B</li>
     * </ol>
     * 
     * @param townA Начальный город
     * @param townB Конечный город
     * @param manager Менеджер узлов
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     * @param startIndex Порядковый номер для нумерации имён узлов (сквозной по сети)
     */
    private static int createNodeChain(WorldObject townA,
                                       WorldObject townB,
                                       WorldObjectManager manager,
                                       long worldSeed,
                                       ChunkGenerator generator,
                                       LevelHeightAccessor heightAccessor,
                                       RandomState randomState,
                                       int startIndex) {
        
        BlockPos posA = getPosition(townA);
        BlockPos posB = getPosition(townB);
        
        if (posA == null || posB == null) {
            LOGGER.warn("[CoarseGraphBuilder] Один из городов не имеет позиции!");
            return 0;
        }
        
        // НОВОЕ: Сначала строим ГРУБУЮ дорогу через CoarsePathfinder (быстрый крупный шаг)
        // Детальная геометрия будет построена позже в тонком слое (FinePathfinder).
        it.unimi.dsi.fastutil.objects.ObjectList<dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D> roadPath = 
            dev.sixik.artisans_delight.mod.core.generation.path.CoarsePathfinder.findCoarseLandRoad(
                generator, heightAccessor, randomState,
                posA.getX(), posA.getZ(), posB.getX(), posB.getZ(),
                COARSE_GRID_STEP
            );
        
        if (roadPath.isEmpty()) {
            // Города разделены океаном — пропускаем (порты обработает SeaRouteBuilder)
            String nameA = townA.getSingle(dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent.class)
                .map(m -> m.name.get()).orElse("UNKNOWN");
            String nameB = townB.getSingle(dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent.class)
                .map(m -> m.name.get()).orElse("UNKNOWN");
            LOGGER.warn("[CoarseGraphBuilder] Не удалось построить сухопутную дорогу между '{}' [{}, {}] и '{}' [{}, {}] (возможно, океан или слишком большое расстояние). Пропускаю.",
                nameA, posA.getX(), posA.getZ(),
                nameB, posB.getX(), posB.getZ());
            return 0;
        }
        
        // Вычисляем длину дороги
        double roadLength = 0.0;
        for (int i = 0; i < roadPath.size() - 1; i++) {
            roadLength += roadPath.get(i).distance(roadPath.get(i + 1));
        }
        
        // МАКСИМАЛЬНО возможное число узлов
        int maxNodes = (int) Math.ceil(roadLength / NODE_SPACING);
        
        // РАНДОМИЗАЦИЯ количества узлов:
        // - Если городов рядом (maxNodes <= 2) — не трогаем, оставляем как есть (1-2 узла).
        // - На длинных маршрутах (maxNodes > RANDOMIZE_THRESHOLD) — минимум MIN_NODES_LONG_ROUTE,
        //   итоговое число случайно в диапазоне [MIN_NODES_LONG_ROUTE, maxNodes].
        Random random = new Random(worldSeed ^ (townA.id().hashCode() * 31L + townB.id().hashCode()));
        
        int nodeCount;
        if (maxNodes <= RANDOMIZE_THRESHOLD) {
            // Близкие города: 1-2 узла, не рандомизируем (детерминированно)
            nodeCount = Math.max(MIN_NODES_BETWEEN_TOWNS, maxNodes);
        } else {
            // Длинный маршрут: рандомизируем в диапазоне [MIN_NODES_LONG_ROUTE, maxNodes]
            int minNodes = MIN_NODES_LONG_ROUTE;
            nodeCount = minNodes + random.nextInt(maxNodes - minNodes + 1);
        }
        
        LOGGER.info("[CoarseGraphBuilder] Дорога {} блоков: maxNodes={}, выбрано nodeCount={}", 
            (int) roadLength, maxNodes, nodeCount);
        
        // Создаём узлы вдоль дороги
        List<WorldObject> chain = new ArrayList<>();
        chain.add(townA);
        
        // РАНДОМИЗИРОВАННОЕ размещение узлов вдоль дороги:
        // Делим дорогу на (nodeCount + 1) сегментов и размещаем узел в СЛУЧАЙНОЙ точке внутри сегмента.
        // Это делает расстояния между узлами неравномерными, но в пределах одного сегмента.
        for (int i = 1; i <= nodeCount; i++) {
            // Базовые границы сегмента i: [ (i-1)*segLen, i*segLen ]
            double segLen = roadLength / (nodeCount + 1);
            double segStart = segLen * (i - 1);
            double segEnd = segLen * i;
            
            // Случайная точка внутри сегмента (отступ 15% с каждой стороны, чтобы узлы не слипались)
            double margin = segLen * 0.15;
            double lo = segStart + margin;
            double hi = segEnd - margin;
            double targetDistance = lo + random.nextDouble() * Math.max(0.0, hi - lo);
            
            // Находим точку на дороге на расстоянии targetDistance от начала
            double accumulatedDist = 0.0;
            int segmentIndex = 0;
            
            for (int j = 0; j < roadPath.size() - 1; j++) {
                double segmentDist = roadPath.get(j).distance(roadPath.get(j + 1));
                if (accumulatedDist + segmentDist >= targetDistance) {
                    segmentIndex = j;
                    break;
                }
                accumulatedDist += segmentDist;
            }
            
            // Интерполируем позицию узла на найденном сегменте
            dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D p1 = roadPath.get(segmentIndex);
            dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D p2 = roadPath.get(Math.min(segmentIndex + 1, roadPath.size() - 1));
            
            double segmentDist = p1.distance(p2);
            double t = segmentDist > 0 ? (targetDistance - accumulatedDist) / segmentDist : 0.0;
            t = Math.max(0.0, Math.min(1.0, t)); // Clamp to [0, 1]
            
            int x = (int) Math.round(p1.x() + (p2.x() - p1.x()) * t);
            int z = (int) Math.round(p1.z() + (p2.z() - p1.z()) * t);
            
            // Ищем вейпоинт РЯДОМ с точкой на дороге (радиус 24 блока),
            // чтобы узел не «прыгал» в случайный угол сектора и не создавал зигзаги.
            BlockPos waypointPos = SectorWaypointPicker.pickWaypointNear(
                x, z, 24,
                worldSeed ^ ((long) x * 31L + z),
                generator, heightAccessor, randomState
            );
            
            if (waypointPos == null) {
                // Fallback: используем саму точку дороги (гарантированно на суше)
                waypointPos = new BlockPos(x, posA.getY(), z);
            }
            
            // Создаём узел (нумеруем последовательно по всей сети через счётчик)
            WorldObject node = createWaypointNode(waypointPos, manager, startIndex + i);
            chain.add(node);
        }
        
        chain.add(townB);
        
        // Соединяем цепочку
        for (int i = 0; i < chain.size() - 1; i++) {
            WorldObject from = chain.get(i);
            WorldObject to = chain.get(i + 1);
            
            // Регистрируем соседство (без геометрии — она будет добавлена позже)
            from.getSingle(KnotConnectionComponent.class).ifPresent(conn -> {
                conn.addNeighbor(to.id());
            });
            
            to.getSingle(KnotConnectionComponent.class).ifPresent(conn -> {
                conn.addNeighbor(from.id());
            });
        }
        
        return nodeCount;
    }
    
    /**
     * Создаёт узел-вейпоинт в заданной позиции.
     * 
     * <p>Узлу присваивается человекочитаемое имя «Перекрёсток #N», где N —
     * порядковый номер создаваемого промежуточного узла (для удобства на карте).
     * 
     * @param position Координаты узла
     * @param manager Менеджер узлов
     * @param index Порядковый номер узла (для имени)
     * @return Созданный узел
     */
    private static WorldObject createWaypointNode(BlockPos position, WorldObjectManager manager, int index) {
        WorldObject node = manager.create();
        
        KnotMetaComponent meta = new KnotMetaComponent(
            KnotType.WAYPOINT,
            position
        );
        meta.name.set("Перекрёсток #" + index);
        node.attach(meta);
        
        node.attach(new KnotRoutesComponent());
        node.attach(new KnotConnectionComponent());
        
        return node;
    }
    
    /**
     * Вычисляет Minimum Spanning Tree между городами.
     * 
     * <p>Использует алгоритм Прима (Prim's algorithm) для построения MST.
     * Гарантирует минимальную общую длину дорог при сохранении связности графа.
     * 
     * @param towns Список городов
     * @return Список рёбер MST
     */
    private static List<TownEdge> calculateMST(Collection<WorldObject> towns) {
        List<WorldObject> townList = new ArrayList<>(towns);
        if (townList.size() < 2) {
            return Collections.emptyList();
        }
        
        List<TownEdge> mstEdges = new ArrayList<>();
        Set<WorldObject> visited = new HashSet<>();
        PriorityQueue<TownEdge> pq = new PriorityQueue<>(Comparator.comparingDouble(e -> e.distance));
        
        // Начинаем с первого города
        WorldObject start = townList.get(0);
        visited.add(start);
        
        // Добавляем все рёбра от стартового города
        for (WorldObject town : townList) {
            if (!town.equals(start)) {
                BlockPos posA = getPosition(start);
                BlockPos posB = getPosition(town);
                if (posA != null && posB != null) {
                    double dist = Math.sqrt(posA.distSqr(posB));
                    pq.add(new TownEdge(start, town, dist));
                }
            }
        }
        
        // Алгоритм Прима
        while (!pq.isEmpty() && visited.size() < townList.size()) {
            TownEdge edge = pq.poll();
            
            if (visited.contains(edge.townB)) {
                continue;
            }
            
            mstEdges.add(edge);
            visited.add(edge.townB);
            
            // Добавляем новые рёбра
            for (WorldObject town : townList) {
                if (!visited.contains(town)) {
                    BlockPos posA = getPosition(edge.townB);
                    BlockPos posB = getPosition(town);
                    if (posA != null && posB != null) {
                        double dist = Math.sqrt(posA.distSqr(posB));
                        pq.add(new TownEdge(edge.townB, town, dist));
                    }
                }
            }
        }
        
        return mstEdges;
    }
    
    /**
     * Извлекает позицию узла из компонента метаданных.
     */
    @Nullable
    private static BlockPos getPosition(WorldObject obj) {
        return obj.getSingle(KnotMetaComponent.class)
            .map(meta -> meta.position.get())
            .orElse(null);
    }
    
    /**
     * Ребро MST между двумя городами.
     */
    private static class TownEdge {
        final WorldObject townA;
        final WorldObject townB;
        final double distance;
        
        TownEdge(WorldObject townA, WorldObject townB, double distance) {
            this.townA = townA;
            this.townB = townB;
            this.distance = distance;
        }
    }
}
