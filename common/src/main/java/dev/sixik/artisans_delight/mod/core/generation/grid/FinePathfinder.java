package dev.sixik.artisans_delight.mod.core.generation.grid;

import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Детальный A* pathfinder для построения физической геометрии дороги между двумя вейпоинтами.
 * 
 * <p>Этот класс отвечает за тонкий слой — КАК физически проходит дорога между узлами,
 * обходя реки, обрывы и крутые склоны.
 * 
 * <p><b>Ключевое отличие от старого RouteGenerator:</b>
 * <ul>
 *   <li>Работает только между соседними вейпоинтами (короткие расстояния 200-500 блоков)</li>
 *   <li>Шаг сетки: 4-8 блоков (детальнее, чем грубый слой)</li>
 *   <li>Результат кэшируется по ключу {@code (seed, fromPos, toPos)}</li>
 *   <li>Две дороги, проходящие через одно ребро, получают побитово идентичную геометрию</li>
 * </ul>
 * 
 * <p><b>Пример использования:</b>
 * <pre>{@code
 * BlockPos waypointA = new BlockPos(100, 64, 200);
 * BlockPos waypointB = new BlockPos(500, 64, 600);
 * 
 * List<RoadPoint2D> path = FinePathfinder.findPath(
 *     waypointA, waypointB, worldSeed, gen, h, state
 * );
 * 
 * // path содержит ломаную линию, огибающую препятствия
 * }</pre>
 */
public final class FinePathfinder {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(FinePathfinder.class);
    
    /**
     * Шаг сетки для детального A* (в блоках).
     * Меньше = точнее обход препятствий, но медленнее.
     * ОПТИМИЗАЦИЯ: Увеличено с 8 до 16 для ускорения в 4 раза.
     */
    private static final int GRID_STEP = 16;
    
    /**
     * Максимальное количество итераций A* (защита от зависания).
     */
    private static final int MAX_ITERATIONS = 50000;
    
    /**
     * Максимальное расстояние между узлами (блоки).
     * Если расстояние больше — возвращаем прямую линию.
     * ОПТИМИЗАЦИЯ: Снижено с 2000 до 600 (для промежуточных узлов достаточно).
     */
    private static final int MAX_DISTANCE = 600;
    
    /**
     * Кэш результатов поиска пути.
     * Ключ: (seed, fromPos, toPos) -> List<RoadPoint2D>
     */
    private static final Map<PathCacheKey, List<RoadPoint2D>> PATH_CACHE = new ConcurrentHashMap<>();
    
    private FinePathfinder() {
    }
    
    /**
     * Находит путь между двумя вейпоинтами с учётом рельефа.
     * 
     * <p>Использует A* с шагом сетки {@link #GRID_STEP} блоков.
     * Результат кэшируется — повторные вызовы с теми же параметрами возвращают закэшированный путь.
     * 
     * <p><b>Детерминированность:</b> Для одних и тех же входных параметров всегда возвращает
     * одинаковый путь (важно для слияния дорог).
     * 
     * @param from Начальная точка (вейпоинт)
     * @param to Конечная точка (вейпоинт)
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     * @return Ломаная линия дороги (список 2D-точек)
     */
    public static List<RoadPoint2D> findPath(BlockPos from,
                                            BlockPos to,
                                            long worldSeed,
                                            ChunkGenerator generator,
                                            LevelHeightAccessor heightAccessor,
                                            RandomState randomState) {
        
        PathCacheKey key = new PathCacheKey(worldSeed, from, to);
        
        return PATH_CACHE.computeIfAbsent(key, k -> 
            computePath(from, to, generator, heightAccessor, randomState)
        );
    }
    
    /**
     * Вычисляет путь через детальный A*.
     */
    private static List<RoadPoint2D> computePath(BlockPos from,
                                                BlockPos to,
                                                ChunkGenerator generator,
                                                LevelHeightAccessor heightAccessor,
                                                RandomState randomState) {
        
        double distance = Math.sqrt(from.distSqr(to));
        
        LOGGER.info("[FinePathfinder] Начинаю A* между ({}, {}) и ({}, {}), расстояние: {} блоков",
            from.getX(), from.getZ(), to.getX(), to.getZ(), (int) distance);
        
        // Защита от слишком длинных путей
        if (distance > MAX_DISTANCE) {
            LOGGER.warn("[FinePathfinder] Расстояние {} > {} блоков! Возвращаю прямую линию.", (int) distance, MAX_DISTANCE);
            return List.of(
                new RoadPoint2D(from.getX(), from.getZ(), false),
                new RoadPoint2D(to.getX(), to.getZ(), false)
            );
        }
        
        long startTime = System.currentTimeMillis();
        
        // Сетка A*
        PriorityQueue<AStarNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fScore));
        Map<GridPos, AStarNode> allNodes = new HashMap<>();
        
        GridPos startGrid = toGrid(from);
        GridPos goalGrid = toGrid(to);
        
        AStarNode startNode = new AStarNode(startGrid, 0, heuristic(startGrid, goalGrid), null);
        openSet.add(startNode);
        allNodes.put(startGrid, startNode);
        
        int iterations = 0;
        int lastLogIteration = 0;
        
        while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            
            // Логирование прогресса каждые 5000 итераций
            if (iterations - lastLogIteration >= 5000) {
                long elapsed = System.currentTimeMillis() - startTime;
                LOGGER.info("[FinePathfinder] Итерация {}/{}, openSet: {}, время: {} мс", 
                    iterations, MAX_ITERATIONS, openSet.size(), elapsed);
                lastLogIteration = iterations;
            }
            
            AStarNode current = openSet.poll();
            
            if (current.pos.equals(goalGrid)) {
                // Путь найден — восстанавливаем через родителей
                long totalTime = System.currentTimeMillis() - startTime;
                List<RoadPoint2D> path = reconstructPath(current, from, to);
                LOGGER.info("[FinePathfinder] Путь найден за {} итераций, {} мс, {} точек", 
                    iterations, totalTime, path.size());
                return path;
            }
            
            // Соседи: 8 направлений (включая диагонали)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    
                    GridPos neighborPos = new GridPos(
                        current.pos.x + dx,
                        current.pos.z + dz
                    );
                    
                    BlockPos worldPos = fromGrid(neighborPos);
                    
                    // Стоимость перехода
                    double moveCost = evaluateTerrainCost(worldPos, generator, heightAccessor, randomState);
                    
                    if (moveCost == Double.POSITIVE_INFINITY) {
                        continue; // Непроходимо
                    }
                    
                    double edgeCost = (dx != 0 && dz != 0) ? 1.414 * GRID_STEP : GRID_STEP; // Диагональ длиннее
                    double newGScore = current.gScore + edgeCost * moveCost;
                    
                    AStarNode neighborNode = allNodes.get(neighborPos);
                    
                    if (neighborNode == null || newGScore < neighborNode.gScore) {
                        double hScore = heuristic(neighborPos, goalGrid);
                        AStarNode newNode = new AStarNode(neighborPos, newGScore, newGScore + hScore, current);
                        
                        allNodes.put(neighborPos, newNode);
                        openSet.add(newNode);
                    }
                }
            }
        }
        
        // Путь не найден — возвращаем прямую линию
        long totalTime = System.currentTimeMillis() - startTime;
        LOGGER.warn("[FinePathfinder] Путь НЕ найден после {} итераций за {} мс! Возвращаю прямую линию.", 
            iterations, totalTime);
        
        return List.of(
            new RoadPoint2D(from.getX(), from.getZ(), false),
            new RoadPoint2D(to.getX(), to.getZ(), false)
        );
    }
    
    /**
     * Оценивает стоимость местности для одной точки.
     */
    private static double evaluateTerrainCost(BlockPos pos,
                                             ChunkGenerator generator,
                                             LevelHeightAccessor heightAccessor,
                                             RandomState randomState) {
        
        int surfaceY = generator.getBaseHeight(pos.getX(), pos.getZ(),
                                              Heightmap.Types.WORLD_SURFACE_WG,
                                              heightAccessor, randomState);
        int floorY = generator.getBaseHeight(pos.getX(), pos.getZ(),
                                           Heightmap.Types.OCEAN_FLOOR_WG,
                                           heightAccessor, randomState);
        
        double cost = 1.0;
        
        // 1. Вода
        boolean isWater = surfaceY <= 63 && floorY < 63;
        if (isWater) {
            int depth = 63 - floorY;
            if (depth > 6) {
                return Double.POSITIVE_INFINITY; // Глубокий океан
            }
            cost += depth * 6.0; // Штраф за реку
        }
        
        // 2. Крутизна (проверка соседей на расстоянии GRID_STEP)
        int[] neighbors = {
            generator.getBaseHeight(pos.getX() + GRID_STEP, pos.getZ(), Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
            generator.getBaseHeight(pos.getX() - GRID_STEP, pos.getZ(), Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
            generator.getBaseHeight(pos.getX(), pos.getZ() + GRID_STEP, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
            generator.getBaseHeight(pos.getX(), pos.getZ() - GRID_STEP, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
        };
        
        for (int ny : neighbors) {
            int deltaY = Math.abs(surfaceY - ny);
            if (deltaY > 1) {
                cost += Math.pow(deltaY, 1.9) * 0.45;
            }
        }
        
        // 3. Горы
        if (surfaceY > 100) {
            cost += (surfaceY - 100) * 0.2;
        }
        
        return cost;
    }
    
    /**
     * Эвристика для A* (манхэттенское расстояние).
     */
    private static double heuristic(GridPos from, GridPos to) {
        int dx = Math.abs(from.x - to.x);
        int dz = Math.abs(from.z - to.z);
        return (dx + dz) * GRID_STEP;
    }
    
    /**
     * Восстанавливает путь из узлов A*.
     */
    private static List<RoadPoint2D> reconstructPath(AStarNode goalNode, BlockPos from, BlockPos to) {
        List<RoadPoint2D> path = new ArrayList<>();
        
        // Идём от цели к старту
        AStarNode current = goalNode;
        while (current != null) {
            BlockPos worldPos = fromGrid(current.pos);
            path.add(new RoadPoint2D(worldPos.getX(), worldPos.getZ(), false));
            current = current.parent;
        }
        
        Collections.reverse(path);
        
        // Добавляем точные координаты начала и конца
        if (!path.isEmpty()) {
            path.set(0, new RoadPoint2D(from.getX(), from.getZ(), false));
            path.set(path.size() - 1, new RoadPoint2D(to.getX(), to.getZ(), false));
        }
        
        return path;
    }
    
    /**
     * Преобразует мировые координаты в координаты сетки A*.
     */
    private static GridPos toGrid(BlockPos worldPos) {
        return new GridPos(
            Math.floorDiv(worldPos.getX(), GRID_STEP),
            Math.floorDiv(worldPos.getZ(), GRID_STEP)
        );
    }
    
    /**
     * Преобразует координаты сетки A* в мировые координаты.
     */
    private static BlockPos fromGrid(GridPos gridPos) {
        return new BlockPos(
            gridPos.x * GRID_STEP,
            64,
            gridPos.z * GRID_STEP
        );
    }
    
    /**
     * Очищает кэш путей.
     * Вызывается при смене мира.
     */
    public static void clearCache() {
        PATH_CACHE.clear();
    }
    
    /**
     * Узел A*.
     */
    private static class AStarNode {
        final GridPos pos;
        final double gScore; // Стоимость пути от старта
        final double fScore; // gScore + эвристика
        final AStarNode parent;
        
        AStarNode(GridPos pos, double gScore, double fScore, AStarNode parent) {
            this.pos = pos;
            this.gScore = gScore;
            this.fScore = fScore;
            this.parent = parent;
        }
    }
    
    /**
     * Позиция в сетке A*.
     */
    private record GridPos(int x, int z) {}
    
    /**
     * Ключ кэша путей.
     */
    private record PathCacheKey(long worldSeed, BlockPos from, BlockPos to) {}
}
