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

    public final TrackedField<ObjectList<KnotRouteEntry>> baselineRoutes = tracked(
            "baseline_routes",
            FieldCodecs.listOf(KnotRouteEntry.CODEC),
            ObjectLists.emptyList()
    );

    public void addOrUpdateRoute(KnotRouteEntry route) {
        if (getBaselineRouteTo(route.targetKnotId()).isEmpty()) {
            setBaselineRoute(route);
        }
        routes.update(list -> {
            ObjectList<KnotRouteEntry> next = new ObjectArrayList<>(list);
            next.removeIf(r -> r.targetKnotId().equals(route.targetKnotId()));
            next.add(route);
            return next;
        });
    }

    public void setBaselineRoute(KnotRouteEntry route) {
        baselineRoutes.update(list -> {
            ObjectList<KnotRouteEntry> next = new ObjectArrayList<>(list);
            next.removeIf(r -> r.targetKnotId().equals(route.targetKnotId()));
            next.add(route);
            return next;
        });
    }

    public Optional<KnotRouteEntry> getBaselineRouteTo(UUID targetKnotId) {
        for (KnotRouteEntry entry : baselineRoutes.get()) {
            if (entry.targetKnotId().equals(targetKnotId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    public boolean restoreBaselineRoute(UUID targetKnotId) {
        Optional<KnotRouteEntry> base = getBaselineRouteTo(targetKnotId);
        if (base.isPresent()) {
            routes.update(list -> {
                ObjectList<KnotRouteEntry> next = new ObjectArrayList<>(list);
                next.removeIf(r -> r.targetKnotId().equals(targetKnotId));
                next.add(base.get());
                return next;
            });
            return true;
        }
        return false;
    }

    public void removeRoute(UUID targetKnotId) {
        routes.update(list -> {
            ObjectList<KnotRouteEntry> next = new ObjectArrayList<>(list);
            next.removeIf(r -> r.targetKnotId().equals(targetKnotId));
            return next;
        });
        baselineRoutes.update(list -> {
            ObjectList<KnotRouteEntry> next = new ObjectArrayList<>(list);
            next.removeIf(r -> r.targetKnotId().equals(targetKnotId));
            return next;
        });
    }

    /**
     * Полный сброс маршрутов (обычных и базовых) с корректным поднятием ревизии поля.
     * Прямой вызов {@code routes.get().clear()} запрещен: он мутирует живой список
     * мимо {@link TrackedField#update}, и сетевые дельты не видят изменения.
     */
    public void clearAllRoutes() {
        routes.update(list -> ObjectLists.emptyList());
        baselineRoutes.update(list -> ObjectLists.emptyList());
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
