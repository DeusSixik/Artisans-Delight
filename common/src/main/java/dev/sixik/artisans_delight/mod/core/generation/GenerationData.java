package dev.sixik.artisans_delight.mod.core.generation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

public class GenerationData {

    private WorldGenLevel genLevel;
    private ChunkAccess chunkAccess;
    private StructureManager structureManager;
    private boolean isServerLevel;

    public GenerationData() { }


    public WorldGenLevel getGenLevel() {
        return genLevel;
    }

    public boolean isServerLevel() {
        return isServerLevel;
    }

    public ChunkAccess getChunkAccess() {
        return chunkAccess;
    }

    public StructureManager getStructureManager() {
        return structureManager;
    }

    void setGenData(WorldGenLevel genLevel, ChunkAccess chunkAccess, StructureManager structureManager) {
        this.genLevel = genLevel;
        this.isServerLevel = genLevel instanceof ServerLevel;
        this.chunkAccess = chunkAccess;
        this.structureManager = structureManager;
    }
}
