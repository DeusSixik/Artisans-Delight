package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;

import java.util.*;

/**
 * Система автоматического объединения сходящихся дорог (Route Bundling & Junction System).
 * Если в поселение или через узкий коридор ведут две или более дороги, проходящие рядом друг с другом
 * (например, через один и тот же перешеек, мост или долину), система объединяет их в единую магистраль.
 * На месте расхождения направлений формируется естественная развилка (Fork / Junction).
 */
public final class RouteBundler {

    /**
     * Максимальная ширина общего коридора (в блоках), при которой дороги считаются параллельными
     * и объединяются в одну магистраль.
     */
    public static final double MAX_CORRIDOR_WIDTH = 48.0;

    /**
     * Минимальная длина общего участка пути (в блоках), чтобы сформировать развилку.
     */
    public static final double MIN_SHARED_DISTANCE = 28.0;

    private RouteBundler() {
    }

    public static final class ProjectionResult {
        public final double dist;
        public final double projX;
        public final double projZ;
        public final double t;

        public ProjectionResult(double dist, double projX, double projZ, double t) {
            this.dist = dist;
            this.projX = projX;
            this.projZ = projZ;
            this.t = t;
        }
    }

    public static ProjectionResult projectPointOnSegment(
            double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lenSq = dx * dx + dz * dz;

        if (lenSq < 1e-4) {
            double d = Math.hypot(px - x1, pz - z1);
            return new ProjectionResult(d, x1, z1, 0.0);
        }

        double u = ((px - x1) * dx + (pz - z1) * dz) / lenSq;
        double clampedU = Math.max(0.0, Math.min(1.0, u));
        double projX = x1 + clampedU * dx;
        double projZ = z1 + clampedU * dz;
        double dist = Math.hypot(px - projX, pz - projZ);

        return new ProjectionResult(dist, projX, projZ, clampedU);
    }

    public static final class PolylineProjection {
        public final double dist;
        public final int segmentIndex;
        public final int projX;
        public final int projZ;
        public final double t;
        public final boolean isBridge;

        public PolylineProjection(double dist, int segmentIndex, int projX, int projZ, double t, boolean isBridge) {
            this.dist = dist;
            this.segmentIndex = segmentIndex;
            this.projX = projX;
            this.projZ = projZ;
            this.t = t;
            this.isBridge = isBridge;
        }
    }

    public static PolylineProjection projectOnPolyline(RoadPoint2D pt, ObjectList<RoadPoint2D> polyline) {
        double minDist = Double.MAX_VALUE;
        int bestSeg = 0;
        int bestX = pt.x();
        int bestZ = pt.z();
        double bestT = 0.0;
        boolean bestBridge = pt.isBridge();

        for (int i = 0; i < polyline.size() - 1; i++) {
            RoadPoint2D p1 = polyline.get(i);
            RoadPoint2D p2 = polyline.get(i + 1);
            ProjectionResult res = projectPointOnSegment(pt.x(), pt.z(), p1.x(), p1.z(), p2.x(), p2.z());
            if (res.dist < minDist) {
                minDist = res.dist;
                bestSeg = i;
                bestX = (int) Math.round(res.projX);
                bestZ = (int) Math.round(res.projZ);
                bestT = res.t;
                bestBridge = p1.isBridge() || p2.isBridge();
            }
        }

        return new PolylineProjection(minDist, bestSeg, bestX, bestZ, bestT, bestBridge);
    }

    public static final class BundleResult {
        public final ObjectList<RoadPoint2D> bundledPri;
        public final ObjectList<RoadPoint2D> bundledSec;
        public final double sharedDistance;

        public BundleResult(ObjectList<RoadPoint2D> bundledPri, ObjectList<RoadPoint2D> bundledSec, double sharedDistance) {
            this.bundledPri = bundledPri;
            this.bundledSec = bundledSec;
            this.sharedDistance = sharedDistance;
        }
    }

    /**
     * Попытка объединить второстепенный маршрут secondary с основным маршрутом primary,
     * если они выходят из одного узла и идут вдоль одного коридора.
     * Возвращает BundleResult с новыми точками для обеих дорог и развилкой (Junction), либо null.
     */
    public static BundleResult tryBundleRoutes(
            ObjectList<RoadPoint2D> primary, ObjectList<RoadPoint2D> secondary, double maxCorridorWidth) {

        if (primary == null || primary.size() < 2 || secondary == null || secondary.size() < 3) {
            return null;
        }

        // Проверяем совпадение начальной точки (узел отправления)
        RoadPoint2D startPri = primary.getFirst();
        RoadPoint2D startSec = secondary.getFirst();
        if (startPri.distSqr(startSec) > 16.0 * 16.0) {
            return null; // Разные начальные узлы
        }

        int lastSharedSecIdx = -1;
        int lastProjSeg = 0;
        double lastProjT = 0.0;
        int lastProjX = startPri.x();
        int lastProjZ = startPri.z();
        boolean lastProjBridge = startPri.isBridge();

        // Сканируем точки secondary по порядку от начала
        for (int s = 1; s < secondary.size() - 1; s++) {
            RoadPoint2D pt = secondary.get(s);
            PolylineProjection proj = projectOnPolyline(pt, primary);

            double continuousPos = proj.segmentIndex + proj.t;
            double lastPos = lastProjSeg + lastProjT;

            // Если точка находится в пределах коридора и проекция движется вперед вдоль магистрали
            if (proj.dist <= maxCorridorWidth && continuousPos >= lastPos - 0.25) {
                lastSharedSecIdx = s;
                lastProjSeg = proj.segmentIndex;
                lastProjT = proj.t;
                lastProjX = proj.projX;
                lastProjZ = proj.projZ;
                lastProjBridge = proj.isBridge;
            } else {
                // Дороги разошлись в разные стороны
                break;
            }
        }

        if (lastSharedSecIdx < 1) {
            return null;
        }

        // Вычисляем длину общего участка вдоль магистрали primary до точки развилки
        double sharedDist = 0.0;
        for (int i = 0; i < lastProjSeg; i++) {
            sharedDist += primary.get(i).distance(primary.get(i + 1));
        }
        sharedDist += primary.get(lastProjSeg).distance(lastProjX, lastProjZ);

        if (sharedDist < MIN_SHARED_DISTANCE) {
            return null;
        }

        RoadPoint2D forkPoint = new RoadPoint2D(lastProjX, lastProjZ, lastProjBridge);

        // 1. Формируем обновленный primary с гарантированной точкой развилки
        ObjectList<RoadPoint2D> bundledPri = new ObjectArrayList<>();
        for (int i = 0; i <= lastProjSeg; i++) {
            bundledPri.add(primary.get(i));
        }
        boolean forkAddedPri = false;
        if (bundledPri.getLast().distSqr(forkPoint) > 16.0) {
            if (lastProjSeg + 1 < primary.size() && primary.get(lastProjSeg + 1).distSqr(forkPoint) > 16.0) {
                bundledPri.add(forkPoint);
                forkAddedPri = true;
            }
        }
        for (int i = lastProjSeg + 1; i < primary.size(); i++) {
            bundledPri.add(primary.get(i));
        }

        // 2. Формируем обновленный secondary: общий ствол до развилки + собственное ответвление
        ObjectList<RoadPoint2D> bundledSec = new ObjectArrayList<>();
        for (int i = 0; i <= lastProjSeg; i++) {
            bundledSec.add(primary.get(i));
        }
        if (forkAddedPri || bundledSec.getLast().distSqr(forkPoint) > 16.0) {
            bundledSec.add(forkPoint);
        }

        for (int s = lastSharedSecIdx + 1; s < secondary.size(); s++) {
            RoadPoint2D remPt = secondary.get(s);
            if (bundledSec.getLast().distSqr(remPt) > 16.0) {
                bundledSec.add(remPt);
            }
        }
        if (bundledSec.getLast().distSqr(secondary.getLast()) > 9.0) {
            bundledSec.add(secondary.getLast());
        }

        return new BundleResult(bundledPri, bundledSec, sharedDist);
    }

    public static double calculateDistance(ObjectList<RoadPoint2D> pts) {
        if (pts == null || pts.size() < 2) return 0.0;
        double dist = 0.0;
        for (int i = 0; i < pts.size() - 1; i++) {
            dist += pts.get(i).distance(pts.get(i + 1));
        }
        return dist;
    }

    private static final class ActiveRoute {
        KnotRouteEntry entry;
        ObjectList<RoadPoint2D> ptsFromKnot;
        boolean modified;

        ActiveRoute(KnotRouteEntry entry, ObjectList<RoadPoint2D> ptsFromKnot) {
            this.entry = entry;
            this.ptsFromKnot = ptsFromKnot;
            this.modified = false;
        }
    }

    /**
     * Объединение всех сходящихся дорог для одного узла.
     */
    public static void bundleKnotRoutes(WorldObject knot, Map<UUID, WorldObject> knotMap) {
        if (knot == null) return;
        KnotRoutesComponent routesComp = knot.getSingle(KnotRoutesComponent.class).orElse(null);
        KnotMetaComponent meta = knot.getSingle(KnotMetaComponent.class).orElse(null);
        if (routesComp == null || meta == null) return;

        BlockPos knotPos = meta.position.get();
        List<KnotRouteEntry> originalRoutes = new ArrayList<>(routesComp.routes.get());
        if (originalRoutes.size() < 2) return;

        // Отбираем только сухопутные маршруты достаточной длины
        List<ActiveRoute> activeRoutes = new ArrayList<>();
        for (KnotRouteEntry r : originalRoutes) {
            if (!r.isSeaRoute() && r.waypoints().size() >= 3) {
                activeRoutes.add(new ActiveRoute(r, orientFromKnot(r.waypoints(), knotPos)));
            }
        }
        if (activeRoutes.size() < 2) return;

        // Сортируем маршруты по убыванию длины (самые длинные — магистральные стволы)
        activeRoutes.sort((a, b) -> Double.compare(b.entry.distance(), a.entry.distance()));

        // Для каждого более короткого маршрута ищем наиболее подходящий магистральный ствол в том же направлении
        for (int i = 1; i < activeRoutes.size(); i++) {
            ActiveRoute secRoute = activeRoutes.get(i);

            int bestTrunkIdx = -1;
            BundleResult bestResult = null;
            double maxSharedDist = 0.0;

            for (int j = 0; j < i; j++) {
                ActiveRoute candTrunk = activeRoutes.get(j);
                BundleResult res = tryBundleRoutes(candTrunk.ptsFromKnot, secRoute.ptsFromKnot, MAX_CORRIDOR_WIDTH);
                if (res != null && res.sharedDistance > maxSharedDist) {
                    maxSharedDist = res.sharedDistance;
                    bestResult = res;
                    bestTrunkIdx = j;
                }
            }

            if (bestResult != null && maxSharedDist >= MIN_SHARED_DISTANCE) {
                ActiveRoute trunk = activeRoutes.get(bestTrunkIdx);
                trunk.ptsFromKnot = bestResult.bundledPri;
                trunk.modified = true;

                secRoute.ptsFromKnot = bestResult.bundledSec;
                secRoute.modified = true;
            }
        }

        // Сохраняем обновленные объединенные маршруты и синхронизируем обратные маршруты
        for (ActiveRoute ar : activeRoutes) {
            if (!ar.modified) continue;

            double newDist = calculateDistance(ar.ptsFromKnot);
            KnotRouteEntry updatedEntry = new KnotRouteEntry(ar.entry.targetKnotId(), ar.ptsFromKnot, false, newDist);
            routesComp.addOrUpdateRoute(updatedEntry);

            // Синхронно обновляем обратный маршрут в целевом узле
            UUID targetId = ar.entry.targetKnotId();
            WorldObject targetObj = knotMap != null ? knotMap.get(targetId) : null;
            if (targetObj == null) {
                targetObj = WorldObjectManagerApi.findObject(targetId).orElse(null);
            }

            if (targetObj != null) {
                targetObj.getSingle(KnotRoutesComponent.class).ifPresent(targetRoutes -> {
                    ObjectList<RoadPoint2D> rev = new ObjectArrayList<>(ar.ptsFromKnot);
                    Collections.reverse(rev);
                    targetRoutes.addOrUpdateRoute(new KnotRouteEntry(knot.id(), rev, false, newDist));
                });
            }
        }
    }

    /**
     * Полное объединение всех сходящихся дорог по всей сети узлов мира.
     */
    public static void bundleAllRoutes(Collection<WorldObject> knots) {
        if (knots == null || knots.size() < 2) return;

        Map<UUID, WorldObject> knotMap = new HashMap<>();
        for (WorldObject obj : knots) {
            knotMap.put(obj.id(), obj);
        }

        for (WorldObject knot : knots) {
            bundleKnotRoutes(knot, knotMap);
        }
    }

    private static ObjectList<RoadPoint2D> orientFromKnot(ObjectList<RoadPoint2D> pts, BlockPos knotPos) {
        if (pts.isEmpty()) return pts;
        double dFirst = pts.getFirst().distSqr(knotPos.getX(), knotPos.getZ());
        double dLast = pts.getLast().distSqr(knotPos.getX(), knotPos.getZ());
        if (dFirst <= dLast) {
            return new ObjectArrayList<>(pts);
        } else {
            ObjectList<RoadPoint2D> rev = new ObjectArrayList<>(pts);
            Collections.reverse(rev);
            return rev;
        }
    }
}
