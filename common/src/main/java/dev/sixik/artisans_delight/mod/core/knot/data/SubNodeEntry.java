package dev.sixik.artisans_delight.mod.core.knot.data;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodec;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.UUID;

public record SubNodeEntry(
        String id,
        String name,
        ResourceLocation icon,
        int cpCost,
        boolean invested,
        UUID assignedWorker,
        ResourceLocation lootTable,
        long cycleStartTick,
        int cycleDurationTicks
) {
    public static final UUID NO_WORKER = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public boolean hasWorker() {
        return assignedWorker != null && !assignedWorker.equals(NO_WORKER);
    }

    /**
     * Кодек для сериализации SubNodeEntry в NBT и сеть через FieldCodecs.compoundOf
     */
    public static final FieldCodec<SubNodeEntry> CODEC = FieldCodecs.compoundOf(
            "artisansdelight:sub_node_entry",
            (nbt, entry) -> {
                nbt.putString("id", entry.id());
                nbt.putString("name", entry.name());
                nbt.putString("icon", entry.icon().toString());
                nbt.putInt("cpCost", entry.cpCost());
                nbt.putBoolean("invested", entry.invested());
                nbt.putUUID("worker", entry.assignedWorker());
                nbt.putString("lootTable", entry.lootTable().toString());
                nbt.putLong("cycleStart", entry.cycleStartTick());
                nbt.putInt("cycleDuration", entry.cycleDurationTicks());
            },
            (nbt, defaultValue) -> new SubNodeEntry(
                    nbt.getString("id"),
                    nbt.getString("name"),
                    ResourceLocation.tryParse(nbt.getString("icon")),
                    nbt.getInt("cpCost"),
                    nbt.getBoolean("invested"),
                    nbt.hasUUID("worker") ? nbt.getUUID("worker") : NO_WORKER,
                    ResourceLocation.tryParse(nbt.getString("lootTable")),
                    nbt.getLong("cycleStart"),
                    nbt.getInt("cycleDuration")
            ),
            entry -> entry, // record immutable
            Objects::equals
    );
}
