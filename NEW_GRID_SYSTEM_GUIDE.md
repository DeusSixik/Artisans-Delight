# Новая система генерации узлов (Двухслойная архитектура)

## Обзор

Вместо прямого A* между городами и геометрических проверок развилок (`JunctionEngine`), теперь используется **двухслойная система**:

1. **Грубый слой (Coarse Grid)** — топология сети (КТО с КЕМ соединён)
2. **Тонкий слой (Fine Pathfinding)** — физическая геометрия дорог (КАК проходит дорога)

---

## Ключевые преимущества

✅ **Детерминированность:** Две дороги через одно ребро получают одинаковую геометрию  
✅ **Нет развилок-призраков:** Развилки создаются естественно на грубом слое  
✅ **Простота:** Нет проверок углов, ширины коридоров, снапов  
✅ **Производительность:** Кэш геометрии + сэмплирование шума без загрузки чанков  
✅ **Расширяемость:** API для сторонних модов  

---

## Архитектура модулей

### 1. **Sector** — Сетка секций мира
```
Сектор = 6×6 чанков (96×96 блоков)
Каждый сектор содержит один детерминированный вейпоинт
```

**Файл:** `common/.../generation/grid/Sector.java`

**Пример:**
```java
Sector sector = Sector.fromWorldPos(x, z);
BlockPos center = sector.centerBlock();
```

---

### 2. **SectorWaypointPicker** — Детерминированный выбор вейпоинта

Для каждого сектора генерирует N кандидатов (по хешу сида), выбирает точку с **минимальной стоимостью местности**.

**Критерии оценки:**
- ❌ Глубокая вода (> 6 блоков) — блокирует сектор
- 🔶 Реки и мелководье — штраф (ищет узкие броды)
- 🔶 Крутые склоны — экспоненциальный штраф
- 🔶 Высокие горы (> 100 Y) — линейный штраф

**Файл:** `common/.../generation/grid/SectorWaypointPicker.java`

**Пример:**
```java
BlockPos waypoint = SectorWaypointPicker.pickWaypoint(
    sector, worldSeed, gen, h, state
);
// Возвращает null, если сектор непроходим (океан, горный массив)
```

---

### 3. **CoarseGraphBuilder** — Построение топологии

Строит MST (Minimum Spanning Tree) между городами и создаёт **цепочки промежуточных узлов**.

**Правила:**
- Города НЕ соединяются напрямую
- Между городами создаётся цепочка узлов каждые **400 блоков**
- Минимум **1 узел** между любыми городами (даже близкими)

**Файл:** `common/.../generation/grid/CoarseGraphBuilder.java`

**Пример:**
```java
List<WorldObject> towns = manager.getAllByType(KnotType.TOWN);
CoarseGraphBuilder.buildNetwork(towns, manager, worldSeed, gen, h, state);

// Результат:
// Town A -> Waypoint1 -> Waypoint2 -> Town B
```

**Конфигурация:**
```java
CoarseGraphBuilder.NODE_SPACING = 400;          // Узел каждые 400 блоков
CoarseGraphBuilder.MIN_NODES_BETWEEN_TOWNS = 1; // Минимум узлов между городами
```

---

### 4. **FinePathfinder** — Детальный A* для геометрии

Строит физический путь между **соседними вейпоинтами** (не между городами напрямую!).

**Особенности:**
- Шаг сетки: **8 блоков** (детальнее, чем грубый слой)
- Кэш по ключу `(seed, A, B)` — повторные вызовы мгновенны
- Обходит реки, обрывы, крутые склоны

**Файл:** `common/.../generation/grid/FinePathfinder.java`

**Пример:**
```java
BlockPos waypointA = new BlockPos(100, 64, 200);
BlockPos waypointB = new BlockPos(500, 64, 600);

List<RoadPoint2D> path = FinePathfinder.findPath(
    waypointA, waypointB, worldSeed, gen, h, state
);

// path содержит ломаную линию, огибающую препятствия
```

**Конфигурация:**
```java
FinePathfinder.GRID_STEP = 8;           // Шаг сетки A* (в блоках)
FinePathfinder.MAX_ITERATIONS = 10000;  // Защита от зависания
```

---

### 5. **NetworkMigrationAdapter** — Адаптер для плавной миграции

Мост между старой системой (`KnotNetworkGraph`) и новой.

**Файл:** `common/.../generation/grid/NetworkMigrationAdapter.java`

**Режим 1: Полная миграция (рекомендуется)**
```java
// В KnotNetworkGraph.rebuildNetwork():
NetworkMigrationAdapter.buildNetworkV2(
    knots, manager, worldSeed, gen, h, state
);
```

**Режим 2: Гибридный (переходный период)**
```java
// Включение/выключение новой системы
NetworkMigrationAdapter.setNewSystemEnabled(true);

if (NetworkMigrationAdapter.isNewSystemEnabled()) {
    NetworkMigrationAdapter.buildNetworkV2(...);
} else {
    // Старая система
    KnotNetworkGraph.rebuildNetwork(...);
}
```

---

## Интеграция в проект

### Шаг 1: Заменить вызовы в `KnotNetworkGraph.java`

**БЫЛО:**
```java
public static void rebuildNetwork(Collection<WorldObject> knots, ...) {
    List<GraphEdge> validTopology = calculateTopology(knots);
    // ... старая логика
}
```

**СТАЛО:**
```java
public static void rebuildNetwork(Collection<WorldObject> knots,
                                 ChunkGenerator generator,
                                 LevelHeightAccessor heightAccessor,
                                 RandomState randomState,
                                 WorldObjectManager manager) {
    
    // Новая система
    NetworkMigrationAdapter.buildNetworkV2(
        knots, manager, worldSeed, generator, heightAccessor, randomState
    );
}
```

### Шаг 2: Обновить `connectNewKnot`

**БЫЛО:**
```java
public static void connectNewKnot(WorldObject newKnot, ...) {
    // Проверка промежуточных узлов
    // RouteGenerator.connectKnotsAsync
    // JunctionEngine.snapEdgesViaTown
}
```

**СТАЛО:**
```java
public static void connectNewKnot(WorldObject newKnot,
                                 WorldObjectManager manager,
                                 long worldSeed,
                                 ChunkGenerator generator,
                                 LevelHeightAccessor heightAccessor,
                                 RandomState randomState) {
    
    NetworkMigrationAdapter.connectNewKnotV2(
        newKnot, manager, worldSeed, generator, heightAccessor, randomState
    );
}
```

---

## API для сторонних разработчиков

### Регистрация собственных стратегий размещения узлов

Сейчас узлы размещаются через `CoarseGraphBuilder`. Для кастомных правил:

```java
// Пример: Размещение узла в лесу
public static BlockPos findForestNode(int centerX, int centerZ, 
                                     ChunkGenerator gen, 
                                     LevelHeightAccessor h, 
                                     RandomState state) {
    
    Random rng = new Random(hashCoords(centerX, centerZ));
    
    for (int attempt = 0; attempt < 32; attempt++) {
        int x = centerX + rng.nextInt(200) - 100;
        int z = centerZ + rng.nextInt(200) - 100;
        
        int surfaceY = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, h, state);
        
        Holder<Biome> biome = gen.getBiomeSource().getNoiseBiome(
            QuartPos.fromBlock(x), 
            QuartPos.fromBlock(surfaceY), 
            QuartPos.fromBlock(z), 
            state.sampler()
        );
        
        if (biome.is(BiomeTags.IS_FOREST)) {
            return new BlockPos(x, surfaceY, z);
        }
    }
    
    return null;
}
```

### Подключение добычного узла

```java
WorldObject sawmill = manager.createObject();
sawmill.add(new KnotMetaComponent(KnotType.WAYPOINT, forestPos));
sawmill.add(new KnotRoutesComponent());
sawmill.add(new KnotConnectionComponent());

// Подключается к ближайшему городу автоматически
NetworkMigrationAdapter.connectNewKnotV2(sawmill, manager, worldSeed, gen, h, state);
```

---

## Конфигурация

Все параметры доступны через **публичные константы**:

```java
// Размер сектора
Sector.SECTOR_SIZE_CHUNKS = 6; // 6×6 чанков

// Количество кандидатов для выбора вейпоинта
SectorWaypointPicker.CANDIDATES = 12;

// Расстояние между узлами
CoarseGraphBuilder.NODE_SPACING = 400; // блоков

// Минимум узлов между городами
CoarseGraphBuilder.MIN_NODES_BETWEEN_TOWNS = 1;

// Шаг сетки детального A*
FinePathfinder.GRID_STEP = 8; // блоков
```

---

## Удаление старого кода (после тестирования)

После подтверждения работоспособности удалить:

1. ❌ `JunctionEngine.java` — заменён грубым слоем
2. ❌ `RouteBundler.java` — больше не нужен (развилки создаются естественно)
3. ⚠️ `RouteGenerator.java` — упростить (оставить только `disconnectKnots`)

---

## Тестирование

1. **Компиляция:** Скомпилируй через IDE (IntelliJ IDEA)
2. **Запуск игры:** Создай новый мир с известным сидом
3. **Открой карту:** Нажми `M` (или горячая клавиша карты)
4. **Ctrl+R:** Пересчитай сеть — узлы должны появиться между городами
5. **Проверь логи:** Должны исчезнуть ошибки `[JunctionEngine DEBUG]`

---

## FAQ

**Q: Почему узлы между городами не появляются?**  
A: Проверь, что `NetworkMigrationAdapter.USE_NEW_SYSTEM = true` и вызывается `buildNetworkV2`.

**Q: Можно ли настроить плотность узлов?**  
A: Да, измени `CoarseGraphBuilder.NODE_SPACING` (меньше = больше узлов).

**Q: Как добавить узлы в конкретных биомах (лес, горы)?**  
A: Используй пример из секции "API для сторонних разработчиков" с `getBiomeSource().getNoiseBiome()`.

**Q: Будут ли конфликты со старой системой?**  
A: Нет, если включен `USE_NEW_SYSTEM = true`, старая система не вызывается.

---

## Следующие шаги

1. ✅ Скомпилируй проект через IDE
2. ⏳ Протестируй в игре (создай новый мир)
3. ⏳ Проверь, что узлы создаются между городами
4. ⏳ Удали `JunctionEngine.java` после успешного теста
5. ⏳ Добавь конфигурацию через JSON (опционально)
