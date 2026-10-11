package dev.sixik.artisans_delight.libs.world.codecs;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

public class ExternFieldCodecs {

    public static final SectionPos SECTION_POS_ZERO = SectionPos.of(0,0,0);

    public static ResourceLocationCodec resourceLocation() {
        return ResourceLocationCodec.INSTANCE;
    }

    public static BlockPosCodec blockPos() {
        return BlockPosCodec.INSTANCE;
    }

    public static ChunkPosCodec chunkPos() {
        return ChunkPosCodec.INSTANCE;
    }

    public static SectionPosCodec sectionPos() {
        return SectionPosCodec.INSTANCE;
    }

    public enum ResourceLocationCodec implements FieldCodec<ResourceLocation> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, ResourceLocation resourceLocation) {
            nbt.putString(key, resourceLocation.toString());
        }

        @Override
        public ResourceLocation fromNbt(CompoundTag nbt, String key, ResourceLocation defaultValue) {
            return ResourceLocation.tryParse(nbt.getString(key));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, ResourceLocation resourceLocation) {
            buf.writeResourceLocation(resourceLocation);
        }

        @Override
        public ResourceLocation fromNetwork(FriendlyByteBuf buf) {
            return buf.readResourceLocation();
        }
    }

    public enum BlockPosCodec implements FieldCodec<BlockPos> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, BlockPos blockPos) {
            nbt.putLong(key, blockPos.asLong());
        }

        @Override
        public BlockPos fromNbt(CompoundTag nbt, String key, BlockPos defaultValue) {
            return BlockPos.of(nbt.getLong(key));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, BlockPos blockPos) {
            buf.writeLong(blockPos.asLong());
        }

        @Override
        public BlockPos fromNetwork(FriendlyByteBuf buf) {
            return BlockPos.of(buf.readLong());
        }
    }

    public enum ChunkPosCodec implements FieldCodec<ChunkPos> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, ChunkPos blockPos) {
            nbt.putLong(key, blockPos.toLong());
        }

        @Override
        public ChunkPos fromNbt(CompoundTag nbt, String key, ChunkPos defaultValue) {
            return new ChunkPos(nbt.getLong(key));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, ChunkPos blockPos) {
            buf.writeLong(blockPos.toLong());
        }

        @Override
        public ChunkPos fromNetwork(FriendlyByteBuf buf) {
            return new ChunkPos(buf.readLong());
        }
    }

    public enum SectionPosCodec implements FieldCodec<SectionPos> {
        INSTANCE;

        @Override
        public void toNbt(CompoundTag nbt, String key, SectionPos blockPos) {
            nbt.putLong(key, blockPos.asLong());
        }

        @Override
        public SectionPos fromNbt(CompoundTag nbt, String key, SectionPos defaultValue) {
            return SectionPos.of(nbt.getLong(key));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, SectionPos blockPos) {
            buf.writeLong(blockPos.asLong());
        }

        @Override
        public SectionPos fromNetwork(FriendlyByteBuf buf) {
            return SectionPos.of(buf.readLong());
        }
    }
}
