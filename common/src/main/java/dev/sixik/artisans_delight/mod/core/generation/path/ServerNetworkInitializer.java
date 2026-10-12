package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetwork;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetworkBuilder;
import dev.sixik.artisans_delight.mod.core.generation.roadmodel.RoadNetworkStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Серверный инициализатор дорожной сети.
 * 
 * <p>Автоматически запускает генерацию промежуточных узлов и дорог в фоновом потоке
 * при загрузке мира на сервере. Это предотвращает зависание UI при открытии карты.
 * 
 * <p><b>Архитектура:</b>
 * <ol>
 *   <li>При загрузке ServerLevel запускается асинхронная генерация</li>
 *   <li>Клиент проверяет готовность через {@link #isNetworkReady(ServerLevel)}</li>
 *   <li>Если сеть не готова — показывается индикатор загрузки</li>
 *   <li>После завершения генерации клиент автоматически обновляет карту</li>
 * </ol>
 */
public final class ServerNetworkInitializer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerNetworkInitializer.class);
    
    /**
     * Пул потоков для асинхронной генерации.
     * Используется только 1 поток, чтобы не перегружать сервер.
     */
    private static final ExecutorService INITIALIZER_EXECUTOR = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "ArtisansDelight-NetworkInit");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1); // Низкий приоритет
        return t;
    });
    
    /**
     * Кэш состояния генерации для каждого измерения.
     * Key: dimension resource key string
     * Value: CompletableFuture<Void> (завершается, когда генерация готова)
     */
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> GENERATION_FUTURES = new ConcurrentHashMap<>();
    
    /**
     * Кэш запросов на пересборку сети (debounce).
     * Позволяет объединить множество запросов при генерации чанков в один пересчёт.
     */
    private static final ConcurrentHashMap<String, CompletableFuture<Void>> REBUILD_FUTURES = new ConcurrentHashMap<>();
    
    /**
     * Задержка перед пересборкой сети после появления нового города (мс).
     * Даёт время нескольким деревням сгенерироваться и объединяет их в один пересчёт.
     */
    private static final long REBUILD_DEBOUNCE_MS = 5000L;
    
    /**
     * Количество городов на момент последней пересборки (по измерению).
     * Если число не изменилось — пересборка не нужна (защита от лишней работы).
     */
    private static final ConcurrentHashMap<String, Integer> LAST_REBUILD_TOWN_COUNT = new ConcurrentHashMap<>();
    
    private ServerNetworkInitializer() {
    }
    
    /**
     * Запускает асинхронную инициализацию сети для ServerLevel.
     * 
     * <p>Вызывается автоматически при первом доступе к WorldObjectManager.
     * Безопасен для повторных вызовов — генерация произойдёт только один раз.
     * 
     * @param level Серверный мир
     */
    public static void initializeNetworkAsync(ServerLevel level) {
        String dimensionKey = level.dimension().location().toString();
        WorldObjectManager manager = WorldObjectManagerApi.getManager(level);
        
        if (manager == null) {
            LOGGER.warn("[ServerNetworkInitializer] WorldObjectManager не найден для измерения {}", dimensionKey);
            return;
        }
        
        // Проверяем, не запущена ли уже генерация
        if (manager.isNetworkInitialized()) {
            LOGGER.debug("[ServerNetworkInitializer] Сеть уже инициализирована для {}", dimensionKey);
            return;
        }
        
        // Создаём или получаем существующий Future
        CompletableFuture<Void> future = GENERATION_FUTURES.computeIfAbsent(dimensionKey, key -> {
            LOGGER.info("[ServerNetworkInitializer] Запускаю асинхронную генерацию сети для {}...", dimensionKey);
            
            return CompletableFuture.runAsync(() -> {
                try {
                    GenerationProgress.begin();

                    // 1. Предсказываем узлы в большом радиусе (центр мира)
                    // Города регистрируются в менеджере как раньше — это игровые
                    // объекты (склады, дома, CP), они нужны миру.
                    ChunkGenerator generator = level.getChunkSource().getGenerator();
                    RandomState randomState = level.getChunkSource().randomState();
                    long worldSeed = level.getChunkSource().getGeneratorState().getLevelSeed();
                    BlockPos center = BlockPos.ZERO; // Центр мира

                    long startTime = System.currentTimeMillis();
                    GenerationProgress.setStage(GenerationProgress.Stage.LOOKAHEAD);
                    LOGGER.info("[ServerNetworkInitializer] Поиск поселений в радиусе 5000 блоков от центра мира...");

                    SeedStructureLookahead.predictKnotsInRange(manager, level, generator, randomState, center, 5000);

                    long lookupTime = System.currentTimeMillis() - startTime;
                    LOGGER.info("[ServerNetworkInitializer] Поиск поселений завершён за {} мс", lookupTime);

                    // 2. Собираем города и считаем ВИРТУАЛЬНУЮ сеть.
                    // Промежуточные узлы и дороги НЕ пишутся в менеджер и НЕ ставят
                    // блоки — только расчёт в RoadNetwork + публикация в Store.
                    // Интеграция в мир (NetworkApplier) — отдельный этап.
                    List<RoadNetworkBuilder.TownSeed> townSeeds = collectTownSeeds(manager, worldSeed);

                    if (townSeeds.isEmpty()) {
                        LOGGER.warn("[ServerNetworkInitializer] Поселения не найдены — пропускаю генерацию сети");
                        GenerationProgress.finish();
                        manager.markNetworkInitialized();
                        return;
                    }

                    LOGGER.info("[ServerNetworkInitializer] Запускаю виртуальный расчёт дорожной сети ({} городов)...", townSeeds.size());
                    long netStartTime = System.currentTimeMillis();

                    RoadNetwork network = RoadNetworkBuilder.build(
                        townSeeds, worldSeed, generator, level, randomState
                    );
                    RoadNetworkStore.publish(network);

                    long netTime = System.currentTimeMillis() - netStartTime;
                    long totalTime = System.currentTimeMillis() - startTime;

                    LOGGER.info("[ServerNetworkInitializer] ✓ Виртуальная сеть посчитана за {} мс (поиск: {} мс, сеть: {} мс): {} узлов, {} рёбер",
                        totalTime, lookupTime, netTime, network.nodeCount(), network.edgeCount());

                    // Запоминаем число городов, чтобы не пересчитывать сеть без изменений
                    LAST_REBUILD_TOWN_COUNT.put(dimensionKey, townSeeds.size());

                    GenerationProgress.finish();
                    manager.markNetworkInitialized();

                } catch (Exception e) {
                    LOGGER.error("[ServerNetworkInitializer] Ошибка при генерации сети для {}", dimensionKey, e);
                    // Помечаем как инициализированную, чтобы не зависать в бесконечном ожидании
                    GenerationProgress.finish();
                    manager.markNetworkInitialized();
                }
            }, INITIALIZER_EXECUTOR);
        });
        
        LOGGER.debug("[ServerNetworkInitializer] Генерация для {} в процессе...", dimensionKey);
    }
    
    /**
     * Запрашивает отложенную пересборку сети после появления нового города.
     * 
     * <p>Вызывается из mixin генерации структур, когда деревня физически создалась
     * в мире ПОСЛЕ первичной инициализации сети. Без пересборки новый город
     * подключался бы старой системой напрямую к соседям (прямые линии город-город).
     * 
     * <p><b>Debounce:</b> несколько запросов подряд (несколько деревень за секунды)
     * объединяются в ОДНУ пересборку через {@link #REBUILD_DEBOUNCE_MS} мс после
     * последнего запроса. Пересборка выполняется в фоновом потоке и не блокирует
     * генерацию чанков.
     * 
     * @param level Серверный мир
     */
    public static void requestNetworkRebuild(ServerLevel level) {
        if (level == null) return;
        
        String dimensionKey = level.dimension().location().toString();
        WorldObjectManager manager = WorldObjectManagerApi.getManager(level);
        if (manager == null || !manager.isNetworkInitialized()) {
            // Сеть ещё не построена — первичная генерация подхватит новый город сама
            return;
        }
        
        REBUILD_FUTURES.compute(dimensionKey, (key, existing) -> {
            if (existing != null && !existing.isDone()) {
                // Пересборка уже запланирована — не дублируем
                return existing;
            }
            
            LOGGER.info("[ServerNetworkInitializer] Запланирована пересборка сети через {} мс ({})", 
                REBUILD_DEBOUNCE_MS, dimensionKey);
            
            return CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(REBUILD_DEBOUNCE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                
                try {
                    // Считаем текущее число городов
                    List<RoadNetworkBuilder.TownSeed> townSeeds = collectTownSeeds(manager, 0L);
                    int townCount = townSeeds.size();

                    Integer lastCount = LAST_REBUILD_TOWN_COUNT.get(dimensionKey);
                    if (lastCount != null && lastCount == townCount) {
                        LOGGER.debug("[ServerNetworkInitializer] Число городов не изменилось ({}), пересборка не нужна", townCount);
                        return;
                    }

                    ChunkGenerator generator = level.getChunkSource().getGenerator();
                    RandomState randomState = level.getChunkSource().randomState();
                    long worldSeed = level.getChunkSource().getGeneratorState().getLevelSeed();

                    LOGGER.info("[ServerNetworkInitializer] Пересчитываю виртуальную сеть с учётом новых городов ({} шт.)...", townCount);
                    GenerationProgress.begin();

                    // Пересобираем TownSeed с реальным сидом (id должны быть детерминированы)
                    townSeeds = collectTownSeeds(manager, worldSeed);
                    RoadNetwork network = RoadNetworkBuilder.build(
                        townSeeds, worldSeed, generator, level, randomState
                    );
                    RoadNetworkStore.publish(network);

                    LAST_REBUILD_TOWN_COUNT.put(dimensionKey, townCount);
                    GenerationProgress.finish();
                    LOGGER.info("[ServerNetworkInitializer] Rebuild done: {} nodes, {} edges",
                        network.nodeCount(), network.edgeCount());
                } catch (Exception e) {
                    LOGGER.error("[ServerNetworkInitializer] Ошибка пересборки сети для {}", dimensionKey, e);
                    GenerationProgress.finish();
                }
            }, INITIALIZER_EXECUTOR);
        });
    }
    
    /**
     * Собирает города из менеджера в плоские {@link RoadNetworkBuilder.TownSeed}.
     *
     * <p>Города остаются игровыми объектами в менеджере (склады, дома, CP) —
     * сюда забираются только данные для расчёта: позиция и имя.
     *
     * @param worldSeed сид мира для детерминированных id (0 — временный подсчёт числа)
     */
    public static List<RoadNetworkBuilder.TownSeed> collectTownSeeds(WorldObjectManager manager, long worldSeed) {
        List<RoadNetworkBuilder.TownSeed> result = new ArrayList<>();
        if (manager == null) return result;
        for (WorldObject obj : manager.all()) {
            var meta = obj.getSingle(dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent.class).orElse(null);
            if (meta == null) continue;
            if (meta.type.get() != dev.sixik.artisans_delight.mod.core.knot.KnotType.TOWN) continue;
            BlockPos pos = meta.position.get();
            if (pos == null) continue;
            java.util.UUID id = worldSeed != 0L
                ? RoadNetworkBuilder.townId(worldSeed, pos.getX(), pos.getZ())
                : obj.id();
            result.add(new RoadNetworkBuilder.TownSeed(id, obj.id(), meta.name.get(), pos.getX(), pos.getY(), pos.getZ()));
        }
        return result;
    }

    /**
     * Проверяет, завершена ли генерация сети для данного мира.
     * 
     * @param level Серверный мир
     * @return true, если сеть готова к использованию
     */
    public static boolean isNetworkReady(ServerLevel level) {
        WorldObjectManager manager = WorldObjectManagerApi.getManager(level);
        return manager != null && manager.isNetworkInitialized();
    }
    
    /**
     * Ожидает завершения генерации сети (блокирующий вызов).
     * 
     * <p><b>ВНИМАНИЕ:</b> Используйте только для дебага!
     * В продакшене используйте {@link #isNetworkReady} для асинхронной проверки.
     * 
     * @param level Серверный мир
     * @param timeoutMs Максимальное время ожидания (миллисекунды)
     * @return true, если сеть готова; false, если превышен таймаут
     */
    public static boolean waitForNetwork(ServerLevel level, long timeoutMs) {
        String dimensionKey = level.dimension().location().toString();
        CompletableFuture<Void> future = GENERATION_FUTURES.get(dimensionKey);
        
        if (future == null) {
            // Генерация не запущена — запускаем сейчас
            initializeNetworkAsync(level);
            future = GENERATION_FUTURES.get(dimensionKey);
        }
        
        if (future == null) {
            return false; // Не удалось запустить генерацию
        }
        
        try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[ServerNetworkInitializer] Таймаут ожидания генерации сети для {}", dimensionKey);
            return false;
        }
    }
    
    /**
     * Очищает кэш генерации при выгрузке мира.
     * 
     * @param level Серверный мир
     */
    public static void cleanup(ServerLevel level) {
        String dimensionKey = level.dimension().location().toString();
        GENERATION_FUTURES.remove(dimensionKey);
        LOGGER.debug("[ServerNetworkInitializer] Очищен кэш генерации для {}", dimensionKey);
    }
}
