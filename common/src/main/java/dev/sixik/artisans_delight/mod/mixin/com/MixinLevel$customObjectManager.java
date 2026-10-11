package dev.sixik.artisans_delight.mod.mixin.com;

import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.LevelObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public class MixinLevel$customObjectManager implements LevelObjectManager {

    @Unique
    private WorldObjectManager sdm$manager;

    @Inject(method = "<init>", at = @At("RETURN"))
    public void sdm$init(CallbackInfo ci) {
        sdm$manager = WorldObjectManagerApi.registerWorld((Level) (Object) this);
    }

    @Override
    public WorldObjectManager getManager() {
        return sdm$manager;
    }

    @Inject(method = "close", at = @At("RETURN"))
    public void sdm$close(CallbackInfo ci) {
        if(sdm$manager == null) {
            return;
        }

        sdm$manager.close();
    }
}
