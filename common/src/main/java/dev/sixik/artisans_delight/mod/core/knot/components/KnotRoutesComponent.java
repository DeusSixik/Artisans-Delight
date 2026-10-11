package dev.sixik.artisans_delight.mod.core.knot.components;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.Optional;
import java.util.UUID;

/**
 * Компонент путей и дорог узла.
 * Хранит реальные геометрические пути (наземные дороги и морские фарватеры) к соседним узлам.
 */
public class KnotRoutesComponent extends SyncedWorldObjectComponent {

    public final TrackedField<ObjectList<KnotRouteEntry>> routes = tracked(
            "routes",
            FieldCodecs.listOf(KnotRouteEntry.CODEC),
            ObjectLists.emptyList()
    );

    public void addOrUpdateRoute(KnotRouteEntry route) {
        routes.update(list -> {
            ObjectList<KnotRouteEntry> next = new ObjectArrayList<>(list);
            next.removeIf(r -> r.targetKnotId().equals(route.targetKnotId()));
            next.add(route);
            return next;
        });
    }

    public void removeRoute(UUID targetKnotId) {
        routes.update(list -> {
            ObjectList<KnotRouteEntry> next = new ObjectArrayList<>(list);
            next.removeIf(r -> r.targetKnotId().equals(targetKnotId));
            return next;
        });
    }

    public Optional<KnotRouteEntry> getRouteTo(UUID targetKnotId) {
        for (KnotRouteEntry entry : routes.get()) {
            if (entry.targetKnotId().equals(targetKnotId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }
}
