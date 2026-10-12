package dev.sixik.artisans_delight.mod.core.generation.placement;

import dev.sixik.artisans_delight.mod.core.generation.grid.Sector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;

/**
 * SPI (Service Provider Interface) для стратегий размещения узлов в мире.
 * 
 * <p>Этот интерфейс позволяет сторонним разработчикам создавать собственные правила
 * размещения узлов добычи ресурсов (лесопилки, шахты, фермы) без изменения кода мода.
 * 
 * <p><b>Философия:</b> Стратегия отвечает за два вопроса:
 * <ul>
 *   <li><b>ГДЕ?</b> — В каких секторах/биомах должен появиться узел (метод {@link #shouldPlaceNode})
 *   <li><b>КАК?</b> — Точные координаты узла внутри сектора (метод {@link #findOptimalPosition})
 * </ul>
 * 
 * <p><b>Примеры встроенных стратегий:</b>
 * <ul>
 *   <li>{@code BiomeBasedPlacement} — узлы в лесах, горах, реках
 *   <li>{@code DistanceBasedPlacement} — узлы каждые N блоков вдоль дорог
 *   <li>{@code RandomScatteredPlacement} — случайный разброс по миру
 * </ul>
 * 
 * <p><b>Пример использования в аддоне:</b>
 * <pre>{@code
 * public class VolcanoMineStrategy implements INodePlacementStrategy {
 *     
 *     @Override
 *     public String getId() {
 *         return "my_addon:volcano_mine";
 *     }
 *     
 *     @Override
 *     public boolean shouldPlaceNode(Sector sector, long worldSeed, ChunkGenerator gen, ...) {
 *         BlockPos center = sector.centerBlock();
 *         Holder<Biome> biome = gen.getBiomeSource().getNoiseBiome(...);
 *         
 *         // Спавн только в вулканических биомах
 *         return biome.is(MyTags.IS_VOLCANIC);
 *     }
 *     
 *     @Override
 *     public BlockPos findOptimalPosition(Sector sector, long worldSeed, ChunkGenerator gen, ...) {
 *         // Ищем самую высокую точку в секторе (вершина вулкана)
 *         return findHighestPoint(sector, gen);
 *     }
 * }
 * 
 * // Регистрация в инициализации мода:
 * NodePlacementRegistry.register(new VolcanoMineStrategy());
 * }</pre>
 * 
 * <p><b>Data-driven подход:</b> Для простых случаев можно описать стратегию в JSON-датапаках
 * без написания кода (см. {@code BiomeBasedPlacement}).
 * 
 * @see dev.sixik.artisans_delight.mod.core.generation.placement.NodePlacementRegistry
 */
public interface INodePlacementStrategy {
    
    /**
     * Уникальный идентификатор стратегии.
     * 
     * <p>Используется для:
     * <ul>
     *   <li>Регистрации в реестре {@link NodePlacementRegistry}
     *   <li>Отладочных логов генерации
     *   <li>Сериализации в конфигах/датапаках
     * </ul>
     * 
     * <p><b>Формат:</b> {@code "namespace:path"} (например, {@code "artisans_delight:forest_sawmill"})
     * 
     * @return Уникальный идентификатор стратегии
     */
    String getId();
    
    /**
     * Определяет, должен ли узел быть размещён в данном секторе.
     * 
     * <p>Этот метод вызывается для каждого сектора вокруг городов и существующих узлов.
     * Возврат {@code true} не гарантирует создание узла — далее вызывается {@link #findOptimalPosition},
     * который может вернуть {@code null}, если подходящее место не найдено.
     * 
     * <p><b>Важно:</b> Метод должен быть детерминированным — для одних и тех же входных параметров
     * всегда возвращать одинаковый результат. Используйте {@link Sector#hash(long)} для
     * генерации детерминированных случайных чисел.
     * 
     * <p><b>Пример — размещение каждые 1000 блоков с вероятностью 30%:</b>
     * <pre>{@code
     * public boolean shouldPlaceNode(Sector sector, long worldSeed, ...) {
     *     Random rng = new Random(sector.hash(worldSeed));
     *     return rng.nextDouble() < 0.3;
     * }
     * }</pre>
     * 
     * @param sector Сектор для проверки
     * @param worldSeed Сид мира (для детерминированности)
     * @param generator Генератор мира (для сэмплирования биомов/высот)
     * @param heightAccessor Информация о границах высоты мира
     * @param randomState Состояние генератора шума (для биомов)
     * @return {@code true}, если в секторе может быть размещён узел
     */
    boolean shouldPlaceNode(Sector sector, 
                           long worldSeed,
                           ChunkGenerator generator,
                           LevelHeightAccessor heightAccessor,
                           RandomState randomState);
    
    /**
     * Находит оптимальные координаты для размещения узла внутри сектора.
     * 
     * <p>Этот метод вызывается только если {@link #shouldPlaceNode} вернул {@code true}.
     * Стратегия может проверить несколько кандидатов внутри сектора и выбрать лучший
     * по критерию стоимости местности, близости к дороге, или другим правилам.
     * 
     * <p><b>Возврат {@code null}:</b> Если подходящее место не найдено (весь сектор в воде/горах),
     * метод должен вернуть {@code null}. Узел не будет создан в этом секторе.
     * 
     * <p><b>Детерминированность:</b> Для одних и тех же входных параметров всегда возвращать
     * одинаковые координаты. Используйте {@link Sector#hash(long)} для RNG.
     * 
     * <p><b>Пример — поиск точки с минимальной крутизной:</b>
     * <pre>{@code
     * public BlockPos findOptimalPosition(Sector sector, long worldSeed, ChunkGenerator gen, ...) {
     *     Random rng = new Random(sector.hash(worldSeed));
     *     BlockPos best = null;
     *     double bestCost = Double.POSITIVE_INFINITY;
     *     
     *     for (int i = 0; i < 16; i++) {
     *         int x = sector.minCorner().getX() + rng.nextInt(Sector.SECTOR_SIZE_BLOCKS);
     *         int z = sector.minCorner().getZ() + rng.nextInt(Sector.SECTOR_SIZE_BLOCKS);
     *         int y = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, ...);
     *         
     *         double cost = evaluateTerrain(x, y, z, gen);
     *         if (cost < bestCost) {
     *             bestCost = cost;
     *             best = new BlockPos(x, y, z);
     *         }
     *     }
     *     
     *     return bestCost == Double.POSITIVE_INFINITY ? null : best;
     * }
     * }</pre>
     * 
     * @param sector Сектор для размещения
     * @param worldSeed Сид мира (для детерминированности)
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты мира
     * @param randomState Состояние генератора шума
     * @return Координаты узла, или {@code null}, если подходящее место не найдено
     */
    @Nullable
    BlockPos findOptimalPosition(Sector sector,
                                 long worldSeed,
                                 ChunkGenerator generator,
                                 LevelHeightAccessor heightAccessor,
                                 RandomState randomState);
    
    /**
     * Приоритет стратегии при конфликте нескольких стратегий для одного сектора.
     * 
     * <p>Если несколько стратегий вернули {@code true} для одного сектора, выбирается
     * стратегия с наибольшим приоритетом. При равном приоритете выбор детерминирован по хешу ID.
     * 
     * <p><b>Стандартные приоритеты:</b>
     * <ul>
     *   <li>{@code 100} — Уникальные редкие узлы (вулканы, древние руины)
     *   <li>{@code 50} — Биомные узлы (леса, горы, реки)
     *   <li>{@code 10} — Дистанционные узлы (каждые N блоков)
     *   <li>{@code 0} — Случайные разбросанные узлы
     * </ul>
     * 
     * @return Приоритет стратегии (больше = выше приоритет)
     */
    default int getPriority() {
        return 50;
    }
    
    /**
     * Можно ли размещать несколько узлов этой стратегии рядом друг с другом.
     * 
     * <p>Если {@code false}, система гарантирует, что между узлами этой стратегии
     * будет минимальная дистанция (минимум 2 сектора = ~200 блоков).
     * 
     * <p>Полезно для редких уникальных узлов, которые не должны спавниться группами.
     * 
     * @return {@code true}, если узлы могут спавниться рядом (по умолчанию {@code true})
     */
    default boolean allowClustering() {
        return true;
    }
}
