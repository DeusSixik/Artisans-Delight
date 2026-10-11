package dev.sixik.artisans_delight.mod.core.knot.data;

import dev.sixik.artisans_delight.libs.world.sync.FieldCodec;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;

import java.util.Objects;

/**
 * Компактная 2D точка маршрута дороги или морского пути (только X и Z, без высоты Y).
 * Включает флаг моста (isBridge) для отображения и генерации переправ через реки.
 */
public record RoadPoint2D(int x, int z, boolean isBridge) {

    public RoadPoint2D(int x, int z) {
        this(x, z, false);
    }

    /**
     * Битовая упаковка с поддержкой флага моста (isBridge) и обратной совместимостью.
     * Координаты мира [-33_554_432 .. +33_554_431] укладываются в 26 бит каждая.
     */
    public static long pack(int x, int z, boolean isBridge) {
        long ox = ((long) x + 33554432L) & 0x3FFFFFFL;
        long oz = ((long) z + 33554432L) & 0x3FFFFFFL;
        long b = isBridge ? 1L : 0L;
        return (1L << 63) | (b << 52) | (ox << 26) | oz;
    }

    public static long pack(int x, int z) {
        return pack(x, z, false);
    }

    public static RoadPoint2D unpack(long packed) {
        if ((packed & (1L << 63)) != 0L) {
            // Новый формат: бит 63 = 1, бит 52 = isBridge, биты 26..51 = X, биты 0..25 = Z
            boolean bridge = ((packed >> 52) & 1L) != 0L;
            int x = (int) (((packed >> 26) & 0x3FFFFFFL) - 33554432L);
            int z = (int) ((packed & 0x3FFFFFFL) - 33554432L);
            return new RoadPoint2D(x, z, bridge);
        } else {
            // Устаревший формат: (long) x << 32 | (z & 0xFFFFFFFFL)
            return new RoadPoint2D((int) (packed >> 32), (int) packed, false);
        }
    }

    public double distSqr(RoadPoint2D other) {
        double dx = (double) this.x - other.x;
        double dz = (double) this.z - other.z;
        return dx * dx + dz * dz;
    }

    public double distSqr(int ox, int oz) {
        double dx = (double) this.x - ox;
        double dz = (double) this.z - oz;
        return dx * dx + dz * dz;
    }

    public double distance(RoadPoint2D other) {
        return Math.sqrt(distSqr(other));
    }

    public static final FieldCodec<RoadPoint2D> CODEC = FieldCodecs.compoundOf(
            "artisansdelight:road_point_2d",
            (nbt, pt) -> {
                nbt.putInt("x", pt.x());
                nbt.putInt("z", pt.z());
                if (pt.isBridge()) {
                    nbt.putBoolean("bridge", true);
                }
            },
            (nbt, defaultValue) -> new RoadPoint2D(nbt.getInt("x"), nbt.getInt("z"), nbt.getBoolean("bridge")),
            pt -> pt,
            Objects::equals
    );
}
