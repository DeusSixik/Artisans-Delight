package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Сервис органического вытаптывания, мощения и природной эволюции дорог (Road Paving Service).
 *
 * 1. При генерации чанков мира (applyBiomeDecoration) аккуратно прорезает базовое дорожное полотно (Tier 1).
 * 2. При движении караванов и торговых сущностей постепенно накатывает и улучшает дорожное покрытие
 *    (трава -> dirt_path -> гравий -> булыжник / мостовая) в зависимости от интенсивности трафика.
 * 3. Поддерживает природную деградацию и зарастание заброшенных участков дорог (Overgrowth).
 */
public final class RoadPavingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadPavingService.class);

    /**
     * Главный выключатель мощения дорог в мире.
     *
     * <p>Укладка при генерации чанков идёт по виртуальной сети
     * ({@code RoadNetworkStore}) через {@link RoadPaver}; доукладка в уже
     * сгенерированные чанки — отдельным флагом {@link RoadPaver#retroPavingEnabled()}.
     */
    public static final boolean PAVING_ENABLED = true;

    public enum PavingTier {
        TRAIL,    // Грунтовая тропа: расчистка травы, грязь / dirt_path
        ROAD,     // Наезженный тракт: гравий, плотная грязь, настилы
        HIGHWAY   // Имперская мостовая: булыжник, каменный кирпич
    }

    private RoadPavingService() {
    }

    /**
     * Вытаптывание и мощение дороги под сущностью (караваном, повозкой, торговцем).
     *
     * @param level          мир
     * @param entityPos      позиция сущности
     * @param radius         радиус воздействия вокруг сущности (обычно 1.5 - 2.5)
     * @param targetTier     целевой уровень мощения
     * @param upgradeChance  вероятность улучшения блока за шаг (0.1 - 0.4)
     */
    public static void paveUnderEntity(
            Level level, BlockPos entityPos, double radius, PavingTier targetTier, float upgradeChance) {

        if (!PAVING_ENABLED) return;
        if (level == null || entityPos == null || level.isClientSide()) return;

        int minX = (int) Math.floor(entityPos.getX() - radius);
        int maxX = (int) Math.ceil(entityPos.getX() + radius);
        int minZ = (int) Math.floor(entityPos.getZ() - radius);
        int maxZ = (int) Math.ceil(entityPos.getZ() + radius);
        double radiusSq = radius * radius;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double distSq = (x - entityPos.getX()) * (x - entityPos.getX())
                        + (z - entityPos.getZ()) * (z - entityPos.getZ());
                if (distSq > radiusSq) continue;

                // Ищем верхний твердый блок почвы в вертикальном окне вокруг сущности
                int surfaceY = findSurfaceAround(level, x, entityPos.getY(), z);
                if (surfaceY == Integer.MIN_VALUE) continue;

                cursor.set(x, surfaceY, z);
                BlockState surfaceState = level.getBlockState(cursor);

                // 1. Расчищаем растительность прямо над дорогой
                cursor.set(x, surfaceY + 1, z);
                BlockState aboveState = level.getBlockState(cursor);
                if (aboveState.canBeReplaced() && !aboveState.is(Blocks.WATER)) {
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), 2);
                }

                // 2. Улучшаем покрытие почвы под ногами с заданной вероятностью
                cursor.set(x, surfaceY, z);
                if (level.random.nextFloat() <= upgradeChance) {
                    BlockState upgraded = evolveBlockState(surfaceState, targetTier, level.random);
                    if (upgraded != null && !upgraded.equals(surfaceState)) {
                        level.setBlock(cursor, upgraded, 2);
                    }
                }
            }
        }
    }

    /**
     * Эволюция блока поверхности в зависимости от тира мощения.
     */
    public static BlockState evolveBlockState(BlockState current, PavingTier tier, RandomSource random) {
        if (current.isAir() || current.is(Blocks.BEDROCK) || current.is(Blocks.BARRIER)) {
            return null;
        }

        switch (tier) {
            case TRAIL -> {
                if (current.is(Blocks.GRASS_BLOCK) || current.is(Blocks.DIRT) || current.is(Blocks.PODZOL) || current.is(Blocks.MYCELIUM)) {
                    return Blocks.DIRT_PATH.defaultBlockState();
                }
                if (current.is(Blocks.SAND)) {
                    return Blocks.SANDSTONE.defaultBlockState();
                }
                if (current.is(Blocks.SNOW_BLOCK)) {
                    return Blocks.PACKED_ICE.defaultBlockState();
                }
            }
            case ROAD -> {
                if (current.is(Blocks.DIRT_PATH)) {
                    float r = random.nextFloat();
                    if (r < 0.45f) return Blocks.GRAVEL.defaultBlockState();
                    if (r < 0.80f) return Blocks.COARSE_DIRT.defaultBlockState();
                    return Blocks.PACKED_MUD.defaultBlockState();
                }
                if (current.is(Blocks.GRASS_BLOCK) || current.is(Blocks.DIRT)) {
                    return Blocks.DIRT_PATH.defaultBlockState();
                }
                if (current.is(Blocks.WATER)) {
                    return Blocks.OAK_SLAB.defaultBlockState();
                }
            }
            case HIGHWAY -> {
                if (current.is(Blocks.GRAVEL) || current.is(Blocks.COARSE_DIRT) || current.is(Blocks.DIRT_PATH) || current.is(Blocks.PACKED_MUD)) {
                    float r = random.nextFloat();
                    if (r < 0.55f) return Blocks.COBBLESTONE.defaultBlockState();
                    if (r < 0.85f) return Blocks.STONE_BRICKS.defaultBlockState();
                    return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
                }
                if (current.is(Blocks.GRASS_BLOCK)) {
                    return Blocks.DIRT_PATH.defaultBlockState();
                }
            }
        }

        return null;
    }

    /**
     * Постепенное природное зарастание и деградация заброшенного участка дороги (Overgrowth).
     */
    public static void decayRoadAt(Level level, BlockPos pos, double radius, float decayChance) {
        if (level == null || pos == null || level.isClientSide()) return;

        int minX = (int) Math.floor(pos.getX() - radius);
        int maxX = (int) Math.ceil(pos.getX() + radius);
        int minZ = (int) Math.floor(pos.getZ() - radius);
        int maxZ = (int) Math.ceil(pos.getZ() + radius);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (level.random.nextFloat() > decayChance) continue;

                int surfaceY = findSurfaceAround(level, x, pos.getY(), z);
                if (surfaceY == Integer.MIN_VALUE) continue;

                cursor.set(x, surfaceY, z);
                BlockState state = level.getBlockState(cursor);

                // Деградация камня в мох и грунт
                if (state.is(Blocks.STONE_BRICKS)) {
                    level.setBlock(cursor, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 2);
                } else if (state.is(Blocks.COBBLESTONE)) {
                    level.setBlock(cursor, Blocks.MOSSY_COBBLESTONE.defaultBlockState(), 2);
                } else if (state.is(Blocks.MOSSY_COBBLESTONE) || state.is(Blocks.MOSSY_STONE_BRICKS)) {
                    level.setBlock(cursor, Blocks.COARSE_DIRT.defaultBlockState(), 2);
                } else if (state.is(Blocks.DIRT_PATH) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PACKED_MUD)) {
                    level.setBlock(cursor, Blocks.GRASS_BLOCK.defaultBlockState(), 2);

                    // С вероятностью 30% прорастает трава поверх
                    cursor.set(x, surfaceY + 1, z);
                    if (level.getBlockState(cursor).isAir()) {
                        level.setBlock(cursor, Blocks.SHORT_GRASS.defaultBlockState(), 2);
                    }
                }
            }
        }
    }

    /**
     * Автоматическая первичная прокладка дорог при генерации чанка мира (Chunk Decoration).
     */
    public static void paveChunk(WorldGenLevel level, ChunkAccess chunk) {
        if (!PAVING_ENABLED) return;
        if (level == null || chunk == null) return;
        try {
            RoadPaver.paveWorldgen(level, chunk);
        } catch (Throwable t) {
            // Генерация мира не должна падать из-за дорог.
            LOGGER.warn("[RoadPavingService] Ошибка укладки чанка {}: {}", chunk.getPos(), t.toString());
        }
    }

    private static int findSurfaceAround(Level level, int x, int centerY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, centerY + 3, z);
        for (int dy = 3; dy >= -4; dy--) {
            pos.setY(centerY + dy);
            BlockState state = level.getBlockState(pos);
            if (state.blocksMotion() || state.is(Blocks.WATER)) {
                return pos.getY();
            }
        }
        return Integer.MIN_VALUE;
    }
}
