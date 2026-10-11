package dev.sixik.artisans_delight.mod.mixin.generation;

import dev.sixik.artisans_delight.mod.core.generation.GenerationHook;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkStatusTasks.class)
public class MixinChunkStatusTasks$injectStructureGeneration {

    @Inject(method = "generateStructureStarts", at = @At("HEAD"))
    private static void sdm$generateStructureStarts(WorldGenContext worldGenContext, ChunkStep chunkStep,
                                                    StaticCache2D<GenerationChunkHolder> staticCache2D,
                                                    ChunkAccess chunkAccess,
                                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        final ServerLevel serverLevel = worldGenContext.level();
        GenerationHook.applyLevelForWorldGeneration(serverLevel, chunkAccess, serverLevel.structureManager());
    }
}
