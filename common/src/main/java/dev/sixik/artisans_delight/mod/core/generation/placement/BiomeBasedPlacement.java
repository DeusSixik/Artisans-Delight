package dev.sixik.artisans_delight.mod.core.generation.placement;

import dev.sixik.artisans_delight.mod.core.generation.grid.Sector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * Встроенная стратегия размещения узлов на основе биомов.
 * 
 * <p>Эта стратегия ищет подходящие биомы (лес, горы, реки) и размещает узлы добычи ресурсов.
 * 
 * <p><b>Пример: Лесопилка в лесу</b>
 * <pre>{@code
 * BiomeBasedPlacement sawmill = BiomeBasedPlacement.builder("artisans_delight:forest_sawmill")
 *     .biomes(BiomeTags.IS_FOREST, BiomeTags.IS_TAIGA)
 *     .spawnChance(0.4) // 40% шанс спавна в подходящем секторе
 *     .avoidWater(true)
 *     .maxSlope(8) // Не более 8 блоков перепада высоты
 *     .build();
 * 
 * NodePlacementRegistry.register(sawmill);
 * }</pre>
 * 
 * <p><b>Data-driven подход:</b> В будущем можно описать эту стратегию в JSON-датапаках:
 * <pre>{@code
 * {
 *   "type": "artisans_delight:biome_based",
 *   "id": "artisans_delight:forest_sawmill",
 *   "biomes": ["#minecraft:is_forest", "#minecraft:is_taiga"],
 *   "spawn_chance": 0.4,
 *   "avoid_water": true,
 *   "max_slope": 8
 * }
 * }</pre>
 */
public class BiomeBasedPlacement implements INodePlacementStrategy {
    
    private final String id;
    private final TagKey<Biome>[] requiredBiomes;
    private final double spawnChance;
    private final boolean avoidWater;
    private final boolean avoidMountains;
    private final int maxSlope;
    private final int minHeight;
    private final int maxHeight;
    private final int candidatesCount;
    private final int priority;
    
    private BiomeBasedPlacement(String id, 
                               TagKey<Biome>[] requiredBiomes,
                               double spawnChance,
                               boolean avoidWater,
                               boolean avoidMountains,
                               int maxSlope,
                               int minHeight,
                               int maxHeight,
                               int candidatesCount,
                               int priority) {
        this.id = id;
        this.requiredBiomes = requiredBiomes;
        this.spawnChance = spawnChance;
        this.avoidWater = avoidWater;
        this.avoidMountains = avoidMountains;
        this.maxSlope = maxSlope;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.candidatesCount = candidatesCount;
        this.priority = priority;
    }
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public boolean shouldPlaceNode(Sector sector, long worldSeed, ChunkGenerator generator, 
                                  LevelHeightAccessor heightAccessor, RandomState randomState) {
        
        // Проверка шанса спавна
        Random rng = new Random(sector.hash(worldSeed));
        if (rng.nextDouble() >= spawnChance) {
            return false;
        }
        
        // Проверка, что в секторе есть хотя бы один подходящий биом
        BlockPos center = sector.centerBlock();
        int surfaceY = generator.getBaseHeight(center.getX(), center.getZ(), 
                                              Heightmap.Types.WORLD_SURFACE_WG, 
                                              heightAccessor, randomState);
        
        Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(
            QuartPos.fromBlock(center.getX()),
            QuartPos.fromBlock(surfaceY),
            QuartPos.fromBlock(center.getZ()),
            randomState.sampler()
        );
        
        return matchesBiome(biome);
    }
    
    @Override
    @Nullable
    public BlockPos findOptimalPosition(Sector sector, long worldSeed, ChunkGenerator generator,
                                       LevelHeightAccessor heightAccessor, RandomState randomState) {
        
        Random rng = new Random(sector.hash(worldSeed));
        BlockPos minCorner = sector.minCorner();
        
        BlockPos best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        
        // Пробуем N кандидатов и выбираем лучший по стоимости местности
        for (int i = 0; i < candidatesCount; i++) {
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
            
            // Проверка биома
            Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(worldX),
                QuartPos.fromBlock(surfaceY),
                QuartPos.fromBlock(worldZ),
                randomState.sampler()
            );
            
            if (!matchesBiome(biome)) {
                continue;
            }
            
            // Оценка стоимости местности
            double cost = evaluateTerrainCost(worldX, worldZ, surfaceY, floorY, 
                                             generator, heightAccessor, randomState);
            
            if (cost < bestCost) {
                bestCost = cost;
                best = new BlockPos(worldX, surfaceY, worldZ);
            }
        }
        
        return bestCost == Double.POSITIVE_INFINITY ? null : best;
    }
    
    @Override
    public int getPriority() {
        return priority;
    }
    
    /**
     * Проверяет, подходит ли биом под требования стратегии.
     */
    private boolean matchesBiome(Holder<Biome> biome) {
        for (TagKey<Biome> tag : requiredBiomes) {
            if (biome.is(tag)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Оценивает стоимость местности (меньше = лучше).
     * Infinity = непроходимо.
     */
    private double evaluateTerrainCost(int x, int z, int surfaceY, int floorY,
                                      ChunkGenerator gen, LevelHeightAccessor h, RandomState state) {
        
        // 1. Проверка воды
        boolean isWater = surfaceY <= 63 && floorY < 63;
        if (isWater) {
            int depth = 63 - floorY;
            if (avoidWater && depth > 3) {
                return Double.POSITIVE_INFINITY; // Глубокая вода непроходима
            }
            // Мелководье допустимо, но штраф
            return depth * 10.0;
        }
        
        // 2. Проверка границ высоты
        if (surfaceY < minHeight || surfaceY > maxHeight) {
            return Double.POSITIVE_INFINITY;
        }
        
        // 3. Проверка гор
        if (avoidMountains && surfaceY > 100) {
            return Double.POSITIVE_INFINITY;
        }
        
        double cost = 1.0;
        
        // 4. Проверка крутизны (slope)
        int[] neighborHeights = {
            gen.getBaseHeight(x + 8, z, Heightmap.Types.WORLD_SURFACE_WG, h, state),
            gen.getBaseHeight(x - 8, z, Heightmap.Types.WORLD_SURFACE_WG, h, state),
            gen.getBaseHeight(x, z + 8, Heightmap.Types.WORLD_SURFACE_WG, h, state),
            gen.getBaseHeight(x, z - 8, Heightmap.Types.WORLD_SURFACE_WG, h, state)
        };
        
        for (int ny : neighborHeights) {
            int slope = Math.abs(surfaceY - ny);
            if (slope > maxSlope) {
                return Double.POSITIVE_INFINITY; // Слишком крутой склон
            }
            cost += slope * 0.5; // Штраф за неровность
        }
        
        return cost;
    }
    
    /**
     * Builder для удобного создания стратегий.
     */
    @SafeVarargs
    public static Builder builder(String id, TagKey<Biome>... requiredBiomes) {
        return new Builder(id, requiredBiomes);
    }
    
    public static class Builder {
        private final String id;
        private final TagKey<Biome>[] requiredBiomes;
        private double spawnChance = 1.0;
        private boolean avoidWater = true;
        private boolean avoidMountains = false;
        private int maxSlope = 8;
        private int minHeight = 60;
        private int maxHeight = 120;
        private int candidatesCount = 16;
        private int priority = 50;
        
        @SafeVarargs
        private Builder(String id, TagKey<Biome>... requiredBiomes) {
            this.id = id;
            this.requiredBiomes = requiredBiomes;
        }
        
        /**
         * Вероятность спавна узла в подходящем секторе (0.0 - 1.0).
         */
        public Builder spawnChance(double chance) {
            this.spawnChance = Math.max(0.0, Math.min(1.0, chance));
            return this;
        }
        
        /**
         * Избегать глубокой воды (глубина > 3 блоков).
         */
        public Builder avoidWater(boolean avoid) {
            this.avoidWater = avoid;
            return this;
        }
        
        /**
         * Избегать гор (высота > 100 блоков).
         */
        public Builder avoidMountains(boolean avoid) {
            this.avoidMountains = avoid;
            return this;
        }
        
        /**
         * Максимальный перепад высоты между соседними точками (в блоках).
         */
        public Builder maxSlope(int slope) {
            this.maxSlope = slope;
            return this;
        }
        
        /**
         * Диапазон допустимых высот для узла.
         */
        public Builder heightRange(int min, int max) {
            this.minHeight = min;
            this.maxHeight = max;
            return this;
        }
        
        /**
         * Количество кандидатов для проверки внутри сектора.
         */
        public Builder candidatesCount(int count) {
            this.candidatesCount = Math.max(1, count);
            return this;
        }
        
        /**
         * Приоритет стратегии при конфликте.
         */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }
        
        public BiomeBasedPlacement build() {
            return new BiomeBasedPlacement(
                id, requiredBiomes, spawnChance, avoidWater, avoidMountains,
                maxSlope, minHeight, maxHeight, candidatesCount, priority
            );
        }
    }
}
