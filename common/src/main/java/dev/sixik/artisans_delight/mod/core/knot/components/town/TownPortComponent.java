package dev.sixik.artisans_delight.mod.core.knot.components.town;

import dev.sixik.artisans_delight.libs.world.codecs.ExternFieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import net.minecraft.core.BlockPos;

/**
 * Компонент порта прибрежного поселения.
 * Связывает город с морем через точную позицию причала (pierPos) у кромки воды.
 */
public class TownPortComponent extends SyncedWorldObjectComponent {

    public final TrackedField<BlockPos> pierPos = tracked("pierPos", ExternFieldCodecs.blockPos(), BlockPos.ZERO);
    public final TrackedField<Boolean> isPort = tracked("isPort", FieldCodecs.bool(), true);
    public final TrackedField<Integer> dockLevel = tracked("dockLevel", FieldCodecs.intValue(), 1);
    public final TrackedField<Integer> shipCapacity = tracked("shipCapacity", FieldCodecs.intValue(), 2);

    public TownPortComponent() {
    }

    public TownPortComponent(BlockPos pierPos) {
        this.pierPos.set(pierPos);
        this.isPort.set(true);
        this.dockLevel.set(1);
        this.shipCapacity.set(2);
    }
}
