package dev.sixik.artisans_delight.mod.core.knot.components;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.Optional;
import java.util.UUID;

public class HierarchyComponent extends SyncedWorldObjectComponent {
    public static final UUID NO_PARENT = UUID.fromString("00000000-0000-0000-0000-000000000000");
    /**
     * Ссылка на родительский WorldObject (город или узел)
     */
    public final TrackedField<UUID> parentId = tracked("parentId", FieldCodecs.uuid(), NO_PARENT);
    /**
     * Дочерние WorldObject
     */
    public final TrackedField<ObjectList<UUID>> childrenIds = tracked(
            "childrenIds",
            FieldCodecs.listOf(FieldCodecs.uuid()),
            ObjectLists.emptyList()
    );

    public boolean hasParent() {
        return !parentId.get().equals(NO_PARENT);
    }

    public Optional<WorldObject> resolveParent(WorldObjectManager manager) {
        return hasParent() ? manager.get(parentId.get()) : Optional.empty();
    }

    public void setParent(UUID parent) {
        parentId.set(parent != null ? parent : NO_PARENT);
    }

    public void addChild(UUID childId) {
        childrenIds.update(list -> {
            ObjectList<UUID> next = new ObjectArrayList<>(list);
            if (!next.contains(childId)) next.add(childId);
            return next;
        });
    }

    public void removeChild(UUID childId) {
        childrenIds.update(list -> {
            ObjectList<UUID> next = new ObjectArrayList<>(list);
            next.remove(childId);
            return next;
        });
    }
}
