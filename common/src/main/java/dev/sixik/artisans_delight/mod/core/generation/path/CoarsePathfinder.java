package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.mod.core.generation.terrain.TerrainSampler;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Collections;
import java.util.PriorityQueue;

/**
 * Высокопроизводительный A* поиск пути по крупной сетке (Coarse Grid) с учетом физического рельефа мира.
 * Все расчеты производятся без загрузки чанков и кэшируются на уровне запроса.
 * Поддерживает автоматическую генерацию мостов через реки и огибание океанов по суше.
 */
public final class CoarsePathfinder {

    private static final int MAX_SEARCH_NODES = 10000;

    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};

    private CoarsePathfinder() {
    }

    /**
     * Быстрый локальный кэш высот и биомов для исключения повторных вызовов генератора шума.
     * Корректно учитывает 3D-биомы на высоте поверхности и уровне моря.
     */
    public static final class FastTerrainCache {
        private final ChunkGenerator generator;
        private final LevelHeightAccessor heightAccessor;
        private final RandomState randomState;
        private final Long2LongOpenHashMap cache = new Long2LongOpenHashMap();

        public FastTerrainCache(ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState) {
            this.generator = generator;
            this.heightAccessor = heightAccessor;
            this.randomState = randomState;
        }

        public long getSample(int x, int z) {
            long packed = RoadPoint2D.pack(x, z);
            long cached = cache.get(packed);
            if (cached != 0L) {
                return cached;
            }

            int surfY = TerrainSampler.getSurfaceHeight(generator, heightAccessor, randomState, x, z);
            int floorY = TerrainSampler.getOceanFloorHeight(generator, heightAccessor, randomState, x, z);
            boolean isWater = surfY > floorY;
            boolean isRiver = false;
            boolean isOcean = false;

            // 3D Биомы: проверяем вертикальную колонку высот (surfY, 63, 62, 64, floorY)
            int[] testYs = {surfY, TerrainSampler.SEA_LEVEL, TerrainSampler.SEA_LEVEL - 1, TerrainSampler.SEA_LEVEL + 1, floorY};
            for (int ty : testYs) {
                var b = TerrainSampler.getNoiseBiome(generator, randomState, x, ty, z);
                if (!isRiver && TerrainSampler.isRiverBiome(b)) {
                    isRiver = true;
                    isWater = true;
                }
                if (!isOcean && TerrainSampler.isOceanBiome(b)) {
                    isOcean = true;
                    isWater = true;
                }
            }

            int depth;
            if (surfY > floorY) {
                depth = surfY - floorY;
            } else if (isRiver) {
                depth = 3;
            } else if (isOcean) {
                depth = Math.max(3, TerrainSampler.SEA_LEVEL - floorY);
            } else {
                depth = 0;
            }

            // Битовая упаковка:
            // bit 0: isWater, bit 1: isRiver, bit 2: isOcean, bits 3..18: depth, bits 19..34: surfY + 2048, bits 35..50: floorY + 2048, bit 62: valid
            long encoded = (isWater ? 1L : 0L)
                    | (isRiver ? 2L : 0L)
                    | (isOcean ? 4L : 0L)
                    | ((long) (depth & 0xFFFF) << 3)
                    | ((long) ((surfY + 2048) & 0xFFFF) << 19)
                    | ((long) ((floorY + 2048) & 0xFFFF) << 35)
                    | (1L << 62);

            cache.put(packed, encoded);
            return encoded;
        }

        public boolean isWater(int x, int z) {
            return (getSample(x, z) & 1L) != 0L;
        }

        public boolean isRiver(int x, int z) {
            return (getSample(x, z) & 2L) != 0L;
        }

        public boolean isOcean(int x, int z) {
            return (getSample(x, z) & 4L) != 0L;
        }

        public int getDepth(int x, int z) {
            return (int) ((getSample(x, z) >> 3) & 0xFFFF);
        }

        public int getSurfaceY(int x, int z) {
            return (int) (((getSample(x, z) >> 19) & 0xFFFF) - 2048);
        }
    }

    private static class Node implements Comparable<Node> {
        final int x;
        final int z;
        final long packed;
        final double gCost;
        final double fCost;
        final Node parent;

        Node(int x, int z, double gCost, double fCost, Node parent) {
            this.x = x;
            this.z = z;
            this.packed = RoadPoint2D.pack(x, z);
            this.gCost = gCost;
            this.fCost = fCost;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.fCost, o.fCost);
        }
    }

    /**
     * Поиск сухопутной дороги между двумя точками с возможностью постройки мостов через реки.
     */
    @FunctionalInterface
    public interface PathConstraint {
        PathConstraint NONE = (x, z) -> 0.0;

        /**
         * Дополнительный штраф за проход через координаты (x, z).
         * Если возвращает Double.POSITIVE_INFINITY или >= 99999.0 — проход строго запрещен.
         */
        double getExtraCost(int x, int z);
    }

    public static ObjectList<RoadPoint2D> findLandRoad(
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState,
            int startX, int startZ, int goalX, int goalZ) {
        return findLandRoad(generator, heightAccessor, randomState, startX, startZ, goalX, goalZ, PathConstraint.NONE);
    }

    public static ObjectList<RoadPoint2D> findLandRoad(
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState,
            int startX, int startZ, int goalX, int goalZ, PathConstraint constraint) {

        FastTerrainCache cache = new FastTerrainCache(generator, heightAccessor, randomState);

        BlockPos landStart = TerrainSampler.findNearestLand(generator, heightAccessor, randomState, startX, startZ, 64);
        BlockPos landGoal = TerrainSampler.findNearestLand(generator, heightAccessor, randomState, goalX, goalZ, 64);

        double totalDist = Math.hypot(landStart.getX() - landGoal.getX(), landStart.getZ() - landGoal.getZ());

        int primaryStep;
        int fallbackStep;

        if (totalDist > 1600.0) {
            primaryStep = 32;
            fallbackStep = 48;
        } else if (totalDist > 600.0) {
            primaryStep = 24;
            fallbackStep = 36;
        } else {
            primaryStep = 16;
            fallbackStep = 24;
        }

        ObjectList<RoadPoint2D> path = findPath(cache, landStart.getX(), landStart.getZ(), landGoal.getX(), landGoal.getZ(), false, primaryStep, constraint);
        if (!path.isEmpty()) {
            return ensureEndpoints(path, startX, startZ, goalX, goalZ);
        }

        path = findPath(cache, landStart.getX(), landStart.getZ(), landGoal.getX(), landGoal.getZ(), false, fallbackStep, constraint);
        if (!path.isEmpty()) {
            return ensureEndpoints(path, startX, startZ, goalX, goalZ);
        }

        return ObjectLists.emptyList();
    }

    /**
     * Поиск морского фарватера по открытой воде между причалами.
     */
    public static ObjectList<RoadPoint2D> findSeaRoute(
            ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState,
            int pierStartX, int pierStartZ, int pierGoalX, int pierGoalZ) {

        FastTerrainCache cache = new FastTerrainCache(generator, heightAccessor, randomState);

        BlockPos waterStart = TerrainSampler.findNearestWater(generator, heightAccessor, randomState, pierStartX, pierStartZ, 32);
        BlockPos waterGoal = TerrainSampler.findNearestWater(generator, heightAccessor, randomState, pierGoalX, pierGoalZ, 32);

        ObjectList<RoadPoint2D> path = findPath(cache, waterStart.getX(), waterStart.getZ(), waterGoal.getX(), waterGoal.getZ(), true, 28);
        if (!path.isEmpty()) {
            return ensureEndpoints(path, pierStartX, pierStartZ, pierGoalX, pierGoalZ);
        }

        path = findPath(cache, waterStart.getX(), waterStart.getZ(), waterGoal.getX(), waterGoal.getZ(), true, 44);
        if (!path.isEmpty()) {
            return ensureEndpoints(path, pierStartX, pierStartZ, pierGoalX, pierGoalZ);
        }

        return ObjectLists.emptyList();
    }

    public static ObjectList<RoadPoint2D> findPath(
            FastTerrainCache cache,
            int startX, int startZ, int goalX, int goalZ, boolean isSea, int step) {
        return findPath(cache, startX, startZ, goalX, goalZ, isSea, step, PathConstraint.NONE);
    }

    public static ObjectList<RoadPoint2D> findPath(
            FastTerrainCache cache,
            int startX, int startZ, int goalX, int goalZ, boolean isSea, int step, PathConstraint constraint) {

        int gridStartX = Math.round((float) startX / step) * step;
        int gridStartZ = Math.round((float) startZ / step) * step;
        int gridGoalX = Math.round((float) goalX / step) * step;
        int gridGoalZ = Math.round((float) goalZ / step) * step;

        PriorityQueue<Node> openSet = new PriorityQueue<>();
        Long2DoubleOpenHashMap gScores = new Long2DoubleOpenHashMap();
        Long2ObjectOpenHashMap<Node> closedSet = new Long2ObjectOpenHashMap<>();

        // Weighted A* (w = 1.15) для сбалансированного и быстрого поиска пути вокруг водных препятствий
        double weight = 1.15;
        double startH = Math.hypot(gridStartX - gridGoalX, gridStartZ - gridGoalZ);
        Node startNode = new Node(gridStartX, gridStartZ, 0.0, weight * startH, null);
        openSet.add(startNode);
        gScores.put(startNode.packed, 0.0);

        Node bestNode = startNode;
        double bestDistToGoal = startH;
        boolean reached = false;
        int iterations = 0;

        while (!openSet.isEmpty() && iterations++ < MAX_SEARCH_NODES) {
            Node current = openSet.poll();

            double distToGoal = Math.hypot(current.x - gridGoalX, current.z - gridGoalZ);
            if (distToGoal < bestDistToGoal) {
                bestDistToGoal = distToGoal;
                bestNode = current;
            }

            // Достигли целевой окрестности
            if (distToGoal <= step * 1.45) {
                bestNode = current;
                reached = true;
                break;
            }

            closedSet.put(current.packed, current);

            int currentY = isSea
                    ? TerrainSampler.SEA_LEVEL
                    : cache.getSurfaceY(current.x, current.z);

            for (int i = 0; i < 8; i++) {
                int nx = current.x + DX[i] * step;
                int nz = current.z + DZ[i] * step;
                long nPacked = RoadPoint2D.pack(nx, nz);

                if (closedSet.containsKey(nPacked)) {
                    continue;
                }

                double stepDist = (i % 2 == 0) ? step : step * 1.41421356;
                double moveMultiplier = evaluateCostMultiplier(cache, current.x, current.z, currentY, nx, nz, stepDist, isSea);

                if (Double.isInfinite(moveMultiplier) || moveMultiplier >= 999.0) {
                    continue; // Непроходимо
                }

                double extraCost = constraint.getExtraCost(nx, nz);
                if (Double.isInfinite(extraCost) || extraCost >= 99999.0) {
                    continue; // Запретная зона препятствия
                }

                // Дополнительный разовый штраф за вход на мост (с суши в воду):
                // заставляет алгоритм предпочитать огибать реку по суше, а не строить мосты туда-обратно
                double bridgeCost = 0.0;
                if (!isSea && !cache.isWater(current.x, current.z) && cache.isWater(nx, nz)) {
                    bridgeCost = 250.0;
                }

                double tentativeG = current.gCost + stepDist * moveMultiplier + bridgeCost + extraCost;
                double recordedG = gScores.getOrDefault(nPacked, Double.MAX_VALUE);

                if (tentativeG < recordedG) {
                    gScores.put(nPacked, tentativeG);
                    double h = Math.hypot(nx - gridGoalX, nz - gridGoalZ);
                    Node neighbor = new Node(nx, nz, tentativeG, tentativeG + weight * h, current);
                    openSet.add(neighbor);
                }
            }
        }

        // Если цель не была достигнута (например, изолированный остров), не создаем ложный путь
        if (!reached && bestDistToGoal > step * 1.8) {
            return ObjectLists.emptyList();
        }

        // Восстановление пути с расстановкой флагов моста
        ObjectList<RoadPoint2D> rawPath = new ObjectArrayList<>();
        Node curr = bestNode;
        while (curr != null) {
            boolean bridge = !isSea && cache.isWater(curr.x, curr.z);
            rawPath.add(new RoadPoint2D(curr.x, curr.z, bridge));
            curr = curr.parent;
        }
        Collections.reverse(rawPath);

        ObjectList<RoadPoint2D> result = new ObjectArrayList<>();
        result.add(new RoadPoint2D(startX, startZ, !isSea && cache.isWater(startX, startZ)));

        for (RoadPoint2D pt : rawPath) {
            if (pt.distSqr(startX, startZ) > (step * step) && pt.distSqr(goalX, goalZ) > (step * step)) {
                result.add(pt);
            }
        }

        result.add(new RoadPoint2D(goalX, goalZ, !isSea && cache.isWater(goalX, goalZ)));

        // Сглаживание пути с гарантией, что новые хорды не пересекают глубокий океан/сушу
        return smoothPath(cache, result, step * 0.8, isSea);
    }

    private static double evaluateCostMultiplier(
            FastTerrainCache cache,
            int fromX, int fromZ, int fromY, int toX, int toZ, double stepDist, boolean isSea) {

        boolean toWater = cache.isWater(toX, toZ);
        int toDepth = toWater ? cache.getDepth(toX, toZ) : 0;
        boolean toOcean = cache.isOcean(toX, toZ);

        if (isSea) {
            // === МОРСКОЙ ПУТЬ ===
            // Суша строго непроходима для судов
            if (!toWater || toDepth == 0) {
                return Double.POSITIVE_INFINITY;
            }
            if (toDepth < 2) {
                return 8.0; // Мелководье у берега
            }
            return 1.0; // Открытый океан
        } else {
            // === СУХОПУТНАЯ ДОРОГА И МОСТЫ ===
            if (toWater || toDepth > 0) {
                // Если это открытый океан или глубина >= 14 — мост строить нельзя, строго непроходимо!
                if (toOcean || toDepth >= 14) {
                    return Double.POSITIVE_INFINITY;
                }

                // Пересечение реки (мост):
                // Высокий штраф (40.0), чтобы A* ВСЕГДА предпочитал огибать реку по суше,
                // если есть возможность обойти излучину реки.
                // Мост строится только если обход по суше невозможен или превышает ~1200-1500 блоков!
                return 40.0;
            }

            // Перепад высот (уклон рельефа)
            int toSurf = cache.getSurfaceY(toX, toZ);
            int dy = Math.abs(toSurf - fromY);
            double slope = (double) dy / stepDist;

            if (slope > 1.25 || dy > 28) {
                return Double.POSITIVE_INFINITY; // Отвесная скала / обрыв
            } else if (slope > 0.45) {
                return 1.0 + 3.0 + 8.0 * (slope - 0.45); // Крутой подъем (огибаем, если есть пологий путь)
            } else if (slope > 0.15) {
                return 1.0 + 2.0 * slope; // Пологий холм
            }

            return 1.0;
        }
    }

    private static ObjectList<RoadPoint2D> ensureEndpoints(ObjectList<RoadPoint2D> path, int sX, int sZ, int gX, int gZ) {
        if (path.isEmpty()) return path;
        RoadPoint2D first = path.getFirst();
        if (first.x() != sX || first.z() != sZ) {
            path.add(0, new RoadPoint2D(sX, sZ, first.isBridge()));
        }
        RoadPoint2D last = path.getLast();
        if (last.x() != gX || last.z() != gZ) {
            path.add(new RoadPoint2D(gX, gZ, last.isBridge()));
        }
        return path;
    }

    /**
     * Проверка, пересекает ли прямой отрезок глубокий океан.
     */
    public static boolean doesSegmentIntersectOcean(FastTerrainCache cache, RoadPoint2D p1, RoadPoint2D p2) {
        double dist = p1.distance(p2);
        int checks = Math.max(2, (int) Math.ceil(dist / 8.0));
        for (int i = 1; i < checks; i++) {
            double t = (double) i / checks;
            int x = (int) Math.round(p1.x() + (p2.x() - p1.x()) * t);
            int z = (int) Math.round(p1.z() + (p2.z() - p1.z()) * t);
            if (cache.isOcean(x, z) || cache.getDepth(x, z) >= 14) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверка, пересекает ли прямой отрезок воду (реку или океан).
     */
    public static boolean doesSegmentIntersectWater(FastTerrainCache cache, RoadPoint2D p1, RoadPoint2D p2) {
        double dist = p1.distance(p2);
        int checks = Math.max(2, (int) Math.ceil(dist / 4.0));
        for (int i = 0; i <= checks; i++) {
            double t = (double) i / checks;
            int x = (int) Math.round(p1.x() + (p2.x() - p1.x()) * t);
            int z = (int) Math.round(p1.z() + (p2.z() - p1.z()) * t);
            if (cache.isWater(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверка, пересекает ли морской отрезок сушу.
     */
    public static boolean doesSegmentIntersectLand(FastTerrainCache cache, RoadPoint2D p1, RoadPoint2D p2) {
        double dist = p1.distance(p2);
        int checks = Math.max(2, (int) Math.ceil(dist / 10.0));
        for (int i = 1; i < checks; i++) {
            double t = (double) i / checks;
            int x = (int) Math.round(p1.x() + (p2.x() - p1.x()) * t);
            int z = (int) Math.round(p1.z() + (p2.z() - p1.z()) * t);
            if (!cache.isWater(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Точная расстановка и разбиение точек мостовых переходов через реки.
     * Сканирует каждый отрезок пути. Если обнаружено пересечение воды,
     * вычисляет точные точки входа и выхода на берег и помечает все точки над водой как isBridge = true.
     */
    public static ObjectList<RoadPoint2D> refineBridgeWaypoints(
            FastTerrainCache cache, ObjectList<RoadPoint2D> points, boolean isSea) {
        if (isSea || points.size() < 2) {
            return points;
        }

        ObjectList<RoadPoint2D> refined = new ObjectArrayList<>();
        refined.add(points.getFirst());

        for (int i = 0; i < points.size() - 1; i++) {
            RoadPoint2D p1 = points.get(i);
            RoadPoint2D p2 = points.get(i + 1);

            double dist = p1.distance(p2);
            int samples = Math.max(2, (int) Math.ceil(dist / 4.0));

            int firstWaterIdx = -1;
            int lastWaterIdx = -1;

            int[] sampleX = new int[samples + 1];
            int[] sampleZ = new int[samples + 1];

            for (int s = 0; s <= samples; s++) {
                double t = (double) s / samples;
                int sx = (int) Math.round(p1.x() + (p2.x() - p1.x()) * t);
                int sz = (int) Math.round(p1.z() + (p2.z() - p1.z()) * t);
                sampleX[s] = sx;
                sampleZ[s] = sz;

                if (cache.isWater(sx, sz)) {
                    if (firstWaterIdx == -1) firstWaterIdx = s;
                    lastWaterIdx = s;
                }
            }

            if (firstWaterIdx == -1) {
                // Воды на отрезке нет — обычная дорога
                refined.add(p2);
            } else {
                // На отрезке обнаружен водоем (река) -> создаем мостовой переход
                int enterIdx = Math.max(0, firstWaterIdx - 1);
                RoadPoint2D enterShore = new RoadPoint2D(sampleX[enterIdx], sampleZ[enterIdx], true);
                if (refined.getLast().distSqr(enterShore) > 4.0) {
                    refined.add(enterShore);
                }

                for (int s = firstWaterIdx; s <= lastWaterIdx; s++) {
                    RoadPoint2D waterPt = new RoadPoint2D(sampleX[s], sampleZ[s], true);
                    if (refined.getLast().distSqr(waterPt) > 16.0) {
                        refined.add(waterPt);
                    }
                }

                int exitIdx = Math.min(samples, lastWaterIdx + 1);
                RoadPoint2D exitShore = new RoadPoint2D(sampleX[exitIdx], sampleZ[exitIdx], true);
                if (refined.getLast().distSqr(exitShore) > 4.0) {
                    refined.add(exitShore);
                }

                if (refined.getLast().distSqr(p2) > 4.0) {
                    refined.add(new RoadPoint2D(p2.x(), p2.z(), cache.isWater(p2.x(), p2.z())));
                }
            }
        }

        return refined;
    }

    /**
     * Сглаживание пути алгоритмом Рамера-Дугласа-Пекера с сохранением мостовых переходов и обходов океана.
     */
    public static ObjectList<RoadPoint2D> smoothPath(
            FastTerrainCache cache, ObjectList<RoadPoint2D> points, double epsilon, boolean isSea) {
        if (points.size() < 3) {
            return refineBridgeWaypoints(cache, points, isSea);
        }

        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;

        rdpSimplify(cache, points, 0, points.size() - 1, epsilon, keep, isSea);

        ObjectList<RoadPoint2D> smoothed = new ObjectArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                RoadPoint2D pt = points.get(i);
                boolean bridge = !isSea && cache.isWater(pt.x(), pt.z());
                smoothed.add(new RoadPoint2D(pt.x(), pt.z(), bridge));
            }
        }

        return refineBridgeWaypoints(cache, smoothed, isSea);
    }

    private static void rdpSimplify(
            FastTerrainCache cache, ObjectList<RoadPoint2D> points, int start, int end, double epsilon, boolean[] keep, boolean isSea) {
        if (end <= start + 1) {
            return;
        }

        // Если для сухопутной дороги хорда пересекает глубокий океан или реку — сохраняем изгиб берега и мостовой переход!
        if (!isSea) {
            boolean p1Water = cache.isWater(points.get(start).x(), points.get(start).z());
            boolean p2Water = cache.isWater(points.get(end).x(), points.get(end).z());
            if (p1Water != p2Water || doesSegmentIntersectWater(cache, points.get(start), points.get(end))) {
                int mid = (start + end) / 2;
                keep[mid] = true;
                rdpSimplify(cache, points, start, mid, epsilon, keep, isSea);
                rdpSimplify(cache, points, mid, end, epsilon, keep, isSea);
                return;
            }
        }

        // Если для морского пути хорда срезает мыс суши — сохраняем обход мыса!
        if (isSea && doesSegmentIntersectLand(cache, points.get(start), points.get(end))) {
            int mid = (start + end) / 2;
            keep[mid] = true;
            rdpSimplify(cache, points, start, mid, epsilon, keep, isSea);
            rdpSimplify(cache, points, mid, end, epsilon, keep, isSea);
            return;
        }

        double maxDist = 0.0;
        int maxIndex = -1;

        RoadPoint2D p1 = points.get(start);
        RoadPoint2D p2 = points.get(end);

        for (int i = start + 1; i < end; i++) {
            double dist = perpendicularDistance(points.get(i), p1, p2);
            if (dist > maxDist) {
                maxDist = dist;
                maxIndex = i;
            }
        }

        if (maxDist > epsilon) {
            keep[maxIndex] = true;
            rdpSimplify(cache, points, start, maxIndex, epsilon, keep, isSea);
            rdpSimplify(cache, points, maxIndex, end, epsilon, keep, isSea);
        }
    }

    private static double perpendicularDistance(RoadPoint2D p, RoadPoint2D lineStart, RoadPoint2D lineEnd) {
        double dx = lineEnd.x() - lineStart.x();
        double dz = lineEnd.z() - lineStart.z();
        double lineLenSq = dx * dx + dz * dz;

        if (lineLenSq == 0.0) {
            return Math.sqrt(p.distSqr(lineStart));
        }

        double u = ((p.x() - lineStart.x()) * dx + (p.z() - lineStart.z()) * dz) / lineLenSq;
        u = Math.max(0.0, Math.min(1.0, u));

        double projX = lineStart.x() + u * dx;
        double projZ = lineStart.z() + u * dz;

        return Math.hypot(p.x() - projX, p.z() - projZ);
    }
}
