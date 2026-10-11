package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.mod.api.KnotApi;
import dev.sixik.artisans_delight.mod.core.generation.terrain.TerrainSampler;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownPortComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Детерминированный предиктивный сканер структур по сиду мира (Seed Structure Lookahead).
 *
 * Позволяет за доли миллисекунды вычислить точные координаты будущих деревень и поселений
 * в радиусе 2000–3500 блоков от текущего узла БЕЗ генерации и загрузки чанков.
 *
 * Благодаря этому начальная топология дорожной сети строится детерминированно и стабильно
 * (A -> C -> B), исключая ложные прямые дороги в обход промежуточных поселений.
 */
public final class SeedStructureLookahead {

    private static final Logger LOGGER = LoggerFactory.getLogger(SeedStructureLookahead.class);

    private SeedStructureLookahead() {
    }

    /**
     * Поиск и предсказание всех структур поселений в заданном радиусе от центральной точки.
     * Найденные поселения регистрируются в WorldObjectManager как узлы мира.
     *
     * @return список новых предсказанных узлов
     */
    public static List<WorldObject> predictKnotsInRange(
            WorldObjectManager manager,
            ServerLevel level,
            ChunkGenerator chunkGenerator,
            RandomState randomState,
            BlockPos centerPos,
            int radiusBlocks) {

        if (manager == null || level == null || chunkGenerator == null || centerPos == null) {
            return Collections.emptyList();
        }

        ChunkGeneratorStructureState structureState = level.getChunkSource().getGeneratorState();
        if (structureState == null) {
            return Collections.emptyList();
        }

        long levelSeed = structureState.getLevelSeed();
        List<Holder<StructureSet>> possibleSets = structureState.possibleStructureSets();
        if (possibleSets == null || possibleSets.isEmpty()) {
            return Collections.emptyList();
        }

        int centerBlockX = centerPos.getX();
        int centerBlockZ = centerPos.getZ();
        int centerChunkX = SectionPos.blockToSectionCoord(centerBlockX);
        int centerChunkZ = SectionPos.blockToSectionCoord(centerBlockZ);
        int chunkRadius = (radiusBlocks + 15) / 16;
        double radiusSq = (double) radiusBlocks * radiusBlocks;

        List<WorldObject> predictedKnots = new ArrayList<>();
        Set<Long> processedChunks = new HashSet<>();

        for (Holder<StructureSet> setHolder : possibleSets) {
            StructureSet set = setHolder.value();
            if (set == null) continue;

            // Проверяем, генерирует ли этот набор структур поселения/деревни
            Holder<Structure> townStructure = null;
            for (StructureSet.StructureSelectionEntry entry : set.structures()) {
                if (KnotApi.getType(entry.structure()) == KnotType.TOWN) {
                    townStructure = entry.structure();
                    break;
                }
            }
            if (townStructure == null) continue;

            StructurePlacement placement = set.placement();
            if (!(placement instanceof RandomSpreadStructurePlacement spread)) continue;

            int spacing = spread.spacing();
            if (spacing <= 0) continue;

            int minRegionI = Math.floorDiv(centerChunkX - chunkRadius, spacing);
            int maxRegionI = Math.floorDiv(centerChunkX + chunkRadius, spacing);
            int minRegionJ = Math.floorDiv(centerChunkZ - chunkRadius, spacing);
            int maxRegionJ = Math.floorDiv(centerChunkZ + chunkRadius, spacing);

            for (int rI = minRegionI; rI <= maxRegionI; rI++) {
                for (int rJ = minRegionJ; rJ <= maxRegionJ; rJ++) {
                    ChunkPos candidateChunk = spread.getPotentialStructureChunk(levelSeed, rI * spacing, rJ * spacing);
                    long chunkKey = candidateChunk.toLong();
                    if (!processedChunks.add(chunkKey)) continue;

                    int candBlockX = SectionPos.sectionToBlockCoord(candidateChunk.x, 8);
                    int candBlockZ = SectionPos.sectionToBlockCoord(candidateChunk.z, 8);

                    double distSq = (double) (candBlockX - centerBlockX) * (candBlockX - centerBlockX)
                            + (double) (candBlockZ - centerBlockZ) * (candBlockZ - centerBlockZ);
                    if (distSq > radiusSq) continue;

                    // 1. Проверяем, подходит ли биом в этой точке для структуры деревни
                    Holder<Biome> noiseBiome = chunkGenerator.getBiomeSource().getNoiseBiome(
                            QuartPos.fromBlock(candBlockX),
                            QuartPos.fromBlock(64),
                            QuartPos.fromBlock(candBlockZ),
                            randomState.sampler()
                    );

                    if (!townStructure.value().biomes().contains(noiseBiome)) {
                        continue; // Биом не подходит для данной деревни
                    }

                    // 2. Проверяем высоту поверхности (исключаем открытый океан)
                    int surfaceY = chunkGenerator.getBaseHeight(
                            candBlockX, candBlockZ,
                            Heightmap.Types.WORLD_SURFACE_WG,
                            level,
                            randomState
                    );

                    if (surfaceY < 62) {
                        continue; // Водоём, суши нет
                    }

                    // 3. Проверяем, нет ли уже зарегистрированного узла рядом
                    if (hasNearbyKnot(manager, candBlockX, candBlockZ, 128.0)) {
                        continue;
                    }

                    // 4. Создаем предсказанный узел поселения
                    BlockPos knotPos = new BlockPos(candBlockX, surfaceY, candBlockZ);
                    WorldObject knot = new WorldObject();
                    String name = "Деревня_" + candidateChunk.x + "_" + candidateChunk.z;
                    ResourceLocation icon = ResourceLocation.tryBuild("minecraft", "air");

                    knot.attach(new KnotMetaComponent(name, icon, KnotType.TOWN, knotPos));
                    knot.attach(new KnotConnectionComponent());
                    knot.attach(new KnotRoutesComponent());

                    // Проверяем, является ли поселение прибрежным портом
                    Optional<BlockPos> pierPos = TerrainSampler.findCoastlinePier(
                            chunkGenerator, level, randomState, knotPos, 180
                    );
                    pierPos.ifPresent(pos -> knot.attach(new TownPortComponent(pos)));

                    manager.add(knot);
                    predictedKnots.add(knot);
                }
            }
        }

        if (!predictedKnots.isEmpty()) {
            LOGGER.info("Seed Lookahead: предсказано {} поселений в радиусе {} блоков вокруг ({}, {})",
                    predictedKnots.size(), radiusBlocks, centerBlockX, centerBlockZ);
        }

        return predictedKnots;
    }

    public static boolean hasNearbyKnot(WorldObjectManager manager, int blockX, int blockZ, double maxDist) {
        if (manager == null) return false;
        double maxDistSq = maxDist * maxDist;

        for (WorldObject obj : manager.all()) {
            Optional<KnotMetaComponent> mOpt = obj.getSingle(KnotMetaComponent.class);
            if (mOpt.isPresent()) {
                BlockPos pos = mOpt.get().pos.get();
                double dx = pos.getX() - blockX;
                double dz = pos.getZ() - blockZ;
                if (dx * dx + dz * dz <= maxDistSq) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Optional<WorldObject> findNearbyKnot(WorldObjectManager manager, BlockPos pos, double maxDist) {
        if (manager == null || pos == null) return Optional.empty();
        double maxDistSq = maxDist * maxDist;

        WorldObject closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (WorldObject obj : manager.all()) {
            Optional<KnotMetaComponent> mOpt = obj.getSingle(KnotMetaComponent.class);
            if (mOpt.isPresent()) {
                BlockPos kPos = mOpt.get().pos.get();
                double dx = kPos.getX() - pos.getX();
                double dz = kPos.getZ() - pos.getZ();
                double distSq = dx * dx + dz * dz;
                if (distSq <= maxDistSq && distSq < closestDistSq) {
                    closestDistSq = distSq;
                    closest = obj;
                }
            }
        }
        return Optional.ofNullable(closest);
    }
}
