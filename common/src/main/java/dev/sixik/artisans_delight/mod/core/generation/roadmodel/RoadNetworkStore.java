package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

/**
 * Глобальное хранилище последней посчитанной виртуальной сети.
 *
 * <p>Хранит две сети:
 * <ul>
 *   <li><b>базовую</b> — как её отдали построитель и стример;</li>
 *   <li><b>текущую</b> — базовую с наложенным слоем (объезды препятствий,
 *       см. {@link #overlay(UnaryOperator)}). Её читают карта и укладка.</li>
 * </ul>
 * Слой можно пересчитать без новой базовой сети ({@link #republish()}) —
 * так препятствие появляется/снимается без пересборки и без потери дорог.
 *
 * <p>Потокобезопасно: публикация синхронизирована (иначе запоздалый
 * {@code republish} со старой базой мог бы перетереть свежую сеть),
 * читатели видят сеть целиком через volatile-ссылку.
 *
 * <p>Ревизия ({@link #revision()}) монотонно растёт при каждой публикации —
 * карта и укладка по ней понимают, что сеть изменилась.
 */
public final class RoadNetworkStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(RoadNetworkStore.class);

    private static final Object LOCK = new Object();
    private static volatile RoadNetwork base;
    private static volatile RoadNetwork current;
    private static final AtomicLong REVISION = new AtomicLong(0);
    private static volatile UnaryOperator<RoadNetwork> overlay = UnaryOperator.identity();

    private RoadNetworkStore() {
    }

    /**
     * Задаёт слой, накладываемый на базовую сеть при каждой публикации.
     * Должен быть быстрым и не бросать исключений (при ошибке публикуется база).
     */
    public static void overlay(UnaryOperator<RoadNetwork> op) {
        overlay = op == null ? UnaryOperator.identity() : op;
    }

    /** Опубликовать новую базовую сеть (целиком заменяет предыдущую). */
    public static long publish(RoadNetwork network) {
        synchronized (LOCK) {
            base = network;
            current = applyOverlay(network);
            return REVISION.incrementAndGet();
        }
    }

    /** Пересчитать слой поверх текущей базовой сети (препятствия изменились). */
    public static long republish() {
        synchronized (LOCK) {
            if (base == null) {
                return REVISION.get();
            }
            current = applyOverlay(base);
            return REVISION.incrementAndGet();
        }
    }

    /** Последняя опубликованная сеть со слоем или {@code null}, если расчёта ещё не было. */
    public static RoadNetwork current() {
        return current;
    }

    /** Базовая сеть без слоя (для отладки/сравнения). */
    public static RoadNetwork base() {
        return base;
    }

    /** Ревизия последней публикации (0 — ничего не опубликовано). */
    public static long revision() {
        return REVISION.get();
    }

    /** Очистить хранилище (смена мира, выход). */
    public static void clear() {
        synchronized (LOCK) {
            base = null;
            current = null;
            REVISION.incrementAndGet();
        }
    }

    private static RoadNetwork applyOverlay(RoadNetwork network) {
        if (network == null) {
            return null;
        }
        try {
            RoadNetwork out = overlay.apply(network);
            return out == null ? network : out;
        } catch (Throwable t) {
            LOGGER.warn("[RoadNetworkStore] Ошибка слоя сети, публикую базу: {}", t.toString());
            return network;
        }
    }
}
