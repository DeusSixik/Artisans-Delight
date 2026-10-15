package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadCalcCache;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadCommitStore;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadEdge;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadPavingLedger;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNode;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сохранение кэша расчётов дорог и закреплённых рёбер на диск.
 *
 * <p>Файл на измерение: {@code <мир>/data/artisans_delight/roads_<ns>_<path>.dat}
 * (сжатый NBT). Свой файл, а не {@code SavedData}: запись идёт из фоновых
 * потоков стримера атомарной заменой (tmp → move), без нагрузки на серверный
 * поток и без зависимости от загрузчика.
 *
 * <p>Устойчивость между сессиями держится на детерминированных id: деревня —
 * (сид, чанк структуры), перекрёсток — (сид, ячейка поля), поэтому ключи рёбер
 * в файле совпадают с ключами новой сессии.
 *
 * <p>Что хранится:
 * <ul>
 *   <li>поле узлов (сырые кандидаты ячеек) — быстрый старт;</li>
 *   <li>проверки воды на хордах;</li>
 *   <li>результаты A* (дорога или «дороги нет»);</li>
 *   <li>закреплённые рёбра — с геометрией и концами.</li>
 * </ul>
 */
public final class RoadPersistence {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadPersistence.class);
    private static final int FORMAT_VERSION = 1;
    private static final Object IO_LOCK = new Object();

    private RoadPersistence() {
    }

    /**
     * Включает область кэша для мира и, если она сменилась, загружает файл.
     * Вызывать перед любым расчётом сети.
     *
     * @param level     серверный мир
     * @param worldSeed сид мира
     */
    public static void ensureLoaded(ServerLevel level, long worldSeed) {
        String dim = level.dimension().location().toString();
        synchronized (IO_LOCK) {
            if (!RoadCalcCache.ensureScope(worldSeed, dim)) {
                return; // уже загружено для этой области
            }
            Path file = file(level);
            if (file == null || !Files.isRegularFile(file)) {
                LOGGER.info("[RoadPersistence] Нет сохранённых дорог для {} — старт с пустого кэша", dim);
                return;
            }
            try {
                CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                if (root.getInt("version") != FORMAT_VERSION || root.getLong("seed") != worldSeed
                    || !dim.equals(root.getString("dim"))) {
                    LOGGER.warn("[RoadPersistence] Файл {} от другой версии/сида/измерения — игнорирую", file);
                    return;
                }
                Map<Long, int[]> field = readField(root);
                Map<String, Boolean> sea = readSea(root);
                Map<String, RoadCalcCache.GeoEntry> geometry = readGeometry(root);
                List<RoadCommitStore.Committed> committed = readCommitted(root);
                RoadCalcCache.restore(RoadCalcCache.scopeKey(worldSeed, dim), field, sea, geometry, committed);
                RoadPavingLedger.restore(readLedger(root));
                RoadObstacleStore.restore(RoadCalcCache.scopeKey(worldSeed, dim), readObstacles(root));
                LOGGER.info("[RoadPersistence] Загружено для {}: {}", dim, RoadCalcCache.stats());
            } catch (Exception e) {
                LOGGER.error("[RoadPersistence] Не удалось прочитать {} — старт с пустого кэша", file, e);
            }
        }
    }

    /**
     * Сохраняет кэш, если с прошлого раза были изменения.
     *
     * @return {@code true}, если файл записан
     */
    public static boolean saveIfDirty(ServerLevel level, long worldSeed) {
        String dim = level.dimension().location().toString();
        synchronized (IO_LOCK) {
            if (!RoadCalcCache.scopeKey(worldSeed, dim).equals(RoadCalcCache.scope())) {
                return false; // кэш сейчас принадлежит другой области
            }
            if (!RoadCalcCache.takeDirty()) {
                return false;
            }
            Path file = file(level);
            if (file == null) {
                RoadCalcCache.markDirty();
                return false;
            }
            try {
                CompoundTag root = new CompoundTag();
                root.putInt("version", FORMAT_VERSION);
                root.putLong("seed", worldSeed);
                root.putString("dim", dim);
                writeField(root);
                writeSea(root);
                writeGeometry(root);
                writeCommitted(root);
                writeLedger(root);
                writeObstacles(root, RoadCalcCache.scopeKey(worldSeed, dim));

                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                NbtIo.writeCompressed(root, tmp);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                LOGGER.debug("[RoadPersistence] Сохранено {}: {}", file.getFileName(), RoadCalcCache.stats());
                return true;
            } catch (Exception e) {
                RoadCalcCache.markDirty(); // повторим в следующий раз
                LOGGER.error("[RoadPersistence] Не удалось записать {}", file, e);
                return false;
            }
        }
    }

    private static Path file(ServerLevel level) {
        MinecraftServer server = level.getServer();
        if (server == null) {
            return null;
        }
        var loc = level.dimension().location();
        String name = "roads_" + loc.getNamespace() + "_" + loc.getPath().replace('/', '_') + ".dat";
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("artisans_delight").resolve(name);
    }

    // =========================================================================
    // Поле: LongArray ключей + IntArray по 6 чисел на ячейку
    // =========================================================================

    private static void writeField(CompoundTag root) {
        List<Map.Entry<Long, int[]>> entries = new ArrayList<>(RoadCalcCache.fieldView().entrySet());
        long[] keys = new long[entries.size()];
        int[] vals = new int[entries.size() * 6];
        for (int i = 0; i < entries.size(); i++) {
            keys[i] = entries.get(i).getKey();
            System.arraycopy(entries.get(i).getValue(), 0, vals, i * 6, 6);
        }
        root.putLongArray("fieldKeys", keys);
        root.putIntArray("fieldVals", vals);
    }

    private static Map<Long, int[]> readField(CompoundTag root) {
        long[] keys = root.getLongArray("fieldKeys");
        int[] vals = root.getIntArray("fieldVals");
        Map<Long, int[]> out = new HashMap<>(keys.length * 2);
        if (vals.length != keys.length * 6) {
            return out;
        }
        for (int i = 0; i < keys.length; i++) {
            int[] v = new int[6];
            System.arraycopy(vals, i * 6, v, 0, 6);
            out.put(keys[i], v);
        }
        return out;
    }

    // =========================================================================
    // Вода на хордах
    // =========================================================================

    private static void writeSea(CompoundTag root) {
        CompoundTag sea = new CompoundTag();
        for (Map.Entry<String, Boolean> e : RoadCalcCache.seaView().entrySet()) {
            sea.putBoolean(e.getKey(), e.getValue());
        }
        root.put("sea", sea);
    }

    private static Map<String, Boolean> readSea(CompoundTag root) {
        CompoundTag sea = root.getCompound("sea");
        Map<String, Boolean> out = new HashMap<>(sea.size() * 2);
        for (String k : sea.getAllKeys()) {
            out.put(k, sea.getBoolean(k));
        }
        return out;
    }

    // =========================================================================
    // Геометрия A*
    // =========================================================================

    private static void writeGeometry(CompoundTag root) {
        ListTag list = new ListTag();
        for (Map.Entry<String, RoadCalcCache.GeoEntry> e : RoadCalcCache.geometryView().entrySet()) {
            RoadCalcCache.GeoEntry g = e.getValue();
            CompoundTag t = new CompoundTag();
            t.putString("k", e.getKey());
            t.putUUID("f", g.fromId());
            t.putIntArray("p", new int[]{g.ax(), g.az(), g.bx(), g.bz()});
            if (g.edge() != null) {
                t.put("e", writeEdge(g.edge()));
            }
            list.add(t);
        }
        root.put("geometry", list);
    }

    private static Map<String, RoadCalcCache.GeoEntry> readGeometry(CompoundTag root) {
        ListTag list = root.getList("geometry", Tag.TAG_COMPOUND);
        Map<String, RoadCalcCache.GeoEntry> out = new HashMap<>(list.size() * 2);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int[] p = t.getIntArray("p");
            if (p.length != 4 || !t.hasUUID("f")) {
                continue;
            }
            RoadEdge edge = t.contains("e") ? readEdge(t.getCompound("e")) : null;
            out.put(t.getString("k"), new RoadCalcCache.GeoEntry(t.getUUID("f"), p[0], p[1], p[2], p[3], edge));
        }
        return out;
    }

    // =========================================================================
    // Закреплённые рёбра
    // =========================================================================

    private static void writeCommitted(CompoundTag root) {
        ListTag list = new ListTag();
        for (RoadCommitStore.Committed c : RoadCommitStore.all()) {
            CompoundTag t = new CompoundTag();
            t.put("a", writeNode(c.a()));
            t.put("b", writeNode(c.b()));
            t.put("e", writeEdge(c.edge()));
            list.add(t);
        }
        root.put("committed", list);
    }

    private static List<RoadCommitStore.Committed> readCommitted(CompoundTag root) {
        ListTag list = root.getList("committed", Tag.TAG_COMPOUND);
        List<RoadCommitStore.Committed> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            RoadNode a = readNode(t.getCompound("a"));
            RoadNode b = readNode(t.getCompound("b"));
            RoadEdge e = readEdge(t.getCompound("e"));
            if (a != null && b != null && e != null) {
                out.add(new RoadCommitStore.Committed(a, b, e));
            }
        }
        return out;
    }

    // =========================================================================
    // Препятствия
    // =========================================================================

    private static void writeObstacles(CompoundTag root, String scope) {
        ListTag list = new ListTag();
        for (RoadObstacle o : RoadObstacleStore.get(scope)) {
            CompoundTag t = new CompoundTag();
            t.putString("id", o.id().toString());
            t.putIntArray("c", new int[]{o.center().getX(), o.center().getY(), o.center().getZ()});
            t.putDouble("r", o.radius());
            t.putString("s", o.severity().name());
            list.add(t);
        }
        root.put("obstacles", list);
    }

    private static List<RoadObstacle> readObstacles(CompoundTag root) {
        ListTag list = root.getList("obstacles", Tag.TAG_COMPOUND);
        List<RoadObstacle> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            net.minecraft.resources.ResourceLocation id =
                net.minecraft.resources.ResourceLocation.tryParse(t.getString("id"));
            int[] c = t.getIntArray("c");
            if (id == null || c.length != 3) {
                continue;
            }
            RoadObstacle.ObstacleSeverity sev;
            try {
                sev = RoadObstacle.ObstacleSeverity.valueOf(t.getString("s"));
            } catch (IllegalArgumentException e) {
                sev = RoadObstacle.ObstacleSeverity.BLOCKING;
            }
            out.add(new RoadObstacle(id, new net.minecraft.core.BlockPos(c[0], c[1], c[2]), t.getDouble("r"), sev));
        }
        return out;
    }

    // =========================================================================
    // Учёт укладки: чанк → рёбра
    // =========================================================================

    private static void writeLedger(CompoundTag root) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, java.util.Set<String>> e : RoadPavingLedger.snapshot().entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("c", e.getKey());
            ListTag keys = new ListTag();
            for (String k : e.getValue()) {
                keys.add(net.minecraft.nbt.StringTag.valueOf(k));
            }
            t.put("e", keys);
            list.add(t);
        }
        root.put("paved", list);
    }

    private static Map<Long, java.util.Set<String>> readLedger(CompoundTag root) {
        ListTag list = root.getList("paved", Tag.TAG_COMPOUND);
        Map<Long, java.util.Set<String>> out = new HashMap<>(list.size() * 2);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            ListTag keys = t.getList("e", Tag.TAG_STRING);
            java.util.Set<String> set = new java.util.HashSet<>(keys.size() * 2);
            for (int j = 0; j < keys.size(); j++) {
                set.add(keys.getString(j));
            }
            out.put(t.getLong("c"), set);
        }
        return out;
    }

    // =========================================================================
    // Узел / ребро
    // =========================================================================

    private static CompoundTag writeNode(RoadNode n) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", n.id());
        t.putBoolean("town", n.isTown());
        t.putString("name", n.name());
        t.putIntArray("pos", new int[]{n.x(), n.y(), n.z()});
        // sourceId (объект города в менеджере) не сохраняем: менеджер живёт
        // только в сессии, при загрузке город подтянется по своему id.
        return t;
    }

    private static RoadNode readNode(CompoundTag t) {
        int[] pos = t.getIntArray("pos");
        if (!t.hasUUID("id") || pos.length != 3) {
            return null;
        }
        RoadNode.Kind kind = t.getBoolean("town") ? RoadNode.Kind.TOWN : RoadNode.Kind.WAYPOINT;
        return new RoadNode(t.getUUID("id"), kind, t.getString("name"), pos[0], pos[1], pos[2]);
    }

    private static CompoundTag writeEdge(RoadEdge e) {
        CompoundTag t = new CompoundTag();
        t.putUUID("f", e.fromId());
        t.putUUID("t", e.toId());
        List<RoadPoint2D> g = e.geometry();
        int[] xs = new int[g.size()];
        int[] zs = new int[g.size()];
        byte[] br = new byte[g.size()];
        for (int i = 0; i < g.size(); i++) {
            xs[i] = g.get(i).x();
            zs[i] = g.get(i).z();
            br[i] = (byte) (g.get(i).isBridge() ? 1 : 0);
        }
        t.putIntArray("x", xs);
        t.putIntArray("z", zs);
        t.putByteArray("b", br);
        t.putDouble("d", e.distance());
        t.putBoolean("sea", e.seaRoute());
        return t;
    }

    private static RoadEdge readEdge(CompoundTag t) {
        if (!t.hasUUID("f") || !t.hasUUID("t")) {
            return null;
        }
        int[] xs = t.getIntArray("x");
        int[] zs = t.getIntArray("z");
        byte[] br = t.getByteArray("b");
        if (xs.length < 2 || xs.length != zs.length || br.length != xs.length) {
            return null;
        }
        List<RoadPoint2D> g = new ArrayList<>(xs.length);
        for (int i = 0; i < xs.length; i++) {
            g.add(new RoadPoint2D(xs[i], zs[i], br[i] != 0));
        }
        return new RoadEdge(t.getUUID("f"), t.getUUID("t"), g, t.getBoolean("sea"), t.getDouble("d"));
    }

    /** Есть ли несохранённые закрепления (их пишем сразу, а не раз в N секунд). */
    static boolean hasUnsavedCommits() {
        return RoadCommitStore.isDirty();
    }
}
