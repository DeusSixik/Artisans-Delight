package dev.sixik.artisans_delight.libs.world.demo;

import dev.sixik.artisans_delight.libs.world.SingleThreadedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.TickableComponent;
import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectComponentType;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.sync.Field;
import dev.sixik.artisans_delight.libs.world.sync.FieldCodecs;
import dev.sixik.artisans_delight.libs.world.sync.SyncedWorldObjectComponent;
import dev.sixik.artisans_delight.libs.world.sync.TrackedField;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Минимальный пример использования WorldComponent.
 *
 * <p>Три шага: объявить компонент с полями, прицепить его к объекту, читать/писать.
 * Запуск: {@code WorldComponentDemo.main()} печатает каждый шаг в консоль.
 */
public final class WorldComponentDemo {

    private WorldComponentDemo() {
    }

    /**
     * 1. Синхронизированный компонент — данные + логика. Сетевые поля объявляются
     * через {@code tracked()} и потом меняются только через хэндл:
     * {@code count.get()} / {@code count.set()}.
     */
    public static final class CounterComponent extends SyncedWorldObjectComponent implements TickableComponent {

        public final TrackedField<Integer> count = tracked("count", FieldCodecs.intValue(), 0);

        @Override
        public void onTick(long tick) {
            count.update(value -> value + 1);
        }
    }

    /**
     * 2. Тип — паспорт компонента: стабильный id и политика дубликатов.
     * REJECT запрещает два счётчика на одном объекте.
     */
    public static final WorldObjectComponentType<CounterComponent> COUNTER_TYPE =
            new WorldObjectComponentType<>("demo:counter", CounterComponent.class,
                    WorldObjectComponentType.DuplicatePolicy.REJECT, CounterComponent::new);

    /**
     * 2b. Однопоточный компонент: лёгкие Field-поля без локов и авто-NBT, сеть отсутствует.
     * Каждый публичный метод начинается с {@code checkThread()} — чужой поток
     * сразу падает с понятной ошибкой вместо молчаливой гонки.
     */
    public static final class SearchCacheComponent extends SingleThreadedWorldObjectComponent {

        private final Field<Integer> queries = field("queries", FieldCodecs.intValue(), 0);
        private final Field<ObjectList<String>> hits =
                field("hits", FieldCodecs.listOf(FieldCodecs.string()), ObjectLists.emptyList());

        public void recordQuery(String query) {
            checkThread();
            queries.update(value -> value + 1);
            if (query != null && query.length() > 2) {
                hits.update(current -> {
                    ObjectList<String> next = new ObjectArrayList<>(current);
                    next.add(query);
                    return next;
                });
            }
        }

        public int queries() {
            checkThread();
            return queries.get();
        }

        public ObjectList<String> hits() {
            checkThread();
            return hits.get();
        }
    }

    public static final WorldObjectComponentType<SearchCacheComponent> CACHE_TYPE =
            new WorldObjectComponentType<>("demo:search-cache", SearchCacheComponent.class,
                    WorldObjectComponentType.DuplicatePolicy.REJECT, SearchCacheComponent::new);

    /**
     * 3. Использование: менеджер -&gt; объект -&gt; attach -&gt; работа с полями.
     */
    public static void main(String[] args) {
        WorldObjectManager manager = new WorldObjectManager();
        try {
            WorldObject object = manager.create();
            CounterComponent server = object.attach(new CounterComponent(), COUNTER_TYPE);

            // Обычная работа: set/get, чтение потокобезопасно с любого потока.
            server.count.set(5);
            System.out.println("count = " + server.count.get());

            // Тики: менеджер тикает все объекты, компонент тикает свои поля.
            manager.tickAll();
            System.out.println("after tick = " + server.count.get());

            // Поиск компонента на объекте.
            CounterComponent found = object.getSingle(COUNTER_TYPE).orElseThrow();
            System.out.println("same instance = " + (found == server));

            // Сейв/загрузка: все поля — в NBT одной строкой.
            CompoundTag nbt = new CompoundTag();
            server.writeNbt(nbt);
            CounterComponent restored = new CounterComponent();
            restored.readNbt(nbt);
            System.out.println("restored = " + restored.count.get());

            // Сеть, первое подключение: полный синк + запоминаем ревизии.
            CounterComponent client = new CounterComponent();
            SyncedWorldObjectComponent.DeltaTracker serverView = new SyncedWorldObjectComponent.DeltaTracker();
            SyncedWorldObjectComponent.DeltaTracker clientView = new SyncedWorldObjectComponent.DeltaTracker();
            FriendlyByteBuf full = new FriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer());
            server.writeFull(full);
            clientView.reset(client.readFullWithRevisions(full));
            System.out.println("client after full = " + client.count.get());

            // Сеть, дальше: шлём только изменившееся. Не менялось — тишина.
            server.count.set(42);
            FriendlyByteBuf delta = new FriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer());
            if (server.writeDelta(delta, serverView)) {
                client.readDelta(delta, clientView);
            }
            System.out.println("client after delta = " + client.count.get());

            // Однопоточный компонент: те же поля, NBT автоматический, checkThread в методах.
            SearchCacheComponent cache = object.attach(new SearchCacheComponent(), CACHE_TYPE);
            cache.recordQuery("iron");
            cache.recordQuery("go");
            System.out.println("cache queries = " + cache.queries() + ", hits = " + cache.hits());
            CompoundTag cacheNbt = new CompoundTag();
            cache.writeNbt(cacheNbt);
            SearchCacheComponent cacheRestored = new SearchCacheComponent();
            cacheRestored.readNbt(cacheNbt);
            System.out.println("cache restored queries = " + cacheRestored.queries());
        } finally {
            manager.close();
        }
    }
}
