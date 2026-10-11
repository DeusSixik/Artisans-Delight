package dev.sixik.artisans_delight.mod.core.knot.data;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodec;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.Objects;
import java.util.UUID;

/**
 * Запись маршрута дороги или морского пути между двумя узлами.
 * Хранит ломаную линию точек (только X, Z).
 */
public record KnotRouteEntry(
        UUID targetKnotId,
        ObjectList<RoadPoint2D> waypoints,
        boolean isSeaRoute,
        double distance
) {

    public KnotRouteEntry {
        waypoints = waypoints != null ? waypoints : ObjectLists.emptyList();
    }

    public static final FieldCodec<KnotRouteEntry> CODEC = FieldCodecs.compoundOf(
            "artisansdelight:knot_route_entry",
            (nbt, entry) -> {
                nbt.putUUID("target", entry.targetKnotId());
                nbt.putBoolean("sea", entry.isSeaRoute());
                nbt.putDouble("dist", entry.distance());

                long[] packed = new long[entry.waypoints().size()];
                for (int i = 0; i < packed.length; i++) {
                    RoadPoint2D pt = entry.waypoints().get(i);
                    packed[i] = RoadPoint2D.pack(pt.x(), pt.z());
                }
                nbt.putLongArray("points", packed);
            },
            (nbt, defaultValue) -> {
                UUID target = nbt.hasUUID("target") ? nbt.getUUID("target") : UUID.randomUUID();
                boolean sea = nbt.getBoolean("sea");
                double dist = nbt.getDouble("dist");
                long[] packed = nbt.getLongArray("points");
                ObjectList<RoadPoint2D> pts = new ObjectArrayList<>(packed.length);
                for (long l : packed) {
                    pts.add(RoadPoint2D.unpack(l));
                }
                return new KnotRouteEntry(target, pts, sea, dist);
            },
            entry -> entry,
            Objects::equals
    );
}
