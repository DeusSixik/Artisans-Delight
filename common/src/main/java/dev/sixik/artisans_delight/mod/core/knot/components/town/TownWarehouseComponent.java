package dev.sixik.artisans_delight.mod.core.knot.components.town;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;

public class TownWarehouseComponent extends SyncedWorldObjectComponent {
    public final TrackedField<Integer> baseSlots = tracked("baseSlots", FieldCodecs.intValue(), 16);
    public final TrackedField<Integer> expandedSlots = tracked("expandedSlots", FieldCodecs.intValue(), 0);
    public final TrackedField<Integer> usedSlots = tracked("usedSlots", FieldCodecs.intValue(), 0);

    public int totalCapacity() {
        return baseSlots.get() + expandedSlots.get();
    }

    public boolean isFull() {
        return usedSlots.get() >= totalCapacity();
    }
}
