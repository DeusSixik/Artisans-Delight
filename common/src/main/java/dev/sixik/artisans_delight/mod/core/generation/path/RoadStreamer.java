package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadCalcCache;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadEdge;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetwork;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetworkBuilder;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetworkStore;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Потоковый досчёт реальных дорог по мере приближения игроков.
 *
 * <p>{@link RoadNetworkBuilder} в режиме {@code STREAM} отдаёт каркас: узлы
 * и ожидающие рёбра (хорды-заглушки). Стример раз в {@link #TICK_MS} мс
 * снимает позиции игроков и отдаёт в пул A* ближайшие ожидающие рёбра
 * в радиусе {@link #STREAM_RADIUS}:
 * <ul>
 *   <li>дорога найдена — ребро становится реальным;</li>
 *   <li>сухой дороги нет (море, скалы, дикий объезд) — ребро удаляется;
 *       перекрёсток без единого ребра пропадает с карты.</li>
 * </ul>
 * Результат публикуется в {@link RoadNetworkStore} не чаще раза за тик.
 *
 * <p><b>Детерминизм:</b> геометрия ребра зависит только от его концов
 * и рельефа, поэтому маршрут игрока не влияет на итоговую сеть.
 *
 * <p><b>Потоки:</b> позиции игроков читаются на серверном потоке
 * ({@code server.execute}), A* — в собственном пуле, публикация —
 * в потоке планировщика. Стример сам останавливается, если сервер
 * остановлен или мир сменился.
 */
public final class RoadStreamer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadStreamer.class);

    /** Радиус (блоки) от игрока до хорды ребра, в котором ребро досчитывается. */
    public static final double STREAM_RADIUS = 1000.0;
    /** Период планировщика (мс). */
    private static final long TICK_MS = 1000L;
    /** Потоков A*. Оставляем ядро серверу. */
    private static final int THREADS = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
    /** Максимум рёбер в работе одновременно (очередь не раздувается). */
    private static final int MAX_IN_FLIGHT = THREADS * 2;

    private static final AtomicReference<RoadStreamer> ACTIVE = new AtomicReference<>();

    private final ServerLevel level;
    private final long worldSeed;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    /** Узлы каркаса (не меняются). */
    private final Map<UUID, RoadNode> nodes;
    /** Порядок рёбер каркаса — для детерминированной публикации. */
    private final List<String> edgeOrder;
    /** Текущее состояние рёбер: ожидающие и готовые; проваленные удалены. */
    private final ConcurrentHashMap<String, RoadEdge> edges = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicInteger readyCount = new AtomicInteger();
    private final AtomicInteger failedCount = new AtomicInteger();
    private volatile double[][] players = new double[0][];
    private volatile boolean stopped;

    private final ScheduledExecutorService scheduler;
    private final ExecutorService pool;

    private RoadStreamer(ServerLevel level, RoadNetwork base) {
        this.level = level;
        this.worldSeed = base.worldSeed();
        this.generator = level.getChunkSource().getGenerator();
        this.randomState = level.getChunkSource().randomState();
        this.nodes = new LinkedHashMap<>();
        for (RoadNode n : base.nodes()) {
            nodes.put(n.id(), n);
        }
        this.edgeOrder = new ArrayList<>(base.edgeCount());
        RoadCalcCache.ensureSeed(worldSeed);
        for (RoadEdge e : base.edges()) {
            edgeOrder.add(e.key());
            if (!e.pending()) {
                edges.put(e.key(), e);
                readyCount.incrementAndGet();
                continue;
            }
            // Пересборка сети не теряет уже посчитанные дороги: результат A*
            // берётся из кэша сессии, если концы ребра остались на месте.
            RoadNode a = nodes.get(e.fromId());
            RoadNode b = nodes.get(e.toId());
            RoadCalcCache.Hit hit = (a == null || b == null)
                ? RoadCalcCache.Hit.MISS
                : RoadCalcCache.lookupGeometry(e.key(), a, b);
            if (hit == RoadCalcCache.Hit.READY) {
                edges.put(e.key(), RoadCalcCache.cachedEdge(e.key()));
                readyCount.incrementAndGet();
            } else if (hit == RoadCalcCache.Hit.FAILED) {
                failedCount.incrementAndGet();
            } else {
                edges.put(e.key(), e);
            }
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("ArtisansDelight-RoadStreamTick"));
        this.pool = Executors.newFixedThreadPool(THREADS, daemon("ArtisansDelight-RoadStream"));
    }

    /**
     * Запускает стример для опубликованного каркаса; предыдущий останавливается.
     *
     * @param level серверный мир
     * @param base  опубликованный каркас (узлы + ожидающие рёбра)
     */
    public static void start(ServerLevel level, RoadNetwork base) {
        if (level == null || base == null) {
            return;
        }
        RoadStreamer s = new RoadStreamer(level, base);
        RoadStreamer old = ACTIVE.getAndSet(s);
        if (old != null) {
            old.stop();
        }
        // Публикуем сразу, уже с дорогами из кэша — без мигания «всё ожидает».
        s.publish();
        s.scheduler.scheduleWithFixedDelay(s::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        LOGGER.info("[RoadStreamer] Старт: {} узлов, {} рёбер, из кэша дорог {}, известных провалов {}, радиус {} блоков, потоков {} (кэш: {})",
            s.nodes.size(), s.edges.size(), s.readyCount.get(), s.failedCount.get(),
            (int) STREAM_RADIUS, THREADS, RoadCalcCache.stats());
    }

    /** Останавливает активный стример (перед пересборкой сети или при смене режима). */
    public static void stopActive() {
        RoadStreamer old = ACTIVE.getAndSet(null);
        if (old != null) {
            old.stop();
        }
    }

    /**
     * Короткая статистика активного стримера для карты/логов.
     *
     * @return строка вида «дорог 12, ждут 340, в работе 4, провал 3» или пустая
     */
    public static String stats() {
        RoadStreamer s = ACTIVE.get();
        if (s == null) {
            return "";
        }
        int pending = 0;
        for (RoadEdge e : s.edges.values()) {
            if (e.pending()) {
                pending++;
            }
        }
        return "дорог " + s.readyCount.get() + ", ждут " + pending
            + ", в работе " + s.inFlight.size() + ", провал " + s.failedCount.get();
    }

    private void stop() {
        stopped = true;
        scheduler.shutdownNow();
        // Без прерывания: начатые A* (≤ MAX_IN_FLIGHT) доработают и лягут в кэш.
        pool.shutdown();
    }

    // =========================================================================
    // Тик планировщика
    // =========================================================================

    private void tick() {
        if (stopped) {
            return;
        }
        try {
            MinecraftServer server = level.getServer();
            if (server == null || !server.isRunning() || server.getLevel(level.dimension()) != level) {
                LOGGER.info("[RoadStreamer] Мир выгружен — остановка");
                ACTIVE.compareAndSet(this, null);
                stop();
                return;
            }
            // Позиции игроков снимаются на серверном потоке; используем снимок
            // прошлого тика (задержка ≤ 1 с несущественна).
            server.execute(this::capturePlayers);

            if (dirty.getAndSet(false)) {
                publish();
            }
            schedule(players);
        } catch (Throwable t) {
            LOGGER.warn("[RoadStreamer] Ошибка тика: {}", t.toString());
        }
    }

    /** Серверный поток: снимок позиций игроков этого мира. */
    private void capturePlayers() {
        List<ServerPlayer> list = level.players();
        double[][] snap = new double[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            ServerPlayer p = list.get(i);
            snap[i] = new double[]{p.getX(), p.getZ()};
        }
        players = snap;
    }

    /** Отдаёт в пул ближайшие к игрокам ожидающие рёбра (по возрастанию расстояния). */
    private void schedule(double[][] ps) {
        if (ps.length == 0) {
            return;
        }
        int slots = MAX_IN_FLIGHT - inFlight.size();
        if (slots <= 0) {
            return;
        }
        record Cand(String key, RoadEdge edge, double dist) {
        }
        List<Cand> cands = new ArrayList<>();
        for (String key : edgeOrder) {
            RoadEdge e = edges.get(key);
            if (e == null || !e.pending() || inFlight.contains(key)) {
                continue;
            }
            RoadNode a = nodes.get(e.fromId());
            RoadNode b = nodes.get(e.toId());
            if (a == null || b == null) {
                continue;
            }
            double d = nearestPlayerDistance(ps, a, b);
            if (d <= STREAM_RADIUS) {
                cands.add(new Cand(key, e, d));
            }
        }
        if (cands.isEmpty()) {
            return;
        }
        cands.sort(Comparator.comparingDouble(Cand::dist).thenComparing(Cand::key));
        for (int i = 0; i < Math.min(slots, cands.size()); i++) {
            Cand c = cands.get(i);
            if (!inFlight.add(c.key())) {
                continue;
            }
            pool.submit(() -> resolve(c.key(), c.edge()));
        }
    }

    /** Поток пула: A* одного ребра; результат — готовое ребро или удаление. */
    private void resolve(String key, RoadEdge pending) {
        try {
            RoadNode a = nodes.get(pending.fromId());
            RoadNode b = nodes.get(pending.toId());
            RoadEdge real = (a == null || b == null) ? null
                : RoadNetworkBuilder.computeEdgeGeometry(a, b, generator, level, randomState);
            // В кэш — даже если стример уже остановлен пересборкой: результат
            // верен и подхватится новым стримером без повторного A*.
            if (a != null && b != null && !Thread.currentThread().isInterrupted()) {
                RoadCalcCache.putGeometry(key, a, b, real);
            }
            if (stopped) {
                return;
            }
            if (real != null) {
                edges.put(key, real);
                readyCount.incrementAndGet();
            } else {
                edges.remove(key);
                failedCount.incrementAndGet();
            }
            dirty.set(true);
        } catch (Throwable t) {
            if (!stopped) {
                LOGGER.warn("[RoadStreamer] Ребро {} не посчиталось: {}", key, t.toString());
                edges.remove(key);
                failedCount.incrementAndGet();
                dirty.set(true);
            }
        } finally {
            inFlight.remove(key);
        }
    }

    /**
     * Публикует снимок: рёбра в исходном порядке; перекрёсток без единого
     * ребра (все провалены) скрывается, города остаются всегда.
     */
    private void publish() {
        if (stopped) {
            return;
        }
        Map<String, RoadEdge> snapEdges = new LinkedHashMap<>();
        Map<UUID, Integer> degree = new HashMap<>();
        int pending = 0;
        for (String key : edgeOrder) {
            RoadEdge e = edges.get(key);
            if (e == null) {
                continue;
            }
            snapEdges.put(key, e);
            degree.merge(e.fromId(), 1, Integer::sum);
            degree.merge(e.toId(), 1, Integer::sum);
            if (e.pending()) {
                pending++;
            }
        }
        Map<UUID, RoadNode> snapNodes = new LinkedHashMap<>();
        for (RoadNode n : nodes.values()) {
            if (n.isTown() || degree.getOrDefault(n.id(), 0) > 0) {
                snapNodes.put(n.id(), n);
            }
        }
        RoadNetworkStore.publish(new RoadNetwork(worldSeed, snapNodes, snapEdges));
        LOGGER.info("[RoadStreamer] Опубликовано: дорог {}, ждут {}, в работе {}, провал {}, узлов {}",
            readyCount.get(), pending, inFlight.size(), failedCount.get(), snapNodes.size());
    }

    // =========================================================================
    // Геометрия
    // =========================================================================

    /**
     * Минимальное расстояние от игроков до отрезка-хорды {@code a–b}:
     * <pre>
     *   t = clamp(((p − a)·(b − a)) / |b − a|², 0, 1),   d = |p − (a + t·(b − a))|
     * </pre>
     */
    private static double nearestPlayerDistance(double[][] ps, RoadNode a, RoadNode b) {
        double ax = a.x();
        double az = a.z();
        double dx = b.x() - ax;
        double dz = b.z() - az;
        double len2 = dx * dx + dz * dz;
        double best = Double.MAX_VALUE;
        for (double[] p : ps) {
            double t = len2 < 1e-9 ? 0.0 : ((p[0] - ax) * dx + (p[1] - az) * dz) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double ex = ax + dx * t - p[0];
            double ez = az + dz * t - p[1];
            best = Math.min(best, Math.sqrt(ex * ex + ez * ez));
        }
        return best;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
    }
}
