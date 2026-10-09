package dev.sixik.artisans_delight.libs.world;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

/**
 * Single-threaded base for {@link WorldObjectComponent} implementations.
 *
 * <p>Однопоточный аналог {@code SyncedWorldObjectComponent}: декларативные
 * {@link dev.sixik.artisans_delight.libs.world.sync.Field}-поля через {@link #field}
 * (без блокировок и ревизий) и та же автоматическая NBT-персистентность из
 * {@link FieldedWorldObjectComponent}. Сериализация полностью автоматическая:
 * объявили поле — оно само пишется в {@link #writeNbt} и читается в {@link #readNbt}.
 *
 * <p>Нужна сеть — берите {@code SyncedWorldObjectComponent} с {@code tracked()}-полями.
 * Здесь поля только NBT: попытка прицепить компонент с {@code tracked()}-полями
 * ни к чему не привязана, но ревизии в однопотоке бесполезны — используйте
 * {@link #field}.
 *
 * <p>Контракт однопоточности: каждое обращение к состоянию компонента должно идти
 * с конфайнящего потока. Вызывайте {@link #checkThread} в начале каждого мутатора
 * и тика — первый вызов фиксирует поток-привязку, любой другой поток после этого
 * получит {@link IllegalStateException}. Это дешёвая диагностика гонок: вместо
 * молчаливой порчи состояния вы сразу видите чужой поток в стектрейсе.
 *
 * <p>Правила:
 * <ul>
 *   <li>Состояние — только {@code Field}-поля через {@link #field} (авто-NBT,
 *       авто-копии, ноль локов). Сырые мутабельные поля допустимы лишь как
 *       производный кэш, который пересчитывается из полей и никогда не сохраняется.</li>
 *   <li>Не используйте унаследованные {@code withRead}/{@code runWrite} — они для
 *       многопоточных наследников. Здесь поля читаются и пишутся напрямую.</li>
 *   <li>{@link #writeNbt}/{@link #readNbt} финальные и обходят поля сами; данные
 *       вне полей сохраняйте через {@link #writeExtraNbt}/{@link #readExtraNbt}.</li>
 *   <li>Если компонент переприцепляют на объект, живущий на другом потоке,
 *       вызовите {@link #resetThreadAffinity} в своём {@code onDetached}.</li>
 * </ul>
 */
public abstract class SingleThreadedWorldObjectComponent extends FieldedWorldObjectComponent {

    private volatile Thread affinity;

    /**
     * Проверяет, что текущий поток — тот же, что зафиксирован первым вызовом.
     * Первый вызов запоминает поток и ничего не бросает.
     *
     * @throws IllegalStateException если вызов пришёл с чужого потока
     */
    protected final void checkThread() {
        Thread current = Thread.currentThread();
        Thread bound = affinity;
        if (bound == null) {
            affinity = current;
            return;
        }
        if (bound != current) {
            throw new IllegalStateException(
                    getClass().getName() + " is single-threaded (bound to " + bound.getName()
                            + "), called from " + current.getName());
        }
    }

    /**
     * Сбрасывает привязку к потоку. Вызывайте из {@code onDetached}, если компонент
     * может быть переприцеплен на объект другого потока.
     */
    protected final void resetThreadAffinity() {
        affinity = null;
    }

    /**
     * Поток-привязка, если уже зафиксирована первым {@link #checkThread}.
     */
    protected final Optional<Thread> boundThread() {
        return Optional.ofNullable(affinity);
    }

    /**
     * NBT-персистентность автоматическая: обход полей из базового класса
     * плюс {@link #writeExtraNbt}. Финальный — переопределяйте хук, а не метод.
     */
    @Override
    public final void writeNbt(CompoundTag nbt) {
        super.writeNbt(nbt);
    }

    /**
     * NBT-загрузка автоматическая: разбор полей из базового класса
     * плюс {@link #readExtraNbt}. Финальный — переопределяйте хук, а не метод.
     */
    @Override
    public final void readNbt(CompoundTag nbt) {
        super.readNbt(nbt);
    }
}
