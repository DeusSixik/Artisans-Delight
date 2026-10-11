package dev.sixik.artisans_delight.mod.core.generation.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Optional;

import net.minecraft.tags.BiomeTags;

/**
 * Утилита для мгновенного математического сэмплирования рельефа мира,
 * высот, океанов, глубин и биомов через ванильный ChunkGenerator БЕЗ загрузки или генерации чанков.
 */
public final class TerrainSampler {

    public static final int SEA_LEVEL = 63;

    private TerrainSampler() {
    }

    /**
     * Получить высоту поверхности суши (или уровень моря Y=63 над водой).
     */
    public static int getSurfaceHeight(ChunkGenerator chunkGenerator, LevelHeightAccessor heightAccessor,
                                       RandomState randomState, int x, int z) {
        return chunkGenerator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState);
    }

    /**
     * Получить высоту дна (твердого грунта под водой).
     */
    public static int getOceanFloorHeight(ChunkGenerator chunkGenerator, LevelHeightAccessor heightAccessor,
                                          RandomState randomState, int x, int z) {
        return chunkGenerator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);
    }

    /**
     * Проверка биома на реку (по тегу BiomeTags.IS_RIVER и по идентификатору).
     */
    public static boolean isRiverBiome(Holder<Biome> biome) {
        if (biome == null) return false;
        if (biome.is(BiomeTags.IS_RIVER)) return true;
        return biome.unwrapKey()
                .map(k -> k.location().getPath().toLowerCase().contains("river"))
                .orElse(false);
    }

    /**
     * Проверка биома на океан (по тегу BiomeTags.IS_OCEAN и по идентификатору).
     */
    public static boolean isOceanBiome(Holder<Biome> biome) {
        if (biome == null) return false;
        if (biome.is(BiomeTags.IS_OCEAN)) return true;
        return biome.unwrapKey()
                .map(k -> k.location().getPath().toLowerCase().contains("ocean"))
                .orElse(false);
    }

    /**
     * Получить глубину воды в блоках (0, если это суша выше уровня моря).
     */
    public static int getWaterDepth(ChunkGenerator chunkGenerator, LevelHeightAccessor heightAccessor,
                                    RandomState randomState, int x, int z) {
        int surfaceY = getSurfaceHeight(chunkGenerator, heightAccessor, randomState, x, z);
        int floorY = getOceanFloorHeight(chunkGenerator, heightAccessor, randomState, x, z);
        if (surfaceY > floorY) {
            return surfaceY - floorY;
        }

        // 3D Биомы: проверяем вертикальную колонку высот (surfY, 63, 62, 64, floorY)
        int[] testYs = {surfaceY, SEA_LEVEL, SEA_LEVEL - 1, SEA_LEVEL + 1, floorY};
        for (int y : testYs) {
            Holder<Biome> b = getNoiseBiome(chunkGenerator, randomState, x, y, z);
            if (isRiverBiome(b)) {
                return 3;
            }
            if (isOceanBiome(b)) {
                return Math.max(3, SEA_LEVEL - floorY);
            }
        }
        return 0;
    }

    /**
     * Проверка, является ли точка водоёмом (океан / залив / река).
     */
    public static boolean isWater(ChunkGenerator chunkGenerator, LevelHeightAccessor heightAccessor,
                                  RandomState randomState, int x, int z) {
        int surfaceY = getSurfaceHeight(chunkGenerator, heightAccessor, randomState, x, z);
        int floorY = getOceanFloorHeight(chunkGenerator, heightAccessor, randomState, x, z);
        if (surfaceY > floorY) {
            return true;
        }

        // 3D Биомы: проверяем вертикальную колонку высот (surfY, 63, 62, 64, floorY)
        int[] testYs = {surfaceY, SEA_LEVEL, SEA_LEVEL - 1, SEA_LEVEL + 1, floorY};
        for (int y : testYs) {
            Holder<Biome> b = getNoiseBiome(chunkGenerator, randomState, x, y, z);
            if (isRiverBiome(b) || isOceanBiome(b)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Проверка, является ли точка рекой.
     */
    public static boolean isRiver(ChunkGenerator chunkGenerator, RandomState randomState, int x, int surfY, int z) {
        int[] testYs = {surfY, SEA_LEVEL, SEA_LEVEL - 1, SEA_LEVEL + 1};
        for (int y : testYs) {
            Holder<Biome> b = getNoiseBiome(chunkGenerator, randomState, x, y, z);
            if (isRiverBiome(b)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Найти ближайшую точку твердой суши вокруг заданных координат.
     */
    public static BlockPos findNearestLand(ChunkGenerator chunkGenerator, LevelHeightAccessor heightAccessor,
                                           RandomState randomState, int startX, int startZ, int maxRadius) {
        if (!isWater(chunkGenerator, heightAccessor, randomState, startX, startZ)) {
            return new BlockPos(startX, getSurfaceHeight(chunkGenerator, heightAccessor, randomState, startX, startZ), startZ);
        }
        int step = 8;
        for (int r = step; r <= maxRadius; r += step) {
            for (int dx = -r; dx <= r; dx += step) {
                for (int dz = -r; dz <= r; dz += step) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = startX + dx;
                    int z = startZ + dz;
                    if (!isWater(chunkGenerator, heightAccessor, randomState, x, z)) {
                        int y = getSurfaceHeight(chunkGenerator, heightAccessor, randomState, x, z);
                        return new BlockPos(x, y, z);
                    }
                }
            }
        }
        return new BlockPos(startX, getSurfaceHeight(chunkGenerator, heightAccessor, randomState, startX, startZ), startZ);
    }

    /**
     * Найти ближайшую точку открытой воды вокруг заданных координат (для старта морских путей).
     */
    public static BlockPos findNearestWater(ChunkGenerator chunkGenerator, LevelHeightAccessor heightAccessor,
                                            RandomState randomState, int startX, int startZ, int maxRadius) {
        if (isWater(chunkGenerator, heightAccessor, randomState, startX, startZ)) {
            return new BlockPos(startX, SEA_LEVEL, startZ);
        }
        int step = 4;
        for (int r = step; r <= maxRadius; r += step) {
            for (int dx = -r; dx <= r; dx += step) {
                for (int dz = -r; dz <= r; dz += step) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int x = startX + dx;
                    int z = startZ + dz;
                    if (isWater(chunkGenerator, heightAccessor, randomState, x, z)) {
                        return new BlockPos(x, SEA_LEVEL, z);
                    }
                }
            }
        }
        return new BlockPos(startX, SEA_LEVEL, startZ);
    }

    /**
     * Получить биом в точке мира без генерации чанка.
     */
    public static Holder<Biome> getNoiseBiome(ChunkGenerator chunkGenerator, RandomState randomState,
                                             int x, int y, int z) {
        return chunkGenerator.getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(x),
                QuartPos.fromBlock(y),
                QuartPos.fromBlock(z),
                randomState.sampler()
        );
    }

    /**
     * Поиск причала (уреза воды) для прибрежного города.
     * Сканирует 16 направлений от центра поселения. Если в радиусе searchRadius обнаружен глубокий океан,
     * бинарным поиском находит точную координату стыка суши и воды.
     *
     * @return BlockPos точки на берегу у воды, либо Optional.empty() если город сухопутный.
     */
    public static Optional<BlockPos> findCoastlinePier(ChunkGenerator chunkGenerator, LevelHeightAccessor heightAccessor,
                                                      RandomState randomState, BlockPos townCenter, int searchRadius) {
        int bestX = 0;
        int bestZ = 0;
        double bestDistSq = Double.MAX_VALUE;
        boolean foundPier = false;

        final int RAYS = 16;
        for (int i = 0; i < RAYS; i++) {
            double angle = (2.0 * Math.PI * i) / RAYS;
            int targetX = (int) (townCenter.getX() + Math.cos(angle) * searchRadius);
            int targetZ = (int) (townCenter.getZ() + Math.sin(angle) * searchRadius);

            int outerDepth = getWaterDepth(chunkGenerator, heightAccessor, randomState, targetX, targetZ);
            Holder<Biome> targetBiome = getNoiseBiome(chunkGenerator, randomState, targetX, SEA_LEVEL, targetZ);
            if (outerDepth >= 3 && targetBiome.is(BiomeTags.IS_OCEAN)) {
                // Бинарный поиск границы суша/вода
                double low = 0.0;
                double high = 1.0;
                int pierX = targetX;
                int pierZ = targetZ;

                for (int step = 0; step < 8; step++) {
                    double mid = (low + high) * 0.5;
                    int curX = (int) (townCenter.getX() + (targetX - townCenter.getX()) * mid);
                    int curZ = (int) (townCenter.getZ() + (targetZ - townCenter.getZ()) * mid);

                    int depth = getWaterDepth(chunkGenerator, heightAccessor, randomState, curX, curZ);
                    if (depth > 0) {
                        high = mid;
                        pierX = curX;
                        pierZ = curZ;
                    } else {
                        low = mid;
                    }
                }

                double distSq = townCenter.distSqr(new BlockPos(pierX, SEA_LEVEL, pierZ));
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestX = pierX;
                    bestZ = pierZ;
                    foundPier = true;
                }
            }
        }

        if (foundPier) {
            int y = getSurfaceHeight(chunkGenerator, heightAccessor, randomState, bestX, bestZ);
            return Optional.of(new BlockPos(bestX, Math.max(SEA_LEVEL, y), bestZ));
        }

        return Optional.empty();
    }
}
