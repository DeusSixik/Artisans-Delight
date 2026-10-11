package dev.sixik.artisans_delight.mod.core.generation.path;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Препятствие на дороге (лагерь разбойников, обвал, блокпост, упавшее дерево, зона события).
 */
public record RoadObstacle(
        ResourceLocation id,
        BlockPos center,
        double radius,
        ObstacleSeverity severity
) {
    public enum ObstacleSeverity {
        BLOCKING,  // Глухая блокада: проход полностью перекрыт, требуется объезд
        HAZARDOUS  // Опасная зона: высокий штраф, объезд строится при возможности
    }

    public boolean affectsPoint(double x, double z, double buffer) {
        double r = radius + buffer;
        double dx = (double) center.getX() - x;
        double dz = (double) center.getZ() - z;
        return (dx * dx + dz * dz) <= (r * r);
    }
}
