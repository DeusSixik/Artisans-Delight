package dev.sixik.artisans_delight.mod.core.generation;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

public final class GenerationHook {

    private static final ThreadLocal<GenerationData> GENERATION_THREAD_LEVEL =
            ThreadLocal.withInitial(GenerationData::new);

    public static void applyLevelForWorldGeneration(WorldGenLevel level, ChunkAccess chunkAccess,
                                                    StructureManager structureManager) {
        GENERATION_THREAD_LEVEL.get().setGenData(level, chunkAccess, structureManager);
    }

    public static GenerationData getLevelForWorldGeneration() {
        return GENERATION_THREAD_LEVEL.get();
    }
}
