package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadCalcCache;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadCommitStore;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadEdge;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetwork;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetworkStore;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNode;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadPavingLedger;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Укладка дорог в мир: {@code dirt_path} шириной 3 по готовой геометрии
 * из {@link RoadNetworkStore}.
 *
 * <p><b>Две ветки.</b>
 * <ul>
 *   <li><b>При генерации чанка</b> ({@link #paveWorldgen}) — основная. Кроме
 *       укладки вырубает препятствия на трассе: растительность, листву и, вне
 *       структур, брёвна. Вырубка — только здесь: в живом мире маршрут меняет
 *       механика перестройки ({@code RoadEventsApi}).</li>
 *   <li><b>Доукладка</b> ({@link #retroPass}) — в уже сгенерированные чанки,
 *       если дорога досчиталась позже их генерации. Только замена природного
 *       грунта на тропу и снятие травы/цветов над ней. Выключается
 *       {@link #retroPavingEnabled(boolean)} (дебаг: Ctrl+P на карте).</li>
 * </ul>
 *
 * <p><b>Закрепление.</b> Если в чанке поменялся хоть один блок дороги,
 * её ребро закрепляется ({@link RoadCommitStore}) — сеть его больше не уберёт.
 * Обработка чанка отмечается в {@link RoadPavingLedger}, даже если блоков
 * не поменялось: чанк для этого ребра больше не проверяется.
 *
 * <p><b>Соседи при генерации.</b> Деревья соседних чанков, декорируемых
 * позже, могут дорасти в наш чанк уже после его укладки. Поэтому при
 * декорации чанка обрабатываются все 3×3 чанка региона: последний
 * декорируемый сосед зачистит трассу и у нас. Это безопасно — пока чанк
 * декорирует фичи, ванилла держит его соседей (сами фичи пишут в них же);
 * чанк не может стать полным, пока все соседи не прошли стадию фич.
 */
public final class RoadPaver {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadPaver.class);

    /**
     * Половина ширины дороги: колонка, чей центр не дальше этого от оси, — дорога.
     * 1.25: на прямой 3 колонки (расстояния 0 и 1), на диагонали без дыр.
     */
    private static final double HALF_WIDTH = 1.25;
    /** Насколько выше шумовой поверхности искать препятствия (кроны деревьев). */
    private static final int SCAN_ABOVE = 40;
    /** Насколько ниже шумовой поверхности искать грунт. */
    private static final int SCAN_BELOW = 12;
    /** Радиус доукладки вокруг игрока (чанки) — в пределах загруженной зоны. */
    private static final int RETRO_RADIUS_CHUNKS = 10;
    /** Максимум чанков за один проход доукладки (бюджет серверного тика). */
    private static final int RETRO_CHUNKS_PER_PASS = 6;

    /** Доукладка в уже сгенерированные чанки (дебаг-переключатель Ctrl+P). */
    private static volatile boolean retroPavingEnabled = true;

    /** Индекс отрезков по чанкам для текущей ревизии сети. */
    private static volatile Index index = Index.EMPTY;
    private static final Object INDEX_LOCK = new Object();

    private RoadPaver() {
    }

    public static boolean retroPavingEnabled() {
        return retroPavingEnabled;
    }

    public static void retroPavingEnabled(boolean enabled) {
        retroPavingEnabled = enabled;
    }

    // =========================================================================
    // Индекс: чанк → отрезки готовых сухопутных дорог
    // =========================================================================

    /**
     * Отрезок дороги.
     *
     * @param edge      базовое ребро — его закрепляем (объезд не закрепляется)
     * @param ledgerKey ключ учёта укладки (у объездной геометрии свой)
     */
    private record Seg(RoadEdge edge, String ledgerKey, RoadNode a, RoadNode b,
                       double x1, double z1, double x2, double z2) {
    }

    private record Index(long revision, Map<Long, List<Seg>> byChunk) {
        static final Index EMPTY = new Index(-1L, Map.of());
    }

    /** Индекс для текущей ревизии сети (строится лениво, раз на ревизию). */
    private static Index currentIndex() {
        long rev = RoadNetworkStore.revision();
        Index idx = index;
        if (idx.revision() == rev) {
            return idx;
        }
        synchronized (INDEX_LOCK) {
            idx = index;
            if (idx.revision() == rev) {
                return idx;
            }
            idx = buildIndex(RoadNetworkStore.current(), rev);
            index = idx;
            return idx;
        }
    }

    private static Index buildIndex(RoadNetwork net, long rev) {
        if (net == null) {
            return new Index(rev, Map.of());
        }
        Map<Long, List<Seg>> byChunk = new HashMap<>();
        int margin = (int) Math.ceil(HALF_WIDTH) + 1;
        for (RoadEdge e : net.edges()) {
            if (e.pending() || e.seaRoute()) {
                continue;
            }
            RoadNode a = net.node(e.fromId());
            RoadNode b = net.node(e.toId());
            if (a == null || b == null) {
                continue;
            }
            List<RoadPoint2D> g = e.geometry();
            RoadEdge commitEdge = RouteDetourEngine.baselineOf(e);
            String ledgerKey = RouteDetourEngine.pavingKey(e);
            for (int i = 0; i < g.size() - 1; i++) {
                RoadPoint2D p = g.get(i);
                RoadPoint2D q = g.get(i + 1);
                if (p.isBridge() && q.isBridge()) {
                    continue; // мосты — отдельный шаг
                }
                // Ось — по центрам блоков (+0.5): колонки меряются от центров,
                // иначе прямой участок выходит шириной 4, а не 3.
                Seg s = new Seg(commitEdge, ledgerKey, a, b,
                    p.x() + 0.5, p.z() + 0.5, q.x() + 0.5, q.z() + 0.5);
                int cx0 = Math.floorDiv(Math.min(p.x(), q.x()) - margin, 16);
                int cx1 = Math.floorDiv(Math.max(p.x(), q.x()) + margin, 16);
                int cz0 = Math.floorDiv(Math.min(p.z(), q.z()) - margin, 16);
                int cz1 = Math.floorDiv(Math.max(p.z(), q.z()) + margin, 16);
                for (int cx = cx0; cx <= cx1; cx++) {
                    for (int cz = cz0; cz <= cz1; cz++) {
                        byChunk.computeIfAbsent(RoadPavingLedger.chunkKey(cx, cz), k -> new ArrayList<>()).add(s);
                    }
                }
            }
        }
        return new Index(rev, byChunk);
    }

    /** Совпадает ли кэш/сеть с этим миром (сид + измерение). */
    private static boolean scopeMatches(ServerLevel level, long seed) {
        String scope = RoadCalcCache.scope();
        return scope != null && scope.equals(RoadCalcCache.scopeKey(seed, level.dimension().location().toString()));
    }

    // =========================================================================
    // Укладка при генерации
    // =========================================================================

    /**
     * Укладка при декорации чанка (вызывается после фич, на потоке генерации).
     * Обрабатывает центральный чанк и соседей региона 3×3.
     */
    public static void paveWorldgen(WorldGenLevel level, ChunkAccess center) {
        ServerLevel server = level.getLevel();
        if (!scopeMatches(server, level.getSeed())) {
            return;
        }
        Index idx = currentIndex();
        if (idx.byChunk().isEmpty()) {
            return;
        }
        int ccx = center.getPos().x;
        int ccz = center.getPos().z;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = ccx + dx;
                int cz = ccz + dz;
                long key = RoadPavingLedger.chunkKey(cx, cz);
                List<Seg> segs = idx.byChunk().get(key);
                if (segs == null) {
                    continue;
                }
                ChunkAccess chunk;
                if (dx == 0 && dz == 0) {
                    chunk = center;
                } else {
                    if (!level.hasChunk(cx, cz)) {
                        continue;
                    }
                    chunk = level.getChunk(cx, cz);
                }
                // Только «живые» прото-чанки; обёртка над полным чанком — не трогаем.
                if (!(chunk instanceof ProtoChunk) || chunk instanceof ImposterProtoChunk) {
                    continue;
                }
                boolean clearLogs = chunk.getAllReferences().isEmpty() && chunk.getAllStarts().isEmpty();
                WorldgenColumns op = new WorldgenColumns(chunk, clearLogs);
                paveChunk(cx, cz, segs, op);
            }
        }
    }

    /** Колонка при генерации: вырубка препятствий + тропа. */
    private static final class WorldgenColumns implements ColumnOp {
        private final ChunkAccess chunk;
        private final boolean clearLogs;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final List<Integer> toClear = new ArrayList<>();

        WorldgenColumns(ChunkAccess chunk, boolean clearLogs) {
            this.chunk = chunk;
            this.clearLogs = clearLogs;
        }

        @Override
        public boolean pave(int x, int z) {
            int wg = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
            int top = Math.min(chunk.getMaxBuildHeight() - 1, wg + SCAN_ABOVE);
            int bottom = Math.max(chunk.getMinBuildHeight(), wg - SCAN_BELOW);
            toClear.clear();
            int groundY = Integer.MIN_VALUE;
            for (int y = top; y >= bottom; y--) {
                cursor.set(x, y, z);
                BlockState s = chunk.getBlockState(cursor);
                if (s.isAir()) {
                    continue;
                }
                if (!s.getFluidState().isEmpty() && !s.is(BlockTags.LEAVES)) {
                    return false; // вода/лава — не наша колонка (мосты — отдельно)
                }
                if (isObstacle(s, clearLogs)) {
                    toClear.add(y);
                    continue;
                }
                groundY = y;
                break;
            }
            if (groundY == Integer.MIN_VALUE) {
                return false;
            }
            boolean changed = false;
            for (int y : toClear) {
                cursor.set(x, y, z);
                chunk.setBlockState(cursor, Blocks.AIR.defaultBlockState(), false);
                changed = true;
            }
            cursor.set(x, groundY, z);
            BlockState ground = chunk.getBlockState(cursor);
            cursor.set(x, groundY + 1, z);
            boolean airAbove = chunk.getBlockState(cursor).isAir();
            if (airAbove && isPathable(ground)) {
                cursor.set(x, groundY, z);
                chunk.setBlockState(cursor, Blocks.DIRT_PATH.defaultBlockState(), false);
                changed = true;
            }
            return changed;
        }
    }

    // =========================================================================
    // Доукладка в уже сгенерированные чанки
    // =========================================================================

    /**
     * Проход доукладки (серверный поток): загруженные чанки рядом с игроками,
     * где готовая дорога ещё не обработана. Не больше
     * {@link #RETRO_CHUNKS_PER_PASS} чанков за проход.
     *
     * @param players позиции игроков {@code {x, z}}
     */
    public static void retroPass(ServerLevel level, long seed, double[][] players) {
        if (!retroPavingEnabled || players.length == 0 || !scopeMatches(level, seed)) {
            return;
        }
        Index idx = currentIndex();
        if (idx.byChunk().isEmpty()) {
            return;
        }
        int budget = RETRO_CHUNKS_PER_PASS;
        Set<Long> seen = new HashSet<>();
        for (double[] p : players) {
            int pcx = Math.floorDiv((int) Math.floor(p[0]), 16);
            int pcz = Math.floorDiv((int) Math.floor(p[1]), 16);
            // Ближние кольца первыми: дорога появляется сначала рядом с игроком.
            for (int r = 0; r <= RETRO_RADIUS_CHUNKS; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        int cx = pcx + dx;
                        int cz = pcz + dz;
                        long key = RoadPavingLedger.chunkKey(cx, cz);
                        if (!seen.add(key)) {
                            continue;
                        }
                        List<Seg> segs = idx.byChunk().get(key);
                        if (segs == null) {
                            continue;
                        }
                        List<Seg> todo = new ArrayList<>(segs.size());
                        for (Seg s : segs) {
                            if (!RoadPavingLedger.isPaved(key, s.ledgerKey())) {
                                todo.add(s);
                            }
                        }
                        if (todo.isEmpty()) {
                            continue;
                        }
                        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                        if (chunk == null) {
                            continue; // не загружен — доуложим, когда игрок подойдёт
                        }
                        int changed = paveChunk(cx, cz, todo, new RetroColumns(level));
                        if (changed > 0) {
                            LOGGER.debug("[RoadPaver] Доукладка чанка [{}, {}]: {} колонок", cx, cz, changed);
                        }
                        if (--budget <= 0) {
                            return;
                        }
                    }
                }
            }
        }
    }

    /** Колонка доукладки: только грунт → тропа, трава/цветы над ней снимаются. */
    private static final class RetroColumns implements ColumnOp {
        private final ServerLevel level;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        RetroColumns(ServerLevel level) {
            this.level = level;
        }

        @Override
        public boolean pave(int x, int z) {
            // Верх без листвы: ствол дерева/постройка дадут не-грунт — колонка пропускается.
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            cursor.set(x, groundY, z);
            BlockState ground = level.getBlockState(cursor);
            if (!isPathable(ground)) {
                return false;
            }
            cursor.set(x, groundY + 1, z);
            BlockState above = level.getBlockState(cursor);
            if (!above.isAir()) {
                boolean softPlant = above.canBeReplaced() && above.getFluidState().isEmpty();
                if (!softPlant) {
                    return false;
                }
                // UPDATE_ALL: верхняя половина высокой травы/цветка снимется сама.
                level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            cursor.set(x, groundY, z);
            level.setBlock(cursor, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
            return true;
        }
    }

    // =========================================================================
    // Общее: растеризация коридора дороги в чанке
    // =========================================================================

    /** Операция над одной колонкой дороги. */
    private interface ColumnOp {
        /** @return {@code true}, если в колонке поменялся хоть один блок */
        boolean pave(int x, int z);
    }

    /**
     * Проходит колонки чанка, попадающие в коридор дорог, применяет операцию,
     * отмечает рёбра в учёте укладки и закрепляет рёбра с изменениями.
     *
     * <p>Колонка {@code (x, z)} — дорога, если расстояние от её центра
     * {@code (x + ½, z + ½)} до отрезка оси не больше {@link #HALF_WIDTH}:
     * <pre>
     *   t = clamp(((c − p)·(q − p)) / |q − p|², 0, 1),   d = |c − (p + t·(q − p))|
     * </pre>
     *
     * @return число изменённых колонок
     */
    private static int paveChunk(int cx, int cz, List<Seg> segs, ColumnOp op) {
        long key = RoadPavingLedger.chunkKey(cx, cz);
        double hw2 = HALF_WIDTH * HALF_WIDTH;
        Map<String, Seg> changedEdges = new LinkedHashMap<>();
        int changedColumns = 0;
        int x0 = cx * 16;
        int z0 = cz * 16;
        List<Seg> hit = new ArrayList<>(4);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                double px = x0 + lx + 0.5;
                double pz = z0 + lz + 0.5;
                hit.clear();
                for (Seg s : segs) {
                    if (dist2ToSeg(px, pz, s) <= hw2) {
                        hit.add(s);
                    }
                }
                if (hit.isEmpty()) {
                    continue;
                }
                if (op.pave(x0 + lx, z0 + lz)) {
                    changedColumns++;
                    for (Seg s : hit) {
                        changedEdges.putIfAbsent(s.edge().key(), s);
                    }
                }
            }
        }
        for (Seg s : segs) {
            RoadPavingLedger.markPaved(key, s.ledgerKey());
        }
        for (Seg s : changedEdges.values()) {
            // Блоки уже в мире — ребро больше не может исчезнуть из сети.
            RoadCommitStore.commit(s.edge(), s.a(), s.b());
        }
        return changedColumns;
    }

    private static double dist2ToSeg(double px, double pz, Seg s) {
        double dx = s.x2() - s.x1();
        double dz = s.z2() - s.z1();
        double len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0.0 : ((px - s.x1()) * dx + (pz - s.z1()) * dz) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double ex = s.x1() + dx * t - px;
        double ez = s.z1() + dz * t - pz;
        return ex * ex + ez * ez;
    }

    // =========================================================================
    // Классификация блоков
    // =========================================================================

    /** Природный грунт, который становится тропой. */
    private static boolean isPathable(BlockState s) {
        return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.COARSE_DIRT)
            || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.ROOTED_DIRT);
    }

    /**
     * Препятствие на трассе, которое вырубается при генерации: листва,
     * растения, снежный слой, грибы-гиганты, кактусы и т.п.; брёвна — только
     * вне структур (в деревнях из брёвен стены домов).
     */
    private static boolean isObstacle(BlockState s, boolean clearLogs) {
        if (s.is(BlockTags.LEAVES)) {
            return true;
        }
        if (s.is(BlockTags.LOGS)) {
            return clearLogs;
        }
        if (s.canBeReplaced()) {
            return true; // трава, цветы, снег-слой, лианы
        }
        return s.is(BlockTags.FLOWERS) || s.is(BlockTags.SAPLINGS)
            || s.is(Blocks.CACTUS) || s.is(Blocks.BAMBOO) || s.is(Blocks.SUGAR_CANE)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.COCOA) || s.is(Blocks.BEE_NEST)
            || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK)
            || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON)
            || s.is(Blocks.AZALEA) || s.is(Blocks.FLOWERING_AZALEA) || s.is(Blocks.MOSS_CARPET);
    }
}
