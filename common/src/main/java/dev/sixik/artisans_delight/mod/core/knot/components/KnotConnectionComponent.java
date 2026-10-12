package dev.sixik.artisans_delight.mod.core.knot.components;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.UUID;

public class KnotConnectionComponent extends SyncedWorldObjectComponent {

    public final TrackedField<Integer> investmentCost = tracked("investmentCost", FieldCodecs.intValue(), 1);

    public final TrackedField<Boolean> isInvested = tracked("isInvested", FieldCodecs.bool(), false);

    public final TrackedField<ObjectList<UUID>> neighbors = tracked(
            "neighbors",
            FieldCodecs.listOf(FieldCodecs.uuid()),
            ObjectLists.emptyList()
    );

    public void addNeighbor(UUID neighborKnotId) {
        neighbors.update(list -> {
            ObjectList<UUID> next = new ObjectArrayList<>(list);
            if (!next.contains(neighborKnotId)) {
                next.add(neighborKnotId);
            }
            return next;
        });
    }

    public void removeNeighbor(UUID neighborKnotId) {
        neighbors.update(list -> {
            ObjectList<UUID> next = new ObjectArrayList<>(list);
            next.remove(neighborKnotId);
            return next;
        });
    }

    /**
     * Полный сброс списка соседей с корректным поднятием ревизии поля.
     * Прямой вызов {@code neighbors.get().clear()} запрещен: он мутирует живой
     * список мимо {@link TrackedField#update}, и сетевые дельты не видят изменения.
     */
    public void clearNeighbors() {
        neighbors.update(list -> ObjectLists.emptyList());
    }
}
