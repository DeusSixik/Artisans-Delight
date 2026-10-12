package dev.sixik.artisans_delight.mod.core.generation.placement;

import com.google.common.collect.ImmutableList;
import dev.sixik.artisans_delight.mod.core.generation.grid.Sector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Глобальный реестр стратегий размещения узлов.
 * 
 * <p>Сторонние разработчики регистрируют свои стратегии через {@link #register(INodePlacementStrategy)}.
 * Во время генерации мира система вызывает все зарегистрированные стратегии и выбирает лучшую.
 * 
 * <p><b>Пример использования в аддоне:</b>
 * <pre>{@code
 * // В конструкторе/инициализации мода:
 * public class MyModInit {
 *     public static void init() {
 *         NodePlacementRegistry.register(new VolcanoMineStrategy());
 *         NodePlacementRegistry.register(new UnderwaterPearlFarmStrategy());
 *     }
 * }
 * }</pre>
 * 
 * <p><b>Thread-safety:</b> Реестр потокобезопасен. Регистрация может происходить из любого потока
 * (главный поток инициализации или параллельные загрузчики модов).
 */
public final class NodePlacementRegistry {
    
    private static final Map<String, INodePlacementStrategy> STRATEGIES = new ConcurrentHashMap<>();
    
    /**
     * Кэш результатов {@link #shouldPlaceNode} для избежания повторных вычислений.
     * Ключ: (strategyId, sectorHash) -> boolean
     */
    private static final Map<CacheKey, Boolean> SHOULD_PLACE_CACHE = new ConcurrentHashMap<>();
    
    private NodePlacementRegistry() {
    }
    
    /**
     * Регистрирует стратегию размещения узлов.
     * 
     * <p>Если стратегия с таким ID уже зарегистрирована, выбрасывается {@link IllegalArgumentException}.
     * 
     * @param strategy Стратегия для регистрации
     * @throws IllegalArgumentException Если ID уже занят
     */
    public static void register(INodePlacementStrategy strategy) {
        String id = strategy.getId();
        if (STRATEGIES.containsKey(id)) {
            throw new IllegalArgumentException("Node placement strategy with ID '" + id + "' is already registered!");
        }
        STRATEGIES.put(id, strategy);
    }
    
    /**
     * Возвращает стратегию по ID.
     * 
     * @param id ID стратегии
     * @return Стратегия, или {@code null}, если не найдена
     */
    @Nullable
    public static INodePlacementStrategy getStrategy(String id) {
        return STRATEGIES.get(id);
    }
    
    /**
     * Возвращает все зарегистрированные стратегии.
     * 
     * @return Неизменяемый список стратегий
     */
    public static List<INodePlacementStrategy> getAllStrategies() {
        return ImmutableList.copyOf(STRATEGIES.values());
    }
    
    /**
     * Находит лучшую стратегию для размещения узла в данном секторе.
     * 
     * <p>Алгоритм:
     * <ol>
     *   <li>Вызывает {@link INodePlacementStrategy#shouldPlaceNode} для всех стратегий
     *   <li>Отбирает стратегии, вернувшие {@code true}
     *   <li>Сортирует по приоритету ({@link INodePlacementStrategy#getPriority})
     *   <li>При равном приоритете выбирает детерминированно по хешу ID
     * </ol>
     * 
     * <p><b>Оптимизация:</b> Результаты {@code shouldPlaceNode} кэшируются по ключу
     * {@code (strategyId, sector.hash(worldSeed))}.
     * 
     * @param sector Сектор для проверки
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     * @return Лучшая стратегия, или {@code null}, если ни одна стратегия не подходит
     */
    @Nullable
    public static INodePlacementStrategy findBestStrategy(Sector sector,
                                                         long worldSeed,
                                                         ChunkGenerator generator,
                                                         LevelHeightAccessor heightAccessor,
                                                         RandomState randomState) {
        
        List<INodePlacementStrategy> candidates = new ArrayList<>();
        long sectorHash = sector.hash(worldSeed);
        
        for (INodePlacementStrategy strategy : STRATEGIES.values()) {
            CacheKey key = new CacheKey(strategy.getId(), sectorHash);
            
            boolean shouldPlace = SHOULD_PLACE_CACHE.computeIfAbsent(key, k -> 
                strategy.shouldPlaceNode(sector, worldSeed, generator, heightAccessor, randomState)
            );
            
            if (shouldPlace) {
                candidates.add(strategy);
            }
        }
        
        if (candidates.isEmpty()) {
            return null;
        }
        
        // Сортировка: сначала по приоритету (больше = лучше), затем по хешу ID (детерминированность)
        candidates.sort((a, b) -> {
            int priorityCompare = Integer.compare(b.getPriority(), a.getPriority());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return a.getId().compareTo(b.getId());
        });
        
        return candidates.get(0);
    }
    
    /**
     * Размещает узел в секторе, используя лучшую подходящую стратегию.
     * 
     * <p>Это главный метод, который вызывается генератором мира. Он:
     * <ol>
     *   <li>Находит лучшую стратегию через {@link #findBestStrategy}
     *   <li>Вызывает {@link INodePlacementStrategy#findOptimalPosition}
     *   <li>Проверяет, что позиция действительно находится внутри сектора
     * </ol>
     * 
     * @param sector Сектор для размещения
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     * @return Позиция узла и ID стратегии, или {@code null}, если размещение невозможно
     */
    @Nullable
    public static PlacementResult placeNode(Sector sector,
                                           long worldSeed,
                                           ChunkGenerator generator,
                                           LevelHeightAccessor heightAccessor,
                                           RandomState randomState) {
        
        INodePlacementStrategy strategy = findBestStrategy(sector, worldSeed, generator, heightAccessor, randomState);
        
        if (strategy == null) {
            return null;
        }
        
        BlockPos position = strategy.findOptimalPosition(sector, worldSeed, generator, heightAccessor, randomState);
        
        if (position == null) {
            return null;
        }
        
        // Проверка границ сектора (защита от ошибок в пользовательских стратегиях)
        if (!sector.contains(position)) {
            System.err.println("[NodePlacementRegistry] Strategy '" + strategy.getId() + "' returned position " +
                             position + " outside sector " + sector + ". Ignoring.");
            return null;
        }
        
        return new PlacementResult(position, strategy.getId());
    }
    
    /**
     * Очищает кэш результатов {@code shouldPlaceNode}.
     * 
     * <p>Вызывается при смене мира или перезагрузке конфигурации.
     */
    public static void clearCache() {
        SHOULD_PLACE_CACHE.clear();
    }
    
    /**
     * Удаляет все зарегистрированные стратегии.
     * 
     * <p>Используется в тестах. В продакшене НЕ вызывать — стратегии регистрируются один раз при старте.
     */
    public static void clearAll() {
        STRATEGIES.clear();
        SHOULD_PLACE_CACHE.clear();
    }
    
    /**
     * Результат размещения узла.
     * 
     * @param position Мировые координаты узла
     * @param strategyId ID стратегии, которая разместила узел
     */
    public record PlacementResult(BlockPos position, String strategyId) {}
    
    /**
     * Ключ кэша для результатов {@code shouldPlaceNode}.
     */
    private record CacheKey(String strategyId, long sectorHash) {}
}
