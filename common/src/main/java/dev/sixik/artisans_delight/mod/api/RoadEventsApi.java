package dev.sixik.artisans_delight.mod.api;

import dev.sixik.artisans_delight.mod.core.generation.path.RoadObstacle;
import dev.sixik.artisans_delight.mod.core.generation.path.RoadObstacleStore;
import dev.sixik.artisans_delight.mod.core.generation.path.RouteDetourEngine;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadCalcCache;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadEdge;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetwork;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetworkStore;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.List;

/**
 * Публичный API управления дорожными событиями и препятствиями для разработчиков аддонов и модпаков.
 *
 * <p>Препятствие (лагерь разбойников, обвал, блокпост) регистрируется в мире;
 * дороги, которые оно задевает, получают локальный объезд вокруг него.
 * Работает поверх виртуальной сети: базовые дороги не меняются, объезд —
 * слой, который снимается вместе с препятствием. Объезд считается в фоне,
 * на карте и в мире он появляется через несколько секунд.
 *
 * <p>Препятствия сохраняются вместе с дорогами мира и переживают перезапуск.
 * Вызывать с серверного потока.
 */
public final class RoadEventsApi {

    private RoadEventsApi() {
    }

    /**
     * Зарегистрировать препятствие на дороге (или заменить препятствие с тем же id).
     * Объезды задетых дорог строятся асинхронно.
     *
     * @return количество уже посчитанных дорог, которые задевает препятствие
     *         (для них запущен расчёт объезда); дороги, досчитанные позже,
     *         получат объезд автоматически
     */
    public static int registerObstacle(ServerLevel level, RoadObstacle obstacle) {
        if (level == null || obstacle == null) return 0;
        RouteDetourEngine.bind(level);
        RoadObstacleStore.put(scope(level), obstacle);
        int affected = countAffected(obstacle);
        RoadNetworkStore.republish();
        return affected;
    }

    /**
     * Снять препятствие (например, после зачистки лагеря игроком).
     * Задетые дороги возвращаются к исходной геометрии.
     *
     * @return true, если препятствие было удалено
     */
    public static boolean removeObstacle(ServerLevel level, ResourceLocation obstacleId) {
        if (level == null || obstacleId == null) return false;
        if (!RoadObstacleStore.remove(scope(level), obstacleId)) {
            return false;
        }
        RouteDetourEngine.bind(level);
        RoadNetworkStore.republish();
        return true;
    }

    /**
     * Получить все активные препятствия в текущем измерении.
     */
    public static Collection<RoadObstacle> getObstacles(Level level) {
        if (!(level instanceof ServerLevel server)) return List.of();
        return RoadObstacleStore.get(scope(server));
    }

    /**
     * Проверка, находится ли точка в зоне действия какого-либо препятствия.
     */
    public static boolean isPointBlocked(Level level, BlockPos pos, double buffer) {
        if (pos == null) return false;
        for (RoadObstacle obs : getObstacles(level)) {
            if (obs.affectsPoint(pos.getX(), pos.getZ(), buffer)) {
                return true;
            }
        }
        return false;
    }

    private static String scope(ServerLevel level) {
        return RoadCalcCache.scopeKey(level.getSeed(), level.dimension().location().toString());
    }

    private static int countAffected(RoadObstacle obstacle) {
        RoadNetwork net = RoadNetworkStore.base();
        if (net == null) return 0;
        int n = 0;
        for (RoadEdge e : net.edges()) {
            if (!e.pending() && !e.seaRoute() && RouteDetourEngine.intersects(e.geometry(), obstacle)) {
                n++;
            }
        }
        return n;
    }
}
