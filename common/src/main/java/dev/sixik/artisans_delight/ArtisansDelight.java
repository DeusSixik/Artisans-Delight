package dev.sixik.artisans_delight;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.PlayerEvent;
import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.core.client.TestMap;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.Optional;

public final class ArtisansDelight {

    public static void init() {
        // Write common init code here.

        PlayerEvent.DROP_ITEM.register((player, item) -> {
            if (player.level().isClientSide) return EventResult.pass();

            final var level = player.level();

            final WorldObjectManager manager = WorldObjectManagerApi.getManager(level);
            final Collection<WorldObject> objects = manager.all();

            for (WorldObject object : objects) {
                Optional<KnotMetaComponent> component = object.getSingle(KnotMetaComponent.class);
                if(component.isEmpty())
                    continue;

                final KnotMetaComponent c = component.get();

                player.sendSystemMessage(Component.literal(c.name + " : " + c.pos));
            }

            RenderSystem.recordRenderCall(() -> {
                TestMap.openMap(manager, objects);
            });

            return EventResult.pass();
        });
    }
}
