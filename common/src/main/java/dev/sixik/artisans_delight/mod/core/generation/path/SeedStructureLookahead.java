package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.mod.api.KnotApi;
import dev.sixik.artisans_delight.mod.core.generation.terrain.TerrainSampler;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownLodgingComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownPortComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownWarehouseComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownWorkshopsComponent;
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

    /**
     * Общий мьютекс всех городовых мутаций менеджера: предикт и миксин
     * физической генерации меняют города только под ним. Без него конкурентный
     * предикт и генерация чанка дают два объекта одного села: предсказанный
     * центр чанка vs реальный origin структуры (~11 блоков — два пина рядом).
     */
    public static final Object TOWN_MUTEX = new Object();

    /**
     * Радиус слияния дублей городов. Заведомо меньше {@code 128} из проверок
     * близости (чтобы не трогать реально соседние сёла), но с запасом покрывает
     * расхождение предсказания и физической генерации (~11 блоков).
     */
    private static final double TOWN_DEDUP_RADIUS = 96.0;

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

        // Все мутации менеджера — под TOWN_MUTEX (см. поле): проверка+вставка
        // атомарны относительно миксина физической генерации.
        synchronized (TOWN_MUTEX) {
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

                    // 3. Проверяем, нет ли уже зарегистрированного узла рядом.
                    // WAYPOINT-развилки игнорируем: они не деревни и не должны блокировать предикт поселений.
                    if (hasNearbyTownKnot(manager, candBlockX, candBlockZ, 128.0)) {
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

        // Ремонт гонок прошлого: сносим дубли ближе TOWN_DEDUP_RADIUS.
        // Вызывается под мьютексом из всех путей (init, миксин, открытие карты).
        int mergedDupes = dedupeTownKnots(manager);
        if (mergedDupes > 0) {
            LOGGER.info("Seed Lookahead: смержено дублей городов: {}", mergedDupes);
        }
        } // end synchronized (TOWN_MUTEX)

        if (!predictedKnots.isEmpty()) {
            LOGGER.info("Seed Lookahead: предсказано {} поселений в радиусе {} блоков вокруг ({}, {})",
                    predictedKnots.size(), radiusBlocks, centerBlockX, centerBlockZ);
        }

        return predictedKnots;
    }

    /**
     * Сносит дубли городов ближе {@link #TOWN_DEDUP_RADIUS} друг к другу.
     *
     * <p>Выживает более «богатый» объект (реальный, со складом/домами/портом —
     * у чистого предсказания только мета+связи+маршруты). При ничьей — первый
     * по id (детерминированно). Железо (порт, склад, барак, мастерские)
     * переносится с удаляемого на выжившего, если того не хватало.
     *
     * <p>Вызывать только под {@link #TOWN_MUTEX}.
     *
     * @return число удалённых дублей
     */
    public static int dedupeTownKnots(WorldObjectManager manager) {
        if (manager == null) return 0;
        List<WorldObject> towns = new ArrayList<>();
        for (WorldObject obj : manager.snapshot()) {
            KnotMetaComponent meta = obj.getSingle(KnotMetaComponent.class).orElse(null);
            if (meta == null || meta.type.get() != KnotType.TOWN) continue;
            if (meta.position.get() == null) continue;
            towns.add(obj);
        }
        if (towns.size() < 2) return 0;
        towns.sort(Comparator.comparing(o -> o.id().toString()));

        double r2 = TOWN_DEDUP_RADIUS * TOWN_DEDUP_RADIUS;
        Set<UUID> dead = new HashSet<>();
        int removed = 0;
        for (int i = 0; i < towns.size(); i++) {
            WorldObject surv = towns.get(i);
            if (dead.contains(surv.id())) continue;
            BlockPos survPos = townPos(surv);
            if (survPos == null) continue;
            for (int j = i + 1; j < towns.size(); j++) {
                WorldObject other = towns.get(j);
                if (dead.contains(other.id())) continue;
                BlockPos otherPos = townPos(other);
                if (otherPos == null) continue;
                double dx = (double) survPos.getX() - otherPos.getX();
                double dz = (double) survPos.getZ() - otherPos.getZ();
                if (dx * dx + dz * dz > r2) continue;
                WorldObject keep = townRichness(other) > townRichness(surv) ? other : surv;
                WorldObject drop = (keep == surv) ? other : surv;
                transferTownHardware(drop, keep);
                if (manager.remove(drop.id())) {
                    dead.add(drop.id());
                    removed++;
                    LOGGER.info("Seed Lookahead: дубль {} merged into {} ({} м)",
                        townLabel(drop), townLabel(keep),
                        (int) Math.sqrt(dx * dx + dz * dz));
                }
                if (drop == surv) {
                    surv = keep;
                    survPos = townPos(surv);
                    if (survPos == null) break;
                }
            }
        }
        return removed;
    }

    private static BlockPos townPos(WorldObject town) {
        KnotMetaComponent meta = town.getSingle(KnotMetaComponent.class).orElse(null);
        return meta == null ? null : meta.position.get();
    }

    private static String townLabel(WorldObject town) {
        KnotMetaComponent meta = town.getSingle(KnotMetaComponent.class).orElse(null);
        if (meta == null) return town.id().toString().substring(0, 8);
        BlockPos pos = meta.position.get();
        String name = "<?>";
        try {
            name = String.valueOf(meta.name.get());
        } catch (Throwable ignored) {
        }
        return pos == null ? name : name + " [" + pos.getX() + "," + pos.getZ() + "]";
    }

    /** Число игровых компонентов: реальный город богаче чистого предсказания. */
    private static int townRichness(WorldObject town) {
        int score = 0;
        if (town.getSingle(KnotConnectionComponent.class).isPresent()) score++;
        if (town.getSingle(KnotRoutesComponent.class).isPresent()) score++;
        if (town.getSingle(TownWarehouseComponent.class).isPresent()) score++;
        if (town.getSingle(TownLodgingComponent.class).isPresent()) score++;
        if (town.getSingle(TownWorkshopsComponent.class).isPresent()) score++;
        if (town.getSingle(TownPortComponent.class).isPresent()) score++;
        return score;
    }

    /** Переносит недостающее железо с удаляемого дубля на выжившего. */
    private static void transferTownHardware(WorldObject drop, WorldObject keep) {
        try {
            if (keep.getSingle(TownWarehouseComponent.class).isEmpty()
                && drop.getSingle(TownWarehouseComponent.class).isPresent()) {
                keep.attach(new TownWarehouseComponent());
            }
            if (keep.getSingle(TownLodgingComponent.class).isEmpty()
                && drop.getSingle(TownLodgingComponent.class).isPresent()) {
                keep.attach(new TownLodgingComponent());
            }
            if (keep.getSingle(TownWorkshopsComponent.class).isEmpty()
                && drop.getSingle(TownWorkshopsComponent.class).isPresent()) {
                keep.attach(new TownWorkshopsComponent());
            }
            Optional<TownPortComponent> dropPort = drop.getSingle(TownPortComponent.class);
            if (keep.getSingle(TownPortComponent.class).isEmpty() && dropPort.isPresent()) {
                BlockPos pier = dropPort.get().pierPos.get();
                keep.attach(new TownPortComponent(pier == null ? BlockPos.ZERO : pier));
            }
        } catch (Throwable t) {
            LOGGER.warn("Seed Lookahead: не перенеслось железо дубля: {}", t.toString());
        }
    }

    public static boolean hasNearbyKnot(WorldObjectManager manager, int blockX, int blockZ, double maxDist) {
        if (manager == null) return false;
        double maxDistSq = maxDist * maxDist;

        for (WorldObject obj : manager.all()) {
            Optional<KnotMetaComponent> mOpt = obj.getSingle(KnotMetaComponent.class);
            if (mOpt.isPresent()) {
                BlockPos pos = mOpt.get().position.get();
                double dx = pos.getX() - blockX;
                double dz = pos.getZ() - blockZ;
                if (dx * dx + dz * dz <= maxDistSq) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Проверка наличия рядом только «настоящих» узлов (TOWN/PRODUCTION/OUTPOST).
     * WAYPOINT-развилки от {@link JunctionEngine} игнорируются: это служебные точки
     * схождения дорог, а не поселения, и они не должны блокировать предикт деревень
     * или считаться промежуточными в топологии.
     */
    public static boolean hasNearbyTownKnot(WorldObjectManager manager, int blockX, int blockZ, double maxDist) {
        if (manager == null) return false;
        double maxDistSq = maxDist * maxDist;

        for (WorldObject obj : manager.all()) {
            Optional<KnotMetaComponent> mOpt = obj.getSingle(KnotMetaComponent.class);
            if (mOpt.isEmpty()) continue;
            if (mOpt.get().type.get() == KnotType.WAYPOINT) continue;
            BlockPos pos = mOpt.get().position.get();
            double dx = pos.getX() - blockX;
            double dz = pos.getZ() - blockZ;
            if (dx * dx + dz * dz <= maxDistSq) {
                return true;
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
            if (mOpt.isEmpty()) continue;
            // WAYPOINT-развилки — служебные точки схождения дорог, а не поселения:
            // структура деревни никогда не должна сливаться с развилкой.
            if (mOpt.get().type.get() == KnotType.WAYPOINT) continue;
            BlockPos kPos = mOpt.get().position.get();
            double dx = kPos.getX() - pos.getX();
            double dz = kPos.getZ() - pos.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq <= maxDistSq && distSq < closestDistSq) {
                closestDistSq = distSq;
                closest = obj;
            }
        }
        return Optional.ofNullable(closest);
    }
}
