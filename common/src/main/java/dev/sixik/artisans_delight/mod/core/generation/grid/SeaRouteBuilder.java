package dev.sixik.artisans_delight.mod.core.generation.grid;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownPortComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Строитель морских маршрутов между портами.
 * 
 * <p>Этот класс создаёт морские узлы (waypoints) в океане и соединяет портовые города через них.
 * 
 * <p><b>Алгоритм:</b>
 * <ol>
 *   <li>Находит все города с компонентом {@link TownPortComponent} (порты)</li>
 *   <li>Для каждой пары портов создаёт цепочку морских waypoints</li>
 *   <li>Морские узлы размещаются каждые 600 блоков (дальше, чем на суше)</li>
 *   <li>Морские узлы НЕ соединяются с сухопутными узлами</li>
 * </ol>
 * 
 * <p><b>Пример использования:</b>
 * <pre>{@code
 * SeaRouteBuilder.buildSeaRoutes(allKnots, manager, worldSeed, gen, h, state);
 * }</pre>
 */
public final class SeaRouteBuilder {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SeaRouteBuilder.class);
    
    /**
     * Расстояние между морскими узлами (больше, чем на суше).
     */
    public static final int SEA_NODE_SPACING = 600;
    
    /**
     * Минимальное расстояние между портами для создания морского маршрута.
     * Если порты ближе — их соединит сухопутная дорога.
     */
    public static final double MIN_SEA_ROUTE_DISTANCE = 800.0;
    
    /**
     * Максимальное расстояние для морского маршрута (слишком далёкие порты не соединяются).
     */
    public static final double MAX_SEA_ROUTE_DISTANCE = 8000.0;
    
    private SeaRouteBuilder() {
    }
    
    /**
     * Строит морские маршруты между всеми портами.
     * 
     * @param knots Все узлы мира
     * @param manager Менеджер узлов
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     */
    public static void buildSeaRoutes(Collection<WorldObject> knots,
                                     WorldObjectManager manager,
                                     long worldSeed,
                                     ChunkGenerator generator,
                                     LevelHeightAccessor heightAccessor,
                                     RandomState randomState) {
        
        // 1. Находим все порты
        List<WorldObject> ports = new ArrayList<>();
        for (WorldObject obj : knots) {
            Optional<TownPortComponent> portOpt = obj.getSingle(TownPortComponent.class);
            Optional<KnotMetaComponent> metaOpt = obj.getSingle(KnotMetaComponent.class);
            if (portOpt.isPresent() && metaOpt.isPresent() && metaOpt.get().type.get() == KnotType.TOWN) {
                ports.add(obj);
            }
        }
        
        if (ports.size() < 2) {
            LOGGER.info("[SeaRouteBuilder] Недостаточно портов для создания морских маршрутов: {}", ports.size());
            return;
        }
        
        LOGGER.info("[SeaRouteBuilder] Начинаю построение морских маршрутов для {} портов", ports.size());
        
        // 2. Строим MST между портами
        List<PortEdge> seaEdges = calculatePortMST(ports);
        
        LOGGER.info("[SeaRouteBuilder] Построено {} морских маршрутов", seaEdges.size());
        
        // 3. Для каждого морского ребра создаём цепочку морских узлов
        int totalSeaNodes = 0;
        for (int i = 0; i < seaEdges.size(); i++) {
            PortEdge edge = seaEdges.get(i);
            LOGGER.info("[SeaRouteBuilder] Обрабатываю морской маршрут {}/{}: {} -> {} (расстояние: {} блоков)", 
                i + 1, seaEdges.size(), 
                edge.portA.id().toString().substring(0, 8), 
                edge.portB.id().toString().substring(0, 8),
                (int) edge.distance);
            
            int nodes = createSeaNodeChain(edge.portA, edge.portB, manager, worldSeed, generator, heightAccessor, randomState);
            totalSeaNodes += nodes;
            LOGGER.info("[SeaRouteBuilder] Создано {} морских узлов", nodes);
        }
        
        LOGGER.info("[SeaRouteBuilder] Морские маршруты построены. Всего создано {} морских узлов", totalSeaNodes);
    }
    
    /**
     * Вычисляет MST между портами.
     */
    private static List<PortEdge> calculatePortMST(List<WorldObject> ports) {
        List<PortEdge> edges = new ArrayList<>();
        
        // Создаём все возможные рёбра между портами
        for (int i = 0; i < ports.size(); i++) {
            for (int j = i + 1; j < ports.size(); j++) {
                WorldObject portA = ports.get(i);
                WorldObject portB = ports.get(j);
                
                BlockPos posA = portA.getSingle(KnotMetaComponent.class).get().position.get();
                BlockPos posB = portB.getSingle(KnotMetaComponent.class).get().position.get();
                
                double distance = Math.hypot(posA.getX() - posB.getX(), posA.getZ() - posB.getZ());
                
                // Фильтруем слишком близкие и слишком далёкие порты
                if (distance >= MIN_SEA_ROUTE_DISTANCE && distance <= MAX_SEA_ROUTE_DISTANCE) {
                    edges.add(new PortEdge(portA, portB, distance));
                }
            }
        }
        
        // Сортируем по расстоянию (для MST)
        edges.sort(Comparator.comparingDouble(e -> e.distance));
        
        // Алгоритм Крускала для MST
        Map<WorldObject, WorldObject> parent = new HashMap<>();
        for (WorldObject port : ports) {
            parent.put(port, port);
        }
        
        List<PortEdge> mstEdges = new ArrayList<>();
        for (PortEdge edge : edges) {
            WorldObject rootA = findRoot(edge.portA, parent);
            WorldObject rootB = findRoot(edge.portB, parent);
            
            if (rootA != rootB) {
                mstEdges.add(edge);
                parent.put(rootA, rootB);
            }
        }
        
        return mstEdges;
    }
    
    private static WorldObject findRoot(WorldObject obj, Map<WorldObject, WorldObject> parent) {
        WorldObject root = obj;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        // Path compression
        WorldObject current = obj;
        while (current != root) {
            WorldObject next = parent.get(current);
            parent.put(current, root);
            current = next;
        }
        return root;
    }
    
    /**
     * Создаёт цепочку морских узлов между двумя портами.
     */
    private static int createSeaNodeChain(WorldObject portA,
                                         WorldObject portB,
                                         WorldObjectManager manager,
                                         long worldSeed,
                                         ChunkGenerator generator,
                                         LevelHeightAccessor heightAccessor,
                                         RandomState randomState) {
        
        BlockPos posA = portA.getSingle(KnotMetaComponent.class).get().position.get();
        BlockPos posB = portB.getSingle(KnotMetaComponent.class).get().position.get();
        
        double distance = Math.hypot(posA.getX() - posB.getX(), posA.getZ() - posB.getZ());
        int nodeCount = Math.max(1, (int) Math.ceil(distance / SEA_NODE_SPACING) - 1);
        
        if (nodeCount == 0) {
            // Порты слишком близко — соединяем напрямую
            connectNodes(portA, portB);
            return 0;
        }
        
        LOGGER.info("[SeaRouteBuilder] Создаю цепочку: {} узлов для расстояния {} блоков", nodeCount, (int)distance);
        
        List<WorldObject> chain = new ArrayList<>();
        chain.add(portA);
        
        // Создаём промежуточные морские узлы
        Random random = new Random(worldSeed ^ (portA.id().hashCode() + portB.id().hashCode()));
        
        for (int i = 1; i <= nodeCount; i++) {
            double t = (double) i / (nodeCount + 1);
            int x = (int) (posA.getX() + t * (posB.getX() - posA.getX()));
            int z = (int) (posA.getZ() + t * (posB.getZ() - posA.getZ()));
            
            // Небольшой случайный сдвиг (±100 блоков) для естественности
            x += random.nextInt(200) - 100;
            z += random.nextInt(200) - 100;
            
            // Морские узлы размещаем на уровне моря (y=64)
            int y = 64;
            BlockPos nodePos = new BlockPos(x, y, z);
            
            // Создаём морской waypoint
            WorldObject seaNode = manager.create();
            
            KnotMetaComponent meta = new KnotMetaComponent(KnotType.WAYPOINT, nodePos);
            meta.name.set("Sea Node #" + (i));
            seaNode.attach(meta);
            
            KnotConnectionComponent conn = new KnotConnectionComponent();
            conn.investmentCost.set(0);
            conn.isInvested.set(true); // Морские узлы всегда активны
            seaNode.attach(conn);
            
            seaNode.attach(new KnotRoutesComponent());
            
            chain.add(seaNode);
        }
        
        chain.add(portB);
        
        // Соединяем цепочку
        for (int i = 0; i < chain.size() - 1; i++) {
            connectNodes(chain.get(i), chain.get(i + 1));
        }
        
        return nodeCount;
    }
    
    /**
     * Соединяет два узла (добавляет друг друга в neighbors).
     */
    private static void connectNodes(WorldObject a, WorldObject b) {
        a.getSingle(KnotConnectionComponent.class).ifPresent(conn -> {
            conn.addNeighbor(b.id());
        });
        
        b.getSingle(KnotConnectionComponent.class).ifPresent(conn -> {
            conn.addNeighbor(a.id());
        });
    }
    
    /**
     * Ребро между двумя портами.
     */
    private static class PortEdge {
        final WorldObject portA;
        final WorldObject portB;
        final double distance;
        
        PortEdge(WorldObject portA, WorldObject portB, double distance) {
            this.portA = portA;
            this.portB = portB;
            this.distance = distance;
        }
    }
}
