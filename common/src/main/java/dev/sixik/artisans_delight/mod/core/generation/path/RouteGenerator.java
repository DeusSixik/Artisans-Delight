package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.mod.core.generation.terrain.TerrainSampler;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownPortComponent;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Генератор связей и маршрутов между узлами мира.
 * Поддерживает как синхронный расчет, так и фоновую асинхронную генерацию без блокировки игрового потока и мира.
 */
public final class RouteGenerator {

    private static final ExecutorService ROUTE_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)),
            new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "ArtisansDelight-RoadGen-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            }
    );

    private static final Set<String> PENDING_ROUTES = ConcurrentHashMap.newKeySet();

    private RouteGenerator() {
    }

    /**
     * Асинхронно построить соединение и маршрут между двумя узлами без блокировки вызывающего потока.
     */
    public static CompletableFuture<Void> connectKnotsAsync(
            WorldObject knotA, WorldObject knotB,
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState) {

        if (knotA == null || knotB == null) {
            return CompletableFuture.completedFuture(null);
        }

        // Если маршрут уже существует, ничего не пересчитываем
        boolean hasRoute = knotA.getSingle(KnotRoutesComponent.class)
                .flatMap(r -> r.getRouteTo(knotB.id()))
                .isPresent();
        if (hasRoute) {
            return CompletableFuture.completedFuture(null);
        }

        UUID idA = knotA.id();
        UUID idB = knotB.id();
        String edgeKey = idA.compareTo(idB) < 0 ? idA + "_" + idB : idB + "_" + idA;

        if (!PENDING_ROUTES.add(edgeKey)) {
            return CompletableFuture.completedFuture(null); // Уже считается в фоне
        }

        return CompletableFuture.runAsync(() -> {
            try {
                connectKnots(knotA, knotB, generator, heightAccessor, randomState);
            } catch (Throwable ignored) {
            } finally {
                PENDING_ROUTES.remove(edgeKey);
            }
        }, ROUTE_EXECUTOR);
    }

    /**
     * Построить соединение и маршрут между двумя узлами (синхронно).
     */
    public static void connectKnots(
            WorldObject knotA, WorldObject knotB,
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState) {

        KnotMetaComponent metaA = knotA.getSingle(KnotMetaComponent.class).orElse(null);
        KnotMetaComponent metaB = knotB.getSingle(KnotMetaComponent.class).orElse(null);
        if (metaA == null || metaB == null) return;

        BlockPos posA = metaA.pos.get();
        BlockPos posB = metaB.pos.get();

        boolean isSeaRoute = false;
        ObjectList<RoadPoint2D> path = null;

        // 1. Приоритет: Сухопутная дорога (строго по суше, огибает реки, озера, заливы и океаны)
        ObjectList<RoadPoint2D> landPath = CoarsePathfinder.findLandRoad(
                generator, heightAccessor, randomState,
                posA.getX(), posA.getZ(), posB.getX(), posB.getZ());

        if (!landPath.isEmpty()) {
            isSeaRoute = false;
            path = landPath;
        } else {
            // 2. Если по суше дойти невозможно (островное поселение), проверяем наличие портов
            Optional<TownPortComponent> portA = knotA.getSingle(TownPortComponent.class);
            if (portA.isEmpty()) {
                TerrainSampler.findCoastlinePier(generator, heightAccessor, randomState, posA, 180)
                        .ifPresent(pier -> {
                            TownPortComponent p = new TownPortComponent(pier);
                            knotA.attach(p);
                        });
                portA = knotA.getSingle(TownPortComponent.class);
            }

            Optional<TownPortComponent> portB = knotB.getSingle(TownPortComponent.class);
            if (portB.isEmpty()) {
                TerrainSampler.findCoastlinePier(generator, heightAccessor, randomState, posB, 180)
                        .ifPresent(pier -> {
                            TownPortComponent p = new TownPortComponent(pier);
                            knotB.attach(p);
                        });
                portB = knotB.getSingle(TownPortComponent.class);
            }

            if (portA.isPresent() && portB.isPresent()) {
                BlockPos pierA = portA.get().pierPos.get();
                BlockPos pierB = portB.get().pierPos.get();
                ObjectList<RoadPoint2D> seaPath = CoarsePathfinder.findSeaRoute(
                        generator, heightAccessor, randomState,
                        pierA.getX(), pierA.getZ(), pierB.getX(), pierB.getZ());

                if (!seaPath.isEmpty()) {
                    isSeaRoute = true;
                    path = seaPath;
                }
            }
        }

        // Если путь так и не удалось построить (нет проходимости), не создаем ложную связь
        if (path == null || path.isEmpty()) {
            return;
        }

        double totalDist = 0.0;
        for (int i = 0; i < path.size() - 1; i++) {
            totalDist += path.get(i).distance(path.get(i + 1));
        }

        // Гарантируем наличие KnotConnectionComponent на обоих объектах
        KnotConnectionComponent connA = knotA.getSingle(KnotConnectionComponent.class).orElseGet(() -> {
            KnotConnectionComponent c = new KnotConnectionComponent();
            knotA.attach(c);
            return c;
        });
        connA.addNeighbor(knotB.id());

        KnotConnectionComponent connB = knotB.getSingle(KnotConnectionComponent.class).orElseGet(() -> {
            KnotConnectionComponent c = new KnotConnectionComponent();
            knotB.attach(c);
            return c;
        });
        connB.addNeighbor(knotA.id());

        // Гарантируем наличие KnotRoutesComponent на обоих объектах
        final boolean sea = isSeaRoute;
        final double dist = totalDist;
        final ObjectList<RoadPoint2D> finalPath = path;

        KnotRoutesComponent routesA = knotA.getSingle(KnotRoutesComponent.class).orElseGet(() -> {
            KnotRoutesComponent r = new KnotRoutesComponent();
            knotA.attach(r);
            return r;
        });
        routesA.addOrUpdateRoute(new KnotRouteEntry(knotB.id(), finalPath, sea, dist));

        KnotRoutesComponent routesB = knotB.getSingle(KnotRoutesComponent.class).orElseGet(() -> {
            KnotRoutesComponent r = new KnotRoutesComponent();
            knotB.attach(r);
            return r;
        });
        ObjectList<RoadPoint2D> reversed = new ObjectArrayList<>(finalPath);
        Collections.reverse(reversed);
        routesB.addOrUpdateRoute(new KnotRouteEntry(knotA.id(), reversed, sea, dist));
    }
}
