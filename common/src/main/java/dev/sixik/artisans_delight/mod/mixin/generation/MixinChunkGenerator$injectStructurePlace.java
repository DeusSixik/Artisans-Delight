package dev.sixik.artisans_delight.mod.mixin.generation;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.api.ArtisansDelightConstants;
import dev.sixik.artisans_delight.mod.api.KnotApi;
import dev.sixik.artisans_delight.mod.core.generation.GenerationData;
import dev.sixik.artisans_delight.mod.core.generation.GenerationHook;
import dev.sixik.artisans_delight.mod.core.generation.path.KnotNetworkGraph;
import dev.sixik.artisans_delight.mod.core.generation.path.RouteGenerator;
import dev.sixik.artisans_delight.mod.core.generation.terrain.TerrainSampler;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownLodgingComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownPortComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownWarehouseComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownWorkshopsComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(ChunkGenerator.class)
public class MixinChunkGenerator$injectStructurePlace {

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    public void sdm$applyBiomeDecoration(
            WorldGenLevel worldGenLevel, ChunkAccess chunkAccess,
            StructureManager structureManager, CallbackInfo ci) {
        GenerationHook.applyLevelForWorldGeneration(worldGenLevel, chunkAccess, structureManager);
    }

    @Inject(method = "tryGenerateStructure", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/StructureManager;setStartForStructure(Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/levelgen/structure/Structure;Lnet/minecraft/world/level/levelgen/structure/StructureStart;Lnet/minecraft/world/level/chunk/StructureAccess;)V", shift = At.Shift.AFTER))
    public void sdm$tryGenerateStructure(StructureSet.StructureSelectionEntry structureSelectionEntry,
                                         StructureManager structureManager, RegistryAccess registryAccess,
                                         RandomState randomState, StructureTemplateManager structureTemplateManager,
                                         long l, ChunkAccess chunkAccess, ChunkPos chunkPos, SectionPos sectionPos,
                                         CallbackInfoReturnable<Boolean> cir) {
        final KnotType type = KnotApi.getType(structureSelectionEntry.structure());

        if(type == KnotType.NONE) {
            return;
        }

        final GenerationData data = GenerationHook.getLevelForWorldGeneration();
        final WorldObjectManager manager = WorldObjectManagerApi.getManager(data.getGenLevel().getLevel());

        WorldObject townKnot = new WorldObject();
        townKnot.attach(new KnotMetaComponent("Оливия_" + sectionPos.asLong(), ResourceLocation.tryBuild("minecraft", "air"),
                KnotType.TOWN, sectionPos.origin()));
        townKnot.attach(new KnotConnectionComponent());
        townKnot.attach(new KnotRoutesComponent());
        townKnot.attach(new TownWarehouseComponent());
        townKnot.attach(new TownLodgingComponent());
        townKnot.attach(new TownWorkshopsComponent());

        // Проверяем, является ли поселение прибрежным портом
        ChunkGenerator chunkGenerator = (ChunkGenerator) (Object) this;
        Optional<BlockPos> pierPos = TerrainSampler.findCoastlinePier(
                chunkGenerator, data.getGenLevel(), randomState, sectionPos.origin(), 180);
        pierPos.ifPresent(pos -> townKnot.attach(new TownPortComponent(pos)));

        manager.add(townKnot);

        // Автоматически подключаем поселение к сети узлов мира без создания лишних прямых срезок
        KnotNetworkGraph.connectNewKnot(townKnot, manager, chunkGenerator, data.getGenLevel(), randomState);
    }
}
