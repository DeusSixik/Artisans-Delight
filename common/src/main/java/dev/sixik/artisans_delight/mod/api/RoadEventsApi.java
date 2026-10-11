package dev.sixik.artisans_delight.mod.api;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.core.generation.path.RoadObstacle;
import dev.sixik.artisans_delight.mod.core.generation.path.RouteDetourEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Публичный API управления дорожными событиями и препятствиями для разработчиков аддонов и модпаков.
 */
public final class RoadEventsApi {

    private static final Map<ResourceKey<Level>, Map<ResourceLocation, RoadObstacle>> ACTIVE_OBSTACLES =
            new ConcurrentHashMap<>();

    private RoadEventsApi() {
    }

    /**
     * Зарегистрировать препятствие на дороге (лагерь разбойников, упавшее дерево, обвал, блокпост).
     * Система автоматически находит пересекаемые тракты и перестраивает локальные объезды.
     *
     * @return количество дорог, для которых был построен объезд
     */
    public static int registerObstacle(ServerLevel level, RoadObstacle obstacle) {
        if (level == null || obstacle == null) return 0;

        Map<ResourceLocation, RoadObstacle> map = ACTIVE_OBSTACLES.computeIfAbsent(
                level.dimension(),
                k -> new ConcurrentHashMap<>()
        );
        map.put(obstacle.id(), obstacle);

        WorldObjectManager manager = WorldObjectManagerApi.getManager(level);
        if (manager == null) return 0;

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        return RouteDetourEngine.applyObstacleToNetwork(
                manager.all(), obstacle, generator, level, randomState
        );
    }

    /**
     * Снять препятствие (например, после зачистки лагеря игроком).
     * Дороги бесшовно возвращаются к своему исходному прямому участку.
     *
     * @return true, если препятствие было удалено
     */
    public static boolean removeObstacle(ServerLevel level, ResourceLocation obstacleId) {
        if (level == null || obstacleId == null) return false;

        Map<ResourceLocation, RoadObstacle> map = ACTIVE_OBSTACLES.get(level.dimension());
        if (map == null || map.remove(obstacleId) == null) {
            return false;
        }

        WorldObjectManager manager = WorldObjectManagerApi.getManager(level);
        if (manager == null) return true;

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();

        // 1. Восстанавливаем базовые трассы
        RouteDetourEngine.restoreNetworkRoutes(manager.all());

        // 2. Если остались другие активные препятствия, заново применяем их
        for (RoadObstacle remaining : map.values()) {
            RouteDetourEngine.applyObstacleToNetwork(
                    manager.all(), remaining, generator, level, randomState
            );
        }

        return true;
    }

    /**
     * Получить все активные препятствия в текущем измерении.
     */
    public static Collection<RoadObstacle> getObstacles(Level level) {
        if (level == null) return Collections.emptyList();
        Map<ResourceLocation, RoadObstacle> map = ACTIVE_OBSTACLES.get(level.dimension());
        return map != null ? Collections.unmodifiableCollection(map.values()) : Collections.emptyList();
    }

    /**
     * Проверка, находится ли точка в зоне действия какого-либо препятствия.
     */
    public static boolean isPointBlocked(Level level, BlockPos pos, double buffer) {
        if (level == null || pos == null) return false;
        Map<ResourceLocation, RoadObstacle> map = ACTIVE_OBSTACLES.get(level.dimension());
        if (map == null || map.isEmpty()) return false;

        for (RoadObstacle obs : map.values()) {
            if (obs.affectsPoint(pos.getX(), pos.getZ(), buffer)) {
                return true;
            }
        }
        return false;
    }
}
