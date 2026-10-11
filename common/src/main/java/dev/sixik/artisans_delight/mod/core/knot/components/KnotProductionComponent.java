package dev.sixik.artisans_delight.mod.core.knot.components;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import dev.sixik.artisans_delight.mod.core.knot.data.SubNodeEntry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.Optional;
import java.util.UUID;

public class KnotProductionComponent extends SyncedWorldObjectComponent {

    /**
     * Список подгрупп (под-узлов добычи) на этой территории
     */
    public final TrackedField<ObjectList<SubNodeEntry>> subNodes = tracked(
            "subNodes",
            FieldCodecs.listOf(SubNodeEntry.CODEC),
            ObjectLists.emptyList()
    );

    /**
     * Регистрация новой подгруппы добычи в узле
     */
    public void registerSubNode(SubNodeEntry entry) {
        subNodes.update(current -> {
            ObjectList<SubNodeEntry> next = new ObjectArrayList<>(current);
            next.removeIf(e -> e.id().equals(entry.id()));
            next.add(entry);
            return next;
        });
    }

    /**
     * Инвестиция ОВ в конкретный под-узел
     */
    public boolean setInvested(String subNodeId, boolean invested) {
        return updateEntry(subNodeId, entry -> new SubNodeEntry(
                entry.id(), entry.name(), entry.icon(), entry.cpCost(),
                invested,
                invested ? entry.assignedWorker() : SubNodeEntry.NO_WORKER, // отзыв ОВ снимает рабочего
                entry.lootTable(), entry.cycleStartTick(), entry.cycleDurationTicks()
        ));
    }

    /**
     * Назначение рабочего на под-узел
     */
    public boolean assignWorker(String subNodeId, UUID workerId, long currentTick, int durationTicks) {
        return updateEntry(subNodeId, entry -> {
            if (!entry.invested()) return entry; // нельзя назначить на неактивный под-узел
            return new SubNodeEntry(
                    entry.id(), entry.name(), entry.icon(), entry.cpCost(),
                    true, workerId, entry.lootTable(), currentTick, durationTicks
            );
        });
    }

    /**
     * Снятие рабочего
     */
    public boolean removeWorker(String subNodeId) {
        return updateEntry(subNodeId, entry -> new SubNodeEntry(
                entry.id(), entry.name(), entry.icon(), entry.cpCost(),
                entry.invested(), SubNodeEntry.NO_WORKER, entry.lootTable(), 0L, 0
        ));
    }

    public Optional<SubNodeEntry> getSubNode(String subNodeId) {
        for (SubNodeEntry entry : subNodes.get()) {
            if (entry.id().equals(subNodeId)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private boolean updateEntry(String id, java.util.function.UnaryOperator<SubNodeEntry> updater) {
        boolean[] changed = {false};
        subNodes.update(current -> {
            ObjectList<SubNodeEntry> next = new ObjectArrayList<>(current.size());
            for (SubNodeEntry entry : current) {
                if (entry.id().equals(id)) {
                    next.add(updater.apply(entry));
                    changed[0] = true;
                } else {
                    next.add(entry);
                }
            }
            return next;
        });
        return changed[0];
    }
}
