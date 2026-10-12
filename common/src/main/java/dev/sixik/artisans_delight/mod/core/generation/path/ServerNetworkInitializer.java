package dev.sixik.artisans_delight.mod.core.generation.path;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
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
                    ChunkGenerator generator = level.getChunkSource().getGenerator();
                    RandomState randomState = level.getChunkSource().randomState();
                    long worldSeed = level.getChunkSource().getGeneratorState().getLevelSeed();
                    BlockPos center = BlockPos.ZERO; // Центр мира
                    
                    long startTime = System.currentTimeMillis();
                    GenerationProgress.setStage(GenerationProgress.Stage.LOOKAHEAD);
                    LOGGER.info("[ServerNetworkInitializer] Поиск поселений в радиусе 5000 блоков от центра мира...");
                    
                    SeedStructureLookahead.predictKnotsInRange(manager, level, generator, randomState, center, 5000);
                    Collection<WorldObject> knots = manager.all();
                    
                    long lookupTime = System.currentTimeMillis() - startTime;
                    LOGGER.info("[ServerNetworkInitializer] Найдено {} поселений за {} мс", knots.size(), lookupTime);
                    
                    if (knots.isEmpty()) {
                        LOGGER.warn("[ServerNetworkInitializer] Поселения не найдены — пропускаю генерацию сети");
                        GenerationProgress.finish();
                        manager.markNetworkInitialized();
                        return;
                    }
                    
                    // 2. Строим сеть через новую систему
                    LOGGER.info("[ServerNetworkInitializer] Запускаю генерацию дорожной сети...");
                    long netStartTime = System.currentTimeMillis();
                    
                    KnotNetworkGraph.rebuildNetwork(knots, generator, level, randomState, manager, worldSeed);
                    
                    long netTime = System.currentTimeMillis() - netStartTime;
                    long totalTime = System.currentTimeMillis() - startTime;
                    
                    LOGGER.info("[ServerNetworkInitializer] ✓ Генерация сети завершена за {} мс (поиск: {} мс, сеть: {} мс)", 
                        totalTime, lookupTime, netTime);
                    
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
