package dev.sixik.artisans_delight.mod.core.knot.components.town;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodec;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public class TownWorkshopsComponent extends SyncedWorldObjectComponent {
    public record WorkshopEntry(
            String id,
            String workshopType, // "blacksmith", "carpenter", "refinery"
            int tier,
            UUID assignedWorker,
            ResourceLocation activeRecipe,
            long craftStartTick,
            int craftDurationTicks
    ) {
        public static final FieldCodec<WorkshopEntry> CODEC = FieldCodecs.compoundOf(
                "artisansdelight:workshop_entry",
                (nbt, entry) -> {
                    nbt.putString("id", entry.id());
                    nbt.putString("type", entry.workshopType());
                    nbt.putInt("tier", entry.tier());
                    nbt.putUUID("worker", entry.assignedWorker());
                    nbt.putString("recipe", entry.activeRecipe().toString());
                    nbt.putLong("startTick", entry.craftStartTick());
                    nbt.putInt("duration", entry.craftDurationTicks());
                },
                (nbt, def) -> new WorkshopEntry(
                        nbt.getString("id"),
                        nbt.getString("type"),
                        nbt.getInt("tier"),
                        nbt.hasUUID("worker") ? nbt.getUUID("worker") : UUID.fromString("00000000-0000-0000-0000-000000000000"),
                        ResourceLocation.tryParse(nbt.getString("recipe")),
                        nbt.getLong("startTick"),
                        nbt.getInt("duration")
                ),
                entry -> entry,
                Objects::equals
        );
    }

    public final TrackedField<ObjectList<WorkshopEntry>> workshops = tracked(
            "workshops",
            FieldCodecs.listOf(WorkshopEntry.CODEC),
            ObjectLists.emptyList()
    );
}