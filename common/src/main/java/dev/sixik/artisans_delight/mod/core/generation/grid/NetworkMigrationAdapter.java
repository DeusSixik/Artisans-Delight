package dev.sixik.artisans_delight.mod.core.generation.grid;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import dev.sixik.artisans_delight.mod.core.generation.path.CoarsePathfinder;
import dev.sixik.artisans_delight.mod.core.generation.path.GenerationProgress;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Адаптер для интеграции новой двухслойной системы генерации в существующий {@link dev.sixik.artisans_delight.mod.core.generation.path.KnotNetworkGraph}.
 * 
 * <p>Этот класс служит мостом между старой системой (прямой A* между городами) и новой
 * (грубый граф секций + тонкий слой детального pathfinding).
 * 
 * <p><b>Режимы работы:</b>
 * <ul>
 *   <li><b>Полная миграция (рекомендуется):</b> Использовать {@link #buildNetworkV2} вместо старого {@code KnotNetworkGraph.rebuildNetwork}</li>
 *   <li><b>Гибридный режим:</b> Новая система для новых узлов, старая для существующих (переходный период)</li>
 * </ul>
 * 
 * <p><b>Пример использования:</b>
 * <pre>{@code
 * // В KnotNetworkGraph.rebuildNetwork():
 * // СТАРОЕ:
 * // List<GraphEdge> validTopology = calculateTopology(knots);
 * 
 * // НОВОЕ:
 * NetworkMigrationAdapter.buildNetworkV2(knots, manager, worldSeed, gen, h, state);
 * }</pre>
 */
public final class NetworkMigrationAdapter {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkMigrationAdapter.class);
    
    /**
     * Флаг включения новой системы (для A/B тестирования).
     * TODO: Сделать конфигурируемым параметром.
     */
    private static boolean USE_NEW_SYSTEM = false; // ВРЕМЕННО ОТКЛЮЧЕНО для первого запуска
    
    private NetworkMigrationAdapter() {
    }
    
    /**
     * Включает/выключает новую систему генерации.
     * 
     * @param enabled {@code true} для новой системы, {@code false} для старой
     */
    public static void setNewSystemEnabled(boolean enabled) {
        USE_NEW_SYSTEM = enabled;
    }
    
    /**
     * Проверяет, включена ли новая система.
     */
    public static boolean isNewSystemEnabled() {
        return USE_NEW_SYSTEM;
    }
    
    /**
     * Строит полную сеть узлов через новую двухслойную систему.
     * 
     * <p>Это полная замена {@code KnotNetworkGraph.rebuildNetwork}.
     * 
     * <p><b>Алгоритм:</b>
     * <ol>
     *   <li>Очистка существующих маршрутов (сохраняя компоненты узлов)</li>
     *   <li>Построение грубого графа через {@link CoarseGraphBuilder} (цепочки узлов между городами)</li>
     *   <li>Построение тонкого слоя через {@link FinePathfinder} (геометрия дорог)</li>
     *   <li>Регистрация маршрутов в компонентах {@link KnotRoutesComponent}</li>
     * </ol>
     * 
     * @param knots Все узлы мира (города + существующие waypoint)
     * @param manager Менеджер узлов
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     */
    public static void buildNetworkV2(Collection<WorldObject> knots,
                                     WorldObjectManager manager,
                                     long worldSeed,
                                     ChunkGenerator generator,
                                     LevelHeightAccessor heightAccessor,
                                     RandomState randomState) {
        
        if (knots == null || knots.size() < 2 || manager == null) {
            return;
        }
        
        LOGGER.info("[NetworkMigrationAdapter] Начинаю построение сети V2: {} узлов, сид={}", knots.size(), worldSeed);
        GenerationProgress.begin();
        
        // Делаем снимок, чтобы безопасно удалять узлы из менеджера во время итерации
        List<WorldObject> snapshot = new ArrayList<>(knots);
        
        // 1. Очистка существующих маршрутов И соседей (полный пересбор графа).
        // Важно: очищаем neighbors, иначе остаются старые прямые связи город-город,
        // из-за которых на карте рисуются линии поверх цепочек промежуточных узлов.
        // Старые промежуточные WAYPOINT-узлы удаляем, чтобы не накапливались при пересборке.
        List<UUID> staleWaypointIds = new ArrayList<>();
        for (WorldObject obj : snapshot) {
            obj.getSingle(KnotRoutesComponent.class).ifPresent(KnotRoutesComponent::clearAllRoutes);
            obj.getSingle(KnotConnectionComponent.class).ifPresent(KnotConnectionComponent::clearNeighbors);
            
            // Помечаем на удаление старые сгенерированные waypoints (кроме портов/городов)
            obj.getSingle(KnotMetaComponent.class).ifPresent(meta -> {
                if (meta.type.get() == KnotType.WAYPOINT) {
                    staleWaypointIds.add(obj.id());
                }
            });
        }
        
        // Удаляем устаревшие промежуточные узлы из менеджера
        for (UUID id : staleWaypointIds) {
            manager.remove(id);
        }
        if (!staleWaypointIds.isEmpty()) {
            LOGGER.info("[NetworkMigrationAdapter] Удалено {} устаревших промежуточных узлов", staleWaypointIds.size());
        }
        
        LOGGER.info("[NetworkMigrationAdapter] Очистка маршрутов и связей завершена");
        
        // 2. Фильтруем города (TOWN) — они будут корнями графа
        List<WorldObject> towns = new ArrayList<>();
        for (WorldObject obj : snapshot) {
            obj.getSingle(KnotMetaComponent.class).ifPresent(meta -> {
                if (meta.type.get() == KnotType.TOWN) {
                    towns.add(obj);
                }
            });
        }
        
        if (towns.isEmpty()) {
            LOGGER.warn("[NetworkMigrationAdapter] Нет городов для построения сети!");
            return;
        }
        
        LOGGER.info("[NetworkMigrationAdapter] Найдено {} городов", towns.size());
        
        // 3. Строим грубый граф (создаёт цепочки узлов между городами)
        GenerationProgress.setStage(GenerationProgress.Stage.COARSE_GRAPH);
        long startCoarse = System.currentTimeMillis();
        CoarseGraphBuilder.buildNetwork(towns, manager, worldSeed, generator, heightAccessor, randomState);
        long coarseTime = System.currentTimeMillis() - startCoarse;
        LOGGER.info("[NetworkMigrationAdapter] Грубый граф построен за {} мс", coarseTime);
        
        // 3.5 Морские маршруты ВРЕМЕННО ОТКЛЮЧЕНЫ.
        // Для полноценных морских путей нужен отдельный механизм поиска по воде
        // (фарватер, морские узлы, обход островов). Пока портовые города
        // остаются обычными городами и подключаются только сухопутными дорогами.
        long seaTime = 0L;
        LOGGER.info("[NetworkMigrationAdapter] Морские маршруты отключены (SeaRouteBuilder не вызывается)");
        
        // 4. Строим тонкий слой (геометрия дорог между соседними узлами)
        GenerationProgress.setStage(GenerationProgress.Stage.FINE_GEOMETRY);
        long startFine = System.currentTimeMillis();
        buildFineLayerGeometry(manager.all(), worldSeed, generator, heightAccessor, randomState);
        long fineTime = System.currentTimeMillis() - startFine;
        LOGGER.info("[NetworkMigrationAdapter] Тонкий слой построен за {} мс", fineTime);
        
        GenerationProgress.finish();
        LOGGER.info("[NetworkMigrationAdapter] Сеть V2 построена полностью. Общее время: {} мс", coarseTime + seaTime + fineTime);
    }
    
    /**
     * Строит геометрию дорог для всех рёбер графа.
     * 
     * <p>Для каждой пары соседних узлов вызывает {@link FinePathfinder} и сохраняет
     * результат в {@link KnotRoutesComponent}.
     * 
     * @param knots Все узлы мира
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     */
    private static void buildFineLayerGeometry(Collection<WorldObject> knots,
                                              long worldSeed,
                                              ChunkGenerator generator,
                                              LevelHeightAccessor heightAccessor,
                                              RandomState randomState) {
        
        Map<UUID, WorldObject> knotMap = new HashMap<>();
        for (WorldObject obj : knots) {
            knotMap.put(obj.id(), obj);
        }
        
        LOGGER.info("[NetworkMigrationAdapter] Начинаю построение геометрии дорог для {} узлов", knots.size());
        
        // Обрабатываем все рёбра графа
        Set<String> processedEdges = new HashSet<>();
        int edgeCount = 0;
        int totalEdges = 0;
        
        // Сначала подсчитаем общее количество рёбер
        for (WorldObject from : knots) {
            KnotConnectionComponent conn = from.getSingle(KnotConnectionComponent.class).orElse(null);
            if (conn != null) {
                totalEdges += conn.neighbors.get().size();
            }
        }
        totalEdges = totalEdges / 2; // Делим на 2, т.к. каждое ребро считается дважды
        
        LOGGER.info("[NetworkMigrationAdapter] Обнаружено {} рёбер для обработки", totalEdges);
        
        for (WorldObject from : knots) {
            BlockPos fromPos = getPosition(from);
            if (fromPos == null) continue;
            
            KnotConnectionComponent conn = from.getSingle(KnotConnectionComponent.class).orElse(null);
            if (conn == null) continue;
            
            for (UUID toId : conn.neighbors.get()) {
                // Избегаем двойной обработки (A-B и B-A)
                String edgeKey = from.id().compareTo(toId) < 0 
                    ? from.id() + "_" + toId 
                    : toId + "_" + from.id();
                
                if (!processedEdges.add(edgeKey)) {
                    continue;
                }
                
                edgeCount++;
                
                WorldObject to = knotMap.get(toId);
                if (to == null) {
                    LOGGER.warn("[NetworkMigrationAdapter] Узел {} не найден!", toId);
                    continue;
                }
                
                BlockPos toPos = getPosition(to);
                if (toPos == null) continue;

                double euclid = Math.sqrt(fromPos.distSqr(toPos));

                dev.sixik.artisans_delight.mod.core.generation.path.GenerationProgress.setProgress(
                    edgeCount, totalEdges,
                    (int) euclid + "м"
                );
                
                // Определяем, морское ли это ребро (оба конца — морские узлы или порт)
                boolean isSeaEdge = isSeaNode(from) || isSeaNode(to);

                // Геометрия дороги через CoarsePathfinder (гибкая дорога с учётом рельефа,
                // мостами через реки и обходом океанов). Прямая линия — только fallback,
                // если pathfinder вернул пустой путь (например, разделённые океаном узлы).
                List<RoadPoint2D> path = List.of(
                    new RoadPoint2D(fromPos.getX(), fromPos.getZ(), false),
                    new RoadPoint2D(toPos.getX(), toPos.getZ(), false)
                );
                ObjectList<RoadPoint2D> found = CoarsePathfinder.findLandRoad(
                    generator, heightAccessor, randomState,
                    fromPos.getX(), fromPos.getZ(), toPos.getX(), toPos.getZ()
                );
                double distance;
                if (!found.isEmpty() && found.size() >= 2) {
                    List<RoadPoint2D> copy = new ArrayList<>(found.size());
                    for (RoadPoint2D pt : found) {
                        copy.add(new RoadPoint2D(pt.x(), pt.z(), pt.isBridge()));
                    }
                    path = copy;
                    distance = 0.0;
                    for (int i = 0; i < path.size() - 1; i++) {
                        distance += path.get(i).distance(path.get(i + 1));
                    }
                } else {
                    distance = Math.sqrt(fromPos.distSqr(toPos));
                    LOGGER.warn("[NetworkMigrationAdapter] Не удалось построить дорогу ({}м) между [{}, {}] и [{}, {}] — оставляю прямую линию",
                        (int) distance, fromPos.getX(), fromPos.getZ(), toPos.getX(), toPos.getZ());
                }
                
                // Сохраняем в финальную переменную для lambda
                final List<RoadPoint2D> finalPath = path;
                final double finalDistance = distance;
                final boolean finalIsSea = isSeaEdge;
                
                // Регистрируем путь в обоих направлениях
                from.getSingle(KnotRoutesComponent.class).ifPresent(routes -> {
                    routes.addOrUpdateRoute(new KnotRouteEntry(
                        toId,
                        new ObjectArrayList<>(finalPath),
                        finalIsSea,
                        finalDistance
                    ));
                });
                
                // Обратный путь (реверс)
                List<RoadPoint2D> reversePath = new ArrayList<>(path);
                Collections.reverse(reversePath);
                final List<RoadPoint2D> finalReversePath = reversePath;
                
                to.getSingle(KnotRoutesComponent.class).ifPresent(routes -> {
                    routes.addOrUpdateRoute(new KnotRouteEntry(
                        from.id(),
                        new ObjectArrayList<>(finalReversePath),
                        finalIsSea,
                        finalDistance
                    ));
                });
            }
        }
        
        LOGGER.info("[NetworkMigrationAdapter] Геометрия дорог построена: обработано {} рёбер", edgeCount);
    }
    
    /**
     * Проверяет, является ли узел морским (waypoint с именем "Sea Node").
     * 
     * @param obj Узел мира
     * @return {@code true}, если это морской узел
     */
    private static boolean isSeaNode(WorldObject obj) {
        return obj.getSingle(KnotMetaComponent.class)
            .map(meta -> meta.type.get() == KnotType.WAYPOINT 
                      && meta.name.get().startsWith("Sea Node"))
            .orElse(false);
    }
    
    /**
     * Гибридный режим: подключает новый узел к сети через новую систему.
     * 
     * <p>Используется в {@code KnotNetworkGraph.connectNewKnot} как замена
     * старой логики.
     * 
     * @param newKnot Новый узел (город или waypoint)
     * @param manager Менеджер узлов
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     */
    public static void connectNewKnotV2(WorldObject newKnot,
                                       WorldObjectManager manager,
                                       long worldSeed,
                                       ChunkGenerator generator,
                                       LevelHeightAccessor heightAccessor,
                                       RandomState randomState) {
        
        if (newKnot == null || manager == null) return;
        
        KnotMetaComponent newMeta = newKnot.getSingle(KnotMetaComponent.class).orElse(null);
        if (newMeta == null) return;
        
        // Если это город, строим полную сеть заново (с учётом нового города)
        if (newMeta.type.get() == KnotType.TOWN) {
            buildNetworkV2(manager.all(), manager, worldSeed, generator, heightAccessor, randomState);
        } else {
            // Если это обычный узел, подключаем к ближайшему городу
            connectResourceNode(newKnot, manager.all(), manager, worldSeed, generator, heightAccessor, randomState);
        }
    }
    
    /**
     * Подключает добычной узел к ближайшему городу/узлу через листовое ребро.
     * 
     * @param resourceNode Добычной узел (лесопилка, шахта)
     * @param existingKnots Существующие узлы
     * @param manager Менеджер узлов
     * @param worldSeed Сид мира
     * @param generator Генератор мира
     * @param heightAccessor Информация о границах высоты
     * @param randomState Состояние генератора шума
     */
    private static void connectResourceNode(WorldObject resourceNode,
                                           Collection<WorldObject> existingKnots,
                                           WorldObjectManager manager,
                                           long worldSeed,
                                           ChunkGenerator generator,
                                           LevelHeightAccessor heightAccessor,
                                           RandomState randomState) {
        
        BlockPos nodePos = getPosition(resourceNode);
        if (nodePos == null) return;
        
        // Ищем ближайший город или waypoint
        WorldObject nearestTemp = null;
        double minDist = Double.MAX_VALUE;
        
        for (WorldObject obj : existingKnots) {
            if (obj.equals(resourceNode)) continue;
            
            BlockPos objPos = getPosition(obj);
            if (objPos == null) continue;
            
            double dist = Math.sqrt(nodePos.distSqr(objPos));
            if (dist < minDist) {
                minDist = dist;
                nearestTemp = obj;
            }
        }
        
        final WorldObject nearest = nearestTemp; // Делаем effectively final
        
        if (nearest != null) {
            BlockPos nearestPos = getPosition(nearest);
            if (nearestPos == null) return;
            
            // Строим путь
            List<RoadPoint2D> path = FinePathfinder.findPath(
                nodePos, nearestPos, worldSeed, generator, heightAccessor, randomState
            );
            
            double distance = nodePos.distManhattan(nearestPos);
            
            // Регистрируем соседство
            resourceNode.getSingle(KnotConnectionComponent.class).ifPresent(conn -> {
                conn.addNeighbor(nearest.id());
            });
            
            nearest.getSingle(KnotConnectionComponent.class).ifPresent(conn -> {
                conn.addNeighbor(resourceNode.id());
            });
            
            // Регистрируем маршруты
            resourceNode.getSingle(KnotRoutesComponent.class).ifPresent(routes -> {
                routes.addOrUpdateRoute(new KnotRouteEntry(
                    nearest.id(),
                    new ObjectArrayList<>(path),
                    false,
                    distance
                ));
            });
            
            List<RoadPoint2D> reversePath = new ArrayList<>(path);
            Collections.reverse(reversePath);
            
            nearest.getSingle(KnotRoutesComponent.class).ifPresent(routes -> {
                routes.addOrUpdateRoute(new KnotRouteEntry(
                    resourceNode.id(),
                    new ObjectArrayList<>(reversePath),
                    false,
                    distance
                ));
            });
        }
    }
    
    /**
     * Извлекает позицию узла из метаданных.
     */
    @Nullable
    private static BlockPos getPosition(WorldObject obj) {
        return obj.getSingle(KnotMetaComponent.class)
            .map(meta -> meta.position.get())
            .orElse(null);
    }
}
