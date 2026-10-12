package dev.sixik.artisans_delight.mod.core.knot.components;

import dev.sixik.artisans_delight.libs.world.codecs.ExternFieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

public class KnotMetaComponent extends SyncedWorldObjectComponent {

    public static final String EMPTY_NAME = "-NO_NAME-";
    public static final ResourceLocation ICON_EMPTY = ResourceLocation.tryBuild("minecraft", "air");
    public static final UUID EMPTY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public final TrackedField<String> name = tracked("name", FieldCodecs.string(), EMPTY_NAME);
    public final TrackedField<ResourceLocation> icon = tracked("icon", ExternFieldCodecs.resourceLocation(), ICON_EMPTY);
    public final TrackedField<KnotType> type = tracked("type", FieldCodecs.enumCodec(KnotType.class), KnotType.NONE);
    public final TrackedField<BlockPos> position = tracked("pos", ExternFieldCodecs.blockPos(), BlockPos.ZERO);
    public final TrackedField<UUID> uuid = tracked("uuid", FieldCodecs.uuid(), EMPTY_UUID);
    public final TrackedField<Integer> tier = tracked("tier", FieldCodecs.intValue(), 1);

    public KnotMetaComponent() {
    }

    public KnotMetaComponent(String name, ResourceLocation icon, KnotType type, BlockPos pos) {
        this(name, icon, type, pos, UUID.randomUUID(), 1);
    }

    public KnotMetaComponent(String name, ResourceLocation icon, KnotType type, BlockPos pos, UUID uuid, int tier) {
        this.name.set(name);
        this.icon.set(icon);
        this.type.set(type);
        this.position.set(pos);
        this.uuid.set(uuid);
        this.tier.set(tier);
    }
    
    public KnotMetaComponent(KnotType type, BlockPos pos) {
        this.type.set(type);
        this.position.set(pos);
        this.uuid.set(UUID.randomUUID());
    }
}
