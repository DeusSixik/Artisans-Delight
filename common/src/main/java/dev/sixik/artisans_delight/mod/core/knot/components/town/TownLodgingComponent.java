package dev.sixik.artisans_delight.mod.core.knot.components.town;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.UUID;

public class TownLodgingComponent extends SyncedWorldObjectComponent {
    public final TrackedField<Integer> baseCapacity = tracked("baseCapacity", FieldCodecs.intValue(), 1);
    public final TrackedField<Integer> expandedCapacity = tracked("expandedCapacity", FieldCodecs.intValue(), 0);
    public final TrackedField<ObjectList<UUID>> hiredWorkers = tracked(
            "hiredWorkers",
            FieldCodecs.listOf(FieldCodecs.uuid()),
            ObjectLists.emptyList()
    );

    public int maxWorkers() {
        return baseCapacity.get() + expandedCapacity.get();
    }

    public boolean hasFreeHousing() {
        return hiredWorkers.get().size() < maxWorkers();
    }

    public boolean addWorker(UUID workerId) {
        if (!hasFreeHousing()) return false;
        hiredWorkers.update(list -> {
            ObjectList<UUID> next = new ObjectArrayList<>(list);
            next.add(workerId);
            return next;
        });
        return true;
    }
}
