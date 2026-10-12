package dev.sixik.artisans_delight.mod.core.generation.path;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Глобальный трекер прогресса генерации дорожной сети.
 *
 * <p>Позволяет UI (карте мира) отображать текущий этап и процент выполнения
 * фоновой генерации, которая идёт в потоке {@link ServerNetworkInitializer}.
 *
 * <p><b>Этапы генерации:</b>
 * <ol>
 *   <li>{@link Stage#IDLE} — генерация не запущена</li>
 *   <li>{@link Stage#LOOKAHEAD} — поиск поселений (Seed Lookahead)</li>
 *   <li>{@link Stage#COARSE_GRAPH} — построение грубого графа (создание узлов)</li>
 *   <li>{@link Stage#SEA_ROUTES} — построение морских маршрутов</li>
 *   <li>{@link Stage#FINE_GEOMETRY} — построение детальной геометрии дорог</li>
 *   <li>{@link Stage#DONE} — генерация завершена</li>
 * </ol>
 *
 * <p><b>Потокобезопасность:</b> Все поля атомарные. Писатель — фоновый поток
 * генерации, читатель — Render thread (UI).
 */
public final class GenerationProgress {

    /**
     * Этапы генерации дорожной сети.
     */
    public enum Stage {
        IDLE("Ожидание"),
        LOOKAHEAD("Поиск поселений"),
        COARSE_GRAPH("Построение узлов"),
        SEA_ROUTES("Морские маршруты"),
        FINE_GEOMETRY("Геометрия дорог"),
        DONE("Готово");

        private final String displayName;

        Stage(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private static final AtomicReference<Stage> CURRENT_STAGE = new AtomicReference<>(Stage.IDLE);
    private static final AtomicInteger CURRENT = new AtomicInteger(0);
    private static final AtomicInteger TOTAL = new AtomicInteger(0);
    private static final AtomicReference<String> DETAIL = new AtomicReference<>("");
    private static final AtomicReference<Long> START_TIME = new AtomicReference<>(0L);

    private GenerationProgress() {
    }

    /**
     * Начинает новый сеанс генерации: сбрасывает счётчики и запоминает время старта.
     */
    public static void begin() {
        CURRENT_STAGE.set(Stage.LOOKAHEAD);
        CURRENT.set(0);
        TOTAL.set(0);
        DETAIL.set("");
        START_TIME.set(System.currentTimeMillis());
    }

    /**
     * Переключает текущий этап генерации и сбрасывает прогресс шага.
     *
     * @param stage Новый этап
     */
    public static void setStage(Stage stage) {
        CURRENT_STAGE.set(stage);
        CURRENT.set(0);
        TOTAL.set(0);
        DETAIL.set("");
    }

    /**
     * Устанавливает прогресс текущего этапа.
     *
     * @param current Текущий шаг (1-based)
     * @param total   Всего шагов
     * @param detail  Дополнительное описание (например, "A -> B")
     */
    public static void setProgress(int current, int total, String detail) {
        CURRENT.set(current);
        TOTAL.set(total);
        DETAIL.set(detail == null ? "" : detail);
    }

    /**
     * Помечает генерацию завершённой.
     */
    public static void finish() {
        CURRENT_STAGE.set(Stage.DONE);
        DETAIL.set("");
    }

    /**
     * Сбрасывает трекер в исходное состояние (при выгрузке мира).
     */
    public static void reset() {
        CURRENT_STAGE.set(Stage.IDLE);
        CURRENT.set(0);
        TOTAL.set(0);
        DETAIL.set("");
        START_TIME.set(0L);
    }

    public static Stage stage() {
        return CURRENT_STAGE.get();
    }

    public static int current() {
        return CURRENT.get();
    }

    public static int total() {
        return TOTAL.get();
    }

    public static String detail() {
        return DETAIL.get();
    }

    /**
     * @return Прошедшее время с начала генерации в миллисекундах, либо 0, если не запущена.
     */
    public static long elapsedMillis() {
        long start = START_TIME.get();
        return start == 0L ? 0L : System.currentTimeMillis() - start;
    }

    /**
     * @return Прогресс текущего этапа в процентах (0-100), либо 0, если total неизвестен.
     */
    public static int percent() {
        int total = TOTAL.get();
        if (total <= 0) {
            return 0;
        }
        int current = CURRENT.get();
        return Math.max(0, Math.min(100, (int) ((long) current * 100L / total)));
    }

    /**
     * @return {@code true}, если генерация активно идёт.
     */
    public static boolean isRunning() {
        Stage s = CURRENT_STAGE.get();
        return s != Stage.IDLE && s != Stage.DONE;
    }
}
