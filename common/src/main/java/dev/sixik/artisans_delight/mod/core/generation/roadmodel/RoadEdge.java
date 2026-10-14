package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Виртуальное ребро дорожной сети между двумя узлами.
 *
 * <p>Геометрия — ломаная {@link RoadPoint2D} с флагами мостов, как раньше
 * хранилось в {@code KnotRouteEntry}. Направление не имеет значения:
 * ребро {@code A-B} эквивалентно {@code B-A} (геометрия реверсируется
 * при чтении при необходимости).
 */
public final class RoadEdge {

    private final UUID fromId;
    private final UUID toId;
    private final List<RoadPoint2D> geometry;
    private final boolean seaRoute;
    private final double distance;
    /**
     * Ребро запланировано топологией, но реальная дорога ещё не посчитана:
     * геометрия — прямая хорда-заглушка. Дорогу досчитает
     * {@code RoadStreamer}, когда игрок подойдёт ближе.
     */
    private final boolean pending;

    public RoadEdge(UUID fromId, UUID toId, List<RoadPoint2D> geometry, boolean seaRoute, double distance) {
        this(fromId, toId, geometry, seaRoute, distance, false);
    }

    public RoadEdge(UUID fromId, UUID toId, List<RoadPoint2D> geometry, boolean seaRoute, double distance,
                    boolean pending) {
        this.fromId = Objects.requireNonNull(fromId, "fromId");
        this.toId = Objects.requireNonNull(toId, "toId");
        this.geometry = geometry == null ? List.of() : List.copyOf(geometry);
        this.seaRoute = seaRoute;
        this.distance = distance;
        this.pending = pending;
    }

    /** {@code true}, если дорога ещё не посчитана (геометрия — хорда-заглушка). */
    public boolean pending() {
        return pending;
    }

    public UUID fromId() {
        return fromId;
    }

    public UUID toId() {
        return toId;
    }

    /** Ломаная дороги (минимум 2 точки после успешного расчёта). */
    public List<RoadPoint2D> geometry() {
        return geometry;
    }

    public boolean seaRoute() {
        return seaRoute;
    }

    /** Длина дороги вдоль геометрии (не евклидова). */
    public double distance() {
        return distance;
    }

    /** Канонический ключ ребра (не зависит от направления). */
    public String key() {
        return fromId.compareTo(toId) < 0 ? fromId + "_" + toId : toId + "_" + fromId;
    }

    public boolean connects(UUID id) {
        return fromId.equals(id) || toId.equals(id);
    }

    public UUID otherEnd(UUID id) {
        if (fromId.equals(id)) return toId;
        if (toId.equals(id)) return fromId;
        throw new IllegalArgumentException("Node " + id + " is not part of edge " + key());
    }
}
