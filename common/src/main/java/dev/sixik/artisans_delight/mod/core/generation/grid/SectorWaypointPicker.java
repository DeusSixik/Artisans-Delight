package dev.sixik.artisans_delight.mod.core.generation.grid;

import dev.sixik.artisans_delight.mod.core.generation.placement.INodePlacementStrategy;
import dev.sixik.artisans_delight.mod.core.generation.placement.NodePlacementRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * Выбирает детерминированный вейпоинт для сектора.
 * 
 * <p>Каждый сектор имеет ровно один вейпоинт, который выбирается из N кандидатов
 * по критерию минимальной стоимости местности. Это гарантирует:
 * <ul>
 *   <li>Вейпоинты не попадают в реки, обрывы, глубокий океан
 *   <li>Два вызова для одного сектора всегда вернут одинаковые координаты
 *   <li>Дороги между вейпоинтами проходят по проходимой местности
 * </ul>
 * 
 * <p><b>Использование стратегий размещения:</b> Если в секторе есть зарегистрированная
 * стратегия размещения (например, лесопилка в лесу), она имеет приоритет над
 * обычным вейпоинтом. Это позволяет узлам добычи ресурсов стать частью грубого графа.
 * 
 * <p><b>Пример:</b>
 * <pre>{@code
 * Sector sector = Sector.fromWorldPos(1200, -800);
 * BlockPos waypoint = SectorWaypointPicker.pickWaypoint(sector, worldSeed, gen, h, state);
 * 
 * if (waypoint != null) {
 *     // Вейпоинт найден — можно строить дорогу
 * } else {
 *     // Весь сектор непроходим (океан, горный массив)
 * }
 * }</pre>
 */
public final class SectorWaypointPicker {
    
    /**
     * Количество кандидатов для выбора вейпоинта в секторе.
     * Больше кандидатов = лучшее качество вейпоинта, но медленнее генерация.
     */
    private static final int CANDIDATES_COUNT = 16;
    
    /**
     * Уменьшенное количество кандидатов для поиска РЯДОМ с точкой дороги.
     * Поскольку радиус поиска мал (16-32 блока), высокая точность не нужна,
     * а меньшее число кандидатов заметно ускоряет генерацию.
     */
    private static final int NEAR_CANDIDATES_COUNT = 6;
    
    /**
     * Стоимость местности выше этого порога делает сектор непроходимым.
     */
    private static final double IMPASSABLE_THRESHOLD = 1000.0;
    
    private SectorWaypointPicker() {
    }
    
    /**
     * Выбирает детерминированный вейпоинт для сектора.
     * 
     * <p><b>Алгоритм:</b>
     * <ol>
     *   <li>Проверка стратегий размещения из {@link NodePlacementRegistry} (приоритет)
     *   <li>Если стратегия вернула позицию, используем её как вейпоинт
     *   <li>Иначе генерируем N случайных кандидатов по хешу {@code sector.hash(worldSeed)}
     *   <li>Оцениваем стоимость местности для каждого кандидата
     *   <li>Выбираем кандидата с минимальной стоимостью
     *   <li>Если все кандидаты непроходимы (стоимость > {@link #IMPASSABLE_THRESHOLD}), возвращаем {@code null}
     * </ol>
     * 
     * <p><b>Детерминированность:</b> Для одних и тех же входных параметров всегда возвращает
     * одинаковый результат. Используется {@link Sector#hash(long)} для генерации RNG.
     * 
     * @param sector Сектор для выбора вейпоинта
     * @param worldSeed Сид мира (для детерминированности)
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты мира
     * @param randomState Состояние генератора шума
     * @return Координаты вейпоинта, или {@code null}, если сектор полностью непроходим
     */
    @Nullable
    public static BlockPos pickWaypoint(Sector sector,
                                       long worldSeed,
                                       ChunkGenerator generator,
                                       LevelHeightAccessor heightAccessor,
                                       RandomState randomState) {
        
        // Приоритет 1: Проверка стратегий размещения узлов
        NodePlacementRegistry.PlacementResult placementResult = 
            NodePlacementRegistry.placeNode(sector, worldSeed, generator, heightAccessor, randomState);
        
        if (placementResult != null) {
            // Стратегия нашла подходящее место (например, лесопилка в лесу)
            return placementResult.position();
        }
        
        // Приоритет 2: Обычный вейпоинт (проходимая местность)
        Random rng = new Random(sector.hash(worldSeed));
        BlockPos minCorner = sector.minCorner();
        
        BlockPos bestCandidate = null;
        double bestCost = Double.POSITIVE_INFINITY;
        
        for (int i = 0; i < CANDIDATES_COUNT; i++) {
            int localX = rng.nextInt(Sector.SECTOR_SIZE_BLOCKS);
            int localZ = rng.nextInt(Sector.SECTOR_SIZE_BLOCKS);
            int worldX = minCorner.getX() + localX;
            int worldZ = minCorner.getZ() + localZ;
            
            int surfaceY = generator.getBaseHeight(worldX, worldZ,
                                                  Heightmap.Types.WORLD_SURFACE_WG,
                                                  heightAccessor, randomState);
            int floorY = generator.getBaseHeight(worldX, worldZ,
                                               Heightmap.Types.OCEAN_FLOOR_WG,
                                               heightAccessor, randomState);
            
            double cost = evaluateTerrainCost(worldX, worldZ, surfaceY, floorY,
                                             generator, heightAccessor, randomState);
            
            if (cost < bestCost) {
                bestCost = cost;
                bestCandidate = new BlockPos(worldX, surfaceY, worldZ);
            }
        }
        
        // Если все кандидаты непроходимы, сектор заблокирован
        return bestCost >= IMPASSABLE_THRESHOLD ? null : bestCandidate;
    }
    
    /**
     * Выбирает вейпоинт РЯДОМ с предпочтительной точкой (обычно — точкой на дороге).
     * 
     * <p>В отличие от {@link #pickWaypoint}, кандидаты ищутся в радиусе
     * {@code searchRadius} блоков вокруг {@code preferredX/preferredZ}, а не по всему
     * сектору. Это устраняет «зигзаги» дороги: узлы остаются вблизи проложенного
     * маршрута, а не прыгают в случайные углы сектора.
     * 
     * <p><b>Алгоритм:</b>
     * <ol>
     *   <li>Генерируем {@link #CANDIDATES_COUNT} случайных точек в радиусе вокруг preferred</li>
     *   <li>Оцениваем стоимость местности каждой</li>
     *   <li>Выбираем лучшую; если лучшая непроходима — используем саму preferred-точку
     *       (она гарантированно на суше, т.к. взята с сухопутной дороги)</li>
     * </ol>
     * 
     * @param preferredX X-координата предпочтительной точки (на дороге)
     * @param preferredZ Z-координата предпочтительной точки (на дороге)
     * @param searchRadius Радиус поиска в блоках (рекомендуется 16-32)
     * @param sectorHashSeed Детерминированный сид (обычно hash сектора или сид мира)
     * @return Координаты вейпоинта (никогда не {@code null} — при неудаче вернёт preferred)
     */
    public static BlockPos pickWaypointNear(int preferredX, int preferredZ,
                                           int searchRadius,
                                           long sectorHashSeed,
                                           ChunkGenerator generator,
                                           LevelHeightAccessor heightAccessor,
                                           RandomState randomState) {
        
        Random rng = new Random(sectorHashSeed);
        
        BlockPos bestCandidate = null;
        double bestCost = Double.POSITIVE_INFINITY;
        
        for (int i = 0; i < NEAR_CANDIDATES_COUNT; i++) {
            int worldX = preferredX + rng.nextInt(searchRadius * 2 + 1) - searchRadius;
            int worldZ = preferredZ + rng.nextInt(searchRadius * 2 + 1) - searchRadius;
            
            int surfaceY = generator.getBaseHeight(worldX, worldZ,
                                                  Heightmap.Types.WORLD_SURFACE_WG,
                                                  heightAccessor, randomState);
            int floorY = generator.getBaseHeight(worldX, worldZ,
                                               Heightmap.Types.OCEAN_FLOOR_WG,
                                               heightAccessor, randomState);
            
            double cost = evaluateTerrainCost(worldX, worldZ, surfaceY, floorY,
                                             generator, heightAccessor, randomState);
            
            // Небольшой штраф за удалённость от preferred-точки (сохраняем близость к дороге)
            double dist = Math.hypot(worldX - preferredX, worldZ - preferredZ);
            cost += dist * 0.5;
            
            if (cost < bestCost) {
                bestCost = cost;
                bestCandidate = new BlockPos(worldX, surfaceY, worldZ);
            }
        }
        
        // Если лучший кандидат непроходим — возвращаем саму точку дороги
        if (bestCandidate == null || bestCost >= IMPASSABLE_THRESHOLD) {
            int surfaceY = generator.getBaseHeight(preferredX, preferredZ,
                                                  Heightmap.Types.WORLD_SURFACE_WG,
                                                  heightAccessor, randomState);
            return new BlockPos(preferredX, surfaceY, preferredZ);
        }
        
        return bestCandidate;
    }
    
    /**
     * Оценивает стоимость местности для кандидата.
     * 
     * <p>Меньше = лучше. {@link Double#POSITIVE_INFINITY} = непроходимо.
     * 
     * <p><b>Факторы стоимости:</b>
     * <ul>
     *   <li>Глубокая вода (> 6 блоков) → Infinity
     *   <li>Мелководье (≤ 6 блоков) → штраф × глубина
     *   <li>Крутые склоны → экспоненциальный штраф
     *   <li>Высокие горы (> 100 блоков) → штраф × высота
     * </ul>
     * 
     * @param x X-координата кандидата
     * @param z Z-координата кандидата
     * @param surfaceY Высота поверхности (или уровень моря Y=63)
     * @param floorY Высота дна (под водой)
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     * @return Стоимость местности (меньше = лучше)
     */
    private static double evaluateTerrainCost(int x, int z, int surfaceY, int floorY,
                                             ChunkGenerator generator,
                                             LevelHeightAccessor heightAccessor,
                                             RandomState randomState) {
        
        double cost = 1.0;
        
        // 1. Проверка воды
        boolean isWater = surfaceY <= 63 && floorY < 63;
        if (isWater) {
            int depth = 63 - floorY;
            if (depth > 6) {
                // Глубокий океан непроходим для дороги
                return Double.POSITIVE_INFINITY;
            } else {
                // Мелководье/река проходимы, но дорого (поиск узкого места под мост)
                cost += depth * 8.0;
            }
        }
        
        // 2. Проверка крутизны склона (slope penalty)
        int[] neighborHeights = {
            generator.getBaseHeight(x + 8, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
            generator.getBaseHeight(x - 8, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
            generator.getBaseHeight(x, z + 8, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState),
            generator.getBaseHeight(x, z - 8, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState)
        };
        
        for (int neighborY : neighborHeights) {
            int deltaY = Math.abs(surfaceY - neighborY);
            if (deltaY > 1) {
                // Экспоненциальный штраф за скалы
                cost += Math.pow(deltaY, 1.9) * 0.45;
            }
        }
        
        // 3. Штраф за высокие горы (предпочитаем долины и равнины)
        if (surfaceY > 100) {
            cost += (surfaceY - 100) * 0.25;
        }
        
        return cost;
    }
    
    /**
     * Быстрая проверка, является ли сектор заблокированным (полностью непроходимым).
     * 
     * <p>Используется для оптимизации: перед полным вызовом {@link #pickWaypoint}
     * можно проверить центр сектора. Если центр в глубоком океане, скорее всего
     * весь сектор непроходим.
     * 
     * <p><b>Внимание:</b> Это эвристика! Не гарантирует 100% точность.
     * 
     * @param sector Сектор для проверки
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     * @return {@code true}, если сектор скорее всего непроходим
     */
    public static boolean isSectorLikelyBlocked(Sector sector,
                                               ChunkGenerator generator,
                                               LevelHeightAccessor heightAccessor,
                                               RandomState randomState) {
        
        BlockPos center = sector.centerBlock();
        int surfaceY = generator.getBaseHeight(center.getX(), center.getZ(),
                                             Heightmap.Types.WORLD_SURFACE_WG,
                                             heightAccessor, randomState);
        int floorY = generator.getBaseHeight(center.getX(), center.getZ(),
                                           Heightmap.Types.OCEAN_FLOOR_WG,
                                           heightAccessor, randomState);
        
        // Глубокий океан (> 10 блоков глубины)
        boolean isDeepOcean = surfaceY <= 63 && (63 - floorY) > 10;
        
        // Очень высокие горы (> 150 блоков)
        boolean isHighMountain = surfaceY > 150;
        
        return isDeepOcean || isHighMountain;
    }
}
