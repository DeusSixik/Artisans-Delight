package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Виртуальная дорожная сеть: узлы + рёбра.
 *
 * <p>Неизменяемый снимок результата расчёта. Потокобезопасен по построению:
 * публикуется целиком через {@link RoadNetworkStore}, читатели (карта)
 * видят либо старую, либо новую сеть, но никогда — частично построенную.
 */
public final class RoadNetwork {

    private final Map<UUID, RoadNode> nodes;
    private final Map<String, RoadEdge> edges;
    private final long worldSeed;
    private final long computedAtMillis;

    public RoadNetwork(long worldSeed, Map<UUID, RoadNode> nodes, Map<String, RoadEdge> edges) {
        this.worldSeed = worldSeed;
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.edges = Collections.unmodifiableMap(new LinkedHashMap<>(edges));
        this.computedAtMillis = System.currentTimeMillis();
    }

    public static RoadNetwork empty(long worldSeed) {
        return new RoadNetwork(worldSeed, Map.of(), Map.of());
    }

    public long worldSeed() {
        return worldSeed;
    }

    public long computedAtMillis() {
        return computedAtMillis;
    }

    public Collection<RoadNode> nodes() {
        return nodes.values();
    }

    public Collection<RoadEdge> edges() {
        return edges.values();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public RoadNode node(UUID id) {
        return nodes.get(id);
    }

    /** Все рёбра, инцидентные узлу. */
    public List<RoadEdge> edgesOf(UUID nodeId) {
        List<RoadEdge> result = new ArrayList<>();
        for (RoadEdge edge : edges.values()) {
            if (edge.connects(nodeId)) {
                result.add(edge);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}
