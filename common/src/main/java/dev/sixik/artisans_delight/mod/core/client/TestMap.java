package dev.sixik.artisans_delight.mod.core.client;

import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotProductionComponent;
import dev.sixik.unigui.api.core.UnityLikeUIScaleProvider;
import dev.sixik.unigui.api.debug.DebugFlags;
import dev.sixik.unigui.api.layout.LayoutConstraints;
import dev.sixik.unigui.api.math.MutableColor;
import dev.sixik.unigui.api.render.DrawScope;
import dev.sixik.unigui.api.render.UiRenderPolicy;
import dev.sixik.unigui.api.widget.Widget;
import dev.sixik.unigui.backend.minecraft_impl.MinecraftClipboardService;
import dev.sixik.unigui.backend.minecraft_impl.MinecraftWidgetScreen;
import dev.sixik.unigui.impl.core.DefaultUIContext;
import dev.sixik.unigui.widgets.containers.VBox;
import dev.sixik.unigui.widgets.display.Label;
import dev.sixik.unigui.widgets.interaction.Button;
import dev.sixik.unigui.widgets.world.WorldCanvas;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class TestMap {

    private static Field serverVisibleChunkMapField;
    private static Field clientStorageField;
    private static Field clientChunksField;
    private static boolean reflectionInitialized = false;

    private static long lastChunkQueryTime = 0;
    private static LongSet cachedLoadedChunks = new LongOpenHashSet();

    public static void openMap() {
        openMap(null, null);
    }

    public static void openMap(WorldObjectManager manager, Collection<WorldObject> knots) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen previous = minecraft.screen;

        // Тестовый режим: если узлы не переданы или пусты, дёргаем сервер напрямую (singleplayer)
        if ((knots == null || knots.isEmpty()) && minecraft.level != null) {
            try {
                if (minecraft.getSingleplayerServer() != null) {
                    var serverLevel = minecraft.getSingleplayerServer().getLevel(minecraft.level.dimension());
                    if (serverLevel != null) {
                        manager = WorldObjectManagerApi.getManager(serverLevel);
                        knots = manager.all();
                    }
                } else {
                    manager = WorldObjectManagerApi.getManager(minecraft.level.dimension(), false);
                    if (manager != null) {
                        knots = manager.all();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        DefaultUIContext context = new DefaultUIContext(new MinecraftClipboardService());
        context.debugFlags(DebugFlags.PROFILER_OVERLAY);
        UnityLikeUIScaleProvider scale = new UnityLikeUIScaleProvider()
                .referenceResolution(1920.0f, 1080.0f)
                .matchBalanced()
                .scaleRange(0.75f, 6.0f)
                .userScale(2.15f);
        context.scaleProvider(scale);

        MinecraftWidgetScreen screen = new MinecraftWidgetScreen(
                Component.literal("Artisans Delight - World Map"),
                root(manager, knots),
                context
        ) {
            @Override
            public void onClose() {
                Minecraft.getInstance().setScreen(previous);
            }

            @Override
            public boolean isPauseScreen() {
                return false;
            }
        };

        screen.useContextScale().scaleWithMinecraftGui(false);
        screen.useSdfDefaultFont();
        screen.renderPolicy(UiRenderPolicy.continuous());
        minecraft.setScreen(screen);
    }

    private static Widget root(WorldObjectManager manager, Collection<WorldObject> knots) {
        Minecraft minecraft = Minecraft.getInstance();

        // Отбираем только объекты с компонентом KnotMetaComponent
        List<WorldObject> knotList = new ArrayList<>();
        if (knots != null) {
            for (WorldObject object : knots) {
                if (object.getSingle(KnotMetaComponent.class).isPresent()) {
                    knotList.add(object);
                }
            }
        }

        // Центрируем камеру: приоритет — игрок, иначе первый узел, иначе 0, 0
        float targetWorldX = 0.0f;
        float targetWorldY = 0.0f;
        if (minecraft.player != null) {
            targetWorldX = (float) minecraft.player.getX();
            targetWorldY = (float) minecraft.player.getZ();
        } else if (!knotList.isEmpty()) {
            BlockPos firstPos = knotList.getFirst().getSingle(KnotMetaComponent.class).get().pos.get();
            targetWorldX = (float) firstPos.getX();
            targetWorldY = (float) firstPos.getZ();
        }

        float initialZoom = 0.35f;
        float screenCenterX = 1920.0f / 2.0f;
        float screenCenterY = 1080.0f / 2.0f;
        float offsetX = screenCenterX - targetWorldX * initialZoom;
        float offsetY = screenCenterY - targetWorldY * initialZoom;

        WorldCanvas canvas = new WorldCanvas()
                .viewport(offsetX, offsetY, initialZoom)
                .zoomRange(0.03f, 4.0f)
                .wheelPanStep(36.0f);
        canvas.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));

        Label status = new Label(String.format(Locale.ROOT,
                "WorldCanvas: knots loaded: %d | Drag empty space, Wheel zoom, Click markers to inspect/invest", knotList.size()));
        status.layout(style -> style.size(LayoutConstraints.AUTO, 22.0f).flexGrow(0.0f).flexShrink(0.0f));

        // Вычисляем псевдо-соединения между узлами (KNN / Proximity Graph)
        List<PseudoEdge> pseudoEdges = buildPseudoEdges(knotList);

        // Фоновый и графический слой карты (сетка, чанки, границы чанков, псевдо-связи, дороги, узлы, игрок)
        canvas.addWorldLayer((world, draw) -> {
            float left = world.layoutBounds().x();
            float top = world.layoutBounds().y();
            float width = world.layoutBounds().width();
            float height = world.layoutBounds().height();

            // Тёмный фон неисследованного мира
            draw.addRectFilled(left, top, width, height, new MutableColor(0.025f, 0.032f, 0.045f, 0.98f));

            float minWorldX = world.rootToWorldX(left);
            float maxWorldX = world.rootToWorldX(left + width);
            float minWorldY = world.rootToWorldY(top);
            float maxWorldY = world.rootToWorldY(top + height);
            float zoom = world.viewport().zoom();

            // === 1. ЗОНА ЗАГРУЖЕННЫХ ЧАНКОВ И ДИНАМИЧЕСКАЯ ГРАНИЦА ===
            LongSet loadedChunks = getLoadedChunks();
            if (!loadedChunks.isEmpty()) {
                // Подсветка исследованной/загруженной территории (если зум не слишком далёкий)
                if (zoom >= 0.08f) {
                    MutableColor chunkTint = new MutableColor(0.065f, 0.14f, 0.25f, 0.22f);
                    for (long packed : loadedChunks) {
                        int cx = ChunkPos.getX(packed);
                        int cz = ChunkPos.getZ(packed);
                        float bx0 = cx * 16.0f;
                        float bx1 = bx0 + 16.0f;
                        float bz0 = cz * 16.0f;
                        float bz1 = bz0 + 16.0f;

                        if (bx1 < minWorldX || bx0 > maxWorldX || bz1 < minWorldY || bz0 > maxWorldY) {
                            continue;
                        }

                        float sx = world.worldToRootX(bx0);
                        float sy = world.worldToRootY(bz0);
                        float sw = 16.0f * zoom;
                        float sh = 16.0f * zoom;
                        draw.addRectFilled(sx, sy, sw, sh, chunkTint);
                    }
                }

                // Граница загруженных чанков (рисуется только на стыке с незагруженными чанками)
                MutableColor glowColor = new MutableColor(0.15f, 0.65f, 1.0f, 0.35f);
                MutableColor borderColor = new MutableColor(0.35f, 0.90f, 1.0f, 0.90f);

                for (long packed : loadedChunks) {
                    int cx = ChunkPos.getX(packed);
                    int cz = ChunkPos.getZ(packed);
                    float bx0 = cx * 16.0f;
                    float bx1 = bx0 + 16.0f;
                    float bz0 = cz * 16.0f;
                    float bz1 = bz0 + 16.0f;

                    if (bx1 < minWorldX - 16 || bx0 > maxWorldX + 16 || bz1 < minWorldY - 16 || bz0 > maxWorldY + 16) {
                        continue;
                    }

                    // Северная граница
                    if (!loadedChunks.contains(ChunkPos.asLong(cx, cz - 1))) {
                        float sx0 = world.worldToRootX(bx0);
                        float sy0 = world.worldToRootY(bz0);
                        float sx1 = world.worldToRootX(bx1);
                        draw.addLine(sx0, sy0, sx1, sy0, glowColor, 4.5f);
                        draw.addLine(sx0, sy0, sx1, sy0, borderColor, 1.8f);
                    }
                    // Южная граница
                    if (!loadedChunks.contains(ChunkPos.asLong(cx, cz + 1))) {
                        float sx0 = world.worldToRootX(bx0);
                        float sy0 = world.worldToRootY(bz1);
                        float sx1 = world.worldToRootX(bx1);
                        draw.addLine(sx0, sy0, sx1, sy0, glowColor, 4.5f);
                        draw.addLine(sx0, sy0, sx1, sy0, borderColor, 1.8f);
                    }
                    // Западная граница
                    if (!loadedChunks.contains(ChunkPos.asLong(cx - 1, cz))) {
                        float sx0 = world.worldToRootX(bx0);
                        float sy0 = world.worldToRootY(bz0);
                        float sy1 = world.worldToRootY(bz1);
                        draw.addLine(sx0, sy0, sx0, sy1, glowColor, 4.5f);
                        draw.addLine(sx0, sy0, sx0, sy1, borderColor, 1.8f);
                    }
                    // Восточная граница
                    if (!loadedChunks.contains(ChunkPos.asLong(cx + 1, cz))) {
                        float sx1 = world.worldToRootX(bx1);
                        float sy0 = world.worldToRootY(bz0);
                        float sy1 = world.worldToRootY(bz1);
                        draw.addLine(sx1, sy0, sx1, sy1, glowColor, 4.5f);
                        draw.addLine(sx1, sy0, sx1, sy1, borderColor, 1.8f);
                    }
                }
            }

            // === 2. АДАПТИВНАЯ КООРДИНАТНАЯ СЕТКА ===
            float step = 100.0f;
            if (zoom < 0.15f) step = 1000.0f;
            else if (zoom < 0.45f) step = 500.0f;
            else if (zoom < 1.0f) step = 200.0f;

            float startX = (float) (Math.floor(Math.min(minWorldX, maxWorldX) / step) * step);
            float endX = (float) (Math.ceil(Math.max(minWorldX, maxWorldX) / step) * step);
            float startY = (float) (Math.floor(Math.min(minWorldY, maxWorldY) / step) * step);
            float endY = (float) (Math.ceil(Math.max(minWorldY, maxWorldY) / step) * step);

            MutableColor gridColor = new MutableColor(0.18f, 0.25f, 0.36f, 0.25f);
            for (float x = startX; x <= endX; x += step) {
                float sx = world.worldToRootX(x);
                draw.addLine(sx, top, sx, top + height, gridColor, 1.0f);
            }
            for (float y = startY; y <= endY; y += step) {
                float sy = world.worldToRootY(y);
                draw.addLine(left, sy, left + width, sy, gridColor, 1.0f);
            }

            // Быстрый кэш позиций и инвестиций
            Map<UUID, BlockPos> posMap = new HashMap<>();
            Map<UUID, Boolean> investedMap = new HashMap<>();
            for (WorldObject obj : knotList) {
                KnotMetaComponent m = obj.getSingle(KnotMetaComponent.class).get();
                posMap.put(obj.id(), m.pos.get());
                posMap.put(m.uuid.get(), m.pos.get());

                boolean inv = obj.getSingle(KnotConnectionComponent.class)
                        .map(c -> c.isInvested.get()).orElse(false);
                investedMap.put(obj.id(), inv);
                investedMap.put(m.uuid.get(), inv);
            }

            // === 3. РЕАЛЬНЫЕ ДОРОЖНЫЕ СВЯЗИ (neighbors) ===
            Set<String> drawnEdges = new HashSet<>();
            for (WorldObject obj : knotList) {
                Optional<KnotMetaComponent> mOpt = obj.getSingle(KnotMetaComponent.class);
                Optional<KnotConnectionComponent> cOpt = obj.getSingle(KnotConnectionComponent.class);
                if (mOpt.isEmpty() || cOpt.isEmpty()) continue;

                UUID fromId = obj.id();
                BlockPos fromPos = mOpt.get().pos.get();
                boolean fromInvested = cOpt.get().isInvested.get();

                for (UUID toId : cOpt.get().neighbors.get()) {
                    String edgeKey = fromId.compareTo(toId) < 0
                            ? fromId + "_" + toId
                            : toId + "_" + fromId;
                    if (!drawnEdges.add(edgeKey)) continue;

                    BlockPos toPos = posMap.get(toId);
                    if (toPos != null) {
                        float x1 = world.worldToRootX((float) fromPos.getX());
                        float y1 = world.worldToRootY((float) fromPos.getZ());
                        float x2 = world.worldToRootX((float) toPos.getX());
                        float y2 = world.worldToRootY((float) toPos.getZ());

                        boolean active = fromInvested && Boolean.TRUE.equals(investedMap.get(toId));
                        if (active) {
                            // Светящийся торговый путь
                            draw.addLine(x1, y1, x2, y2, new MutableColor(1.0f, 0.85f, 0.35f, 0.95f), 3.5f);
                            draw.addLine(x1, y1, x2, y2, new MutableColor(1.0f, 0.65f, 0.15f, 0.40f), 6.5f);
                        } else {
                            // Неактивная дорога
                            draw.addLine(x1, y1, x2, y2, new MutableColor(0.40f, 0.52f, 0.68f, 0.60f), 2.0f);
                        }
                    }
                }
            }

            // === 4. ПСЕВДО-ЛИНИИ СОЕДИНЕНИЙ (ДЛЯ ТЕСТА И ПЛАНИРОВАНИЯ МАРШРУТОВ) ===
            MutableColor pseudoColor = new MutableColor(0.45f, 0.65f, 0.85f, 0.40f);
            MutableColor pseudoPointColor = new MutableColor(0.45f, 0.65f, 0.85f, 0.60f);

            for (PseudoEdge edge : pseudoEdges) {
                // Если между узлами уже есть реальная связь, псевдо-линию пропускаем
                if (drawnEdges.contains(edge.key())) continue;

                float x1 = world.worldToRootX((float) edge.posA.getX());
                float y1 = world.worldToRootY((float) edge.posA.getZ());
                float x2 = world.worldToRootX((float) edge.posB.getX());
                float y2 = world.worldToRootY((float) edge.posB.getZ());

                // Рисуем пунктирную псевдо-линию
                drawDashedLine(draw, x1, y1, x2, y2, pseudoColor, 1.8f, 10.0f, 6.0f);

                // Точка на середине пути для визуального ритма
                float mx = (x1 + x2) * 0.5f;
                float my = (y1 + y2) * 0.5f;
                draw.addCircleFilled(mx, my, 3.0f, pseudoPointColor, 8);
            }

            // === 5. ОРЕОЛЫ И МАРКЕРЫ УЗЛОВ ===
            for (WorldObject obj : knotList) {
                KnotMetaComponent m = obj.getSingle(KnotMetaComponent.class).get();
                BlockPos pos = m.pos.get();
                float kx = world.worldToRootX((float) pos.getX());
                float ky = world.worldToRootY((float) pos.getZ());

                boolean inv = obj.getSingle(KnotConnectionComponent.class)
                        .map(c -> c.isInvested.get()).orElse(false);

                KnotType kType = m.type.get();
                MutableColor haloColor = inv
                        ? new MutableColor(1.0f, 0.82f, 0.22f, 0.55f)
                        : (kType == KnotType.TOWN
                            ? new MutableColor(0.25f, 0.55f, 0.95f, 0.55f)
                            : new MutableColor(0.35f, 0.80f, 0.45f, 0.55f));

                draw.addCircleFilled(kx, ky, 10.0f, haloColor, 16);
                draw.addCircle(kx, ky, 14.0f, new MutableColor(1.0f, 1.0f, 1.0f, 0.40f), 16, 1.5f);
            }

            // === 6. МАРКЕР ИГРОКА ===
            if (minecraft.player != null) {
                float px = (float) minecraft.player.getX();
                float pz = (float) minecraft.player.getZ();
                float psx = world.worldToRootX(px);
                float psy = world.worldToRootY(pz);

                draw.addCircleFilled(psx, psy, 6.0f, new MutableColor(0.95f, 0.22f, 0.22f, 1.0f), 16);
                draw.addCircle(psx, psy, 11.0f, new MutableColor(1.0f, 0.70f, 0.70f, 0.80f), 16, 1.5f);
            }
        });

        // Создаем интерактивные якоря (виджеты) для каждого узла
        for (WorldObject knot : knotList) {
            KnotMetaComponent meta = knot.getSingle(KnotMetaComponent.class).get();
            BlockPos pos = meta.pos.get();
            float worldX = (float) pos.getX();
            float worldY = (float) pos.getZ();

            String name = meta.name.get();
            KnotType type = meta.type.get();
            UUID knotId = knot.id();

            Optional<KnotConnectionComponent> connOpt = knot.getSingle(KnotConnectionComponent.class);
            Optional<KnotProductionComponent> prodOpt = knot.getSingle(KnotProductionComponent.class);

            String iconPrefix = switch (type) {
                case TOWN -> "🏛 ";
                case PRODUCTION -> "⛏ ";
                case WAYPOINT -> "⚓ ";
                case OUTPOST -> "⚔ ";
                case PORTAL -> "🌀 ";
                default -> "📍 ";
            };

            String btnTitle = iconPrefix + name;
            Button marker = new Button(btnTitle);

            marker.onClick(event -> {
                // В тестовом режиме: клик переключает инвестицию в узел (isInvested)
                connOpt.ifPresent(conn -> conn.isInvested.update(v -> !v));

                boolean currentInvested = connOpt.map(c -> c.isInvested.get()).orElse(false);
                int cost = connOpt.map(c -> c.investmentCost.get()).orElse(0);
                int neighborsCount = connOpt.map(c -> c.neighbors.get().size()).orElse(0);
                int subNodesCount = prodOpt.map(p -> p.subNodes.get().size()).orElse(0);

                status.text(String.format(Locale.ROOT,
                        "[%s] %s | Pos: [%d, %d, %d] | Invested: %s (Cost: %d CP) | Sub-nodes: %d | Neighbors: %d",
                        type, name, pos.getX(), pos.getY(), pos.getZ(),
                        currentInvested ? "YES" : "NO", cost, subNodesCount, neighborsCount
                ));
            });

            float btnW = Math.max(90.0f, btnTitle.length() * 8.0f + 18.0f);
            float btnH = 26.0f;
            canvas.anchorLayer().add("knot_" + knotId, worldX, worldY, marker)
                    .screenSize(btnW, btnH)
                    .pivot(0.5f, 0.5f);
        }

        canvas.onViewportChanged(event -> {
            float curWorldX = canvas.rootToWorldX(screenCenterX);
            float curWorldY = canvas.rootToWorldY(screenCenterY);
            status.text(String.format(Locale.ROOT,
                    "WorldCanvas: Center [%.0f, %.0f] | Zoom %.2fx | Knots: %d | Loaded Chunks: %d",
                    curWorldX, curWorldY, event.newZoom(), knotList.size(), cachedLoadedChunks.size()));
        });

        // Собираем в вертикальный контейнер: холст сверху, статус-бар снизу
        VBox rootBox = new VBox();
        rootBox.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));
        rootBox.addChild(canvas);
        rootBox.addChild(status);

        return rootBox;
    }

    /**
     * Построение псевдо-соединений (Proximity Network) между ближайшими узлами.
     */
    private record PseudoEdge(UUID a, UUID b, BlockPos posA, BlockPos posB, double distance) {
        public String key() {
            return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
        }
    }

    private record NeighborCandidate(UUID id, BlockPos pos, double dist) {
    }

    private static List<PseudoEdge> buildPseudoEdges(List<WorldObject> knots) {
        Map<String, PseudoEdge> edges = new HashMap<>();
        if (knots.size() < 2) return List.of();

        for (int i = 0; i < knots.size(); i++) {
            WorldObject objA = knots.get(i);
            KnotMetaComponent metaA = objA.getSingle(KnotMetaComponent.class).get();
            BlockPos posA = metaA.pos.get();
            UUID idA = objA.id();

            List<NeighborCandidate> candidates = new ArrayList<>();
            for (int j = 0; j < knots.size(); j++) {
                if (i == j) continue;
                WorldObject objB = knots.get(j);
                KnotMetaComponent metaB = objB.getSingle(KnotMetaComponent.class).get();
                BlockPos posB = metaB.pos.get();
                double dist = Math.hypot(posA.getX() - posB.getX(), posA.getZ() - posB.getZ());
                candidates.add(new NeighborCandidate(objB.id(), posB, dist));
            }

            candidates.sort(Comparator.comparingDouble(NeighborCandidate::dist));

            // Связываем с самым ближайшим соседом (до 6000 блоков)
            if (!candidates.isEmpty()) {
                NeighborCandidate c1 = candidates.get(0);
                if (c1.dist <= 6000.0) {
                    PseudoEdge e = new PseudoEdge(idA, c1.id, posA, c1.pos, c1.dist);
                    edges.putIfAbsent(e.key(), e);
                }
            }

            // Связываем со вторым ближайшим, если он не слишком далеко по сравнению с первым
            if (candidates.size() > 1) {
                NeighborCandidate c1 = candidates.get(0);
                NeighborCandidate c2 = candidates.get(1);
                if (c2.dist <= 6000.0 && c2.dist <= c1.dist * 1.85) {
                    PseudoEdge e = new PseudoEdge(idA, c2.id, posA, c2.pos, c2.dist);
                    edges.putIfAbsent(e.key(), e);
                }
            }

            // Связываем с третьим, если узлы сгруппированы в плотный кластер
            if (candidates.size() > 2) {
                NeighborCandidate c1 = candidates.get(0);
                NeighborCandidate c3 = candidates.get(2);
                if (c3.dist <= 4000.0 && c3.dist <= c1.dist * 1.35) {
                    PseudoEdge e = new PseudoEdge(idA, c3.id, posA, c3.pos, c3.dist);
                    edges.putIfAbsent(e.key(), e);
                }
            }
        }

        return new ArrayList<>(edges.values());
    }

    /**
     * Отрисовка пунктирной линии.
     */
    private static void drawDashedLine(DrawScope draw, float x1, float y1, float x2, float y2,
                                       MutableColor color, float thickness, float dashLen, float gapLen) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dist = (float) Math.hypot(dx, dy);
        if (dist <= 0.001f) return;

        float dirX = dx / dist;
        float dirY = dy / dist;

        float traveled = 0.0f;
        boolean drawDash = true;
        while (traveled < dist) {
            float step = drawDash ? dashLen : gapLen;
            float next = Math.min(dist, traveled + step);
            if (drawDash) {
                float ax = x1 + dirX * traveled;
                float ay = y1 + dirY * traveled;
                float bx = x1 + dirX * next;
                float by = y1 + dirY * next;
                draw.addLine(ax, ay, bx, by, color, thickness);
            }
            traveled = next;
            drawDash = !drawDash;
        }
    }

    /**
     * Быстрое и надёжное получение набора загруженных чанков мира.
     */
    public static LongSet getLoadedChunks() {
        long now = System.currentTimeMillis();
        if (now - lastChunkQueryTime < 150 && !cachedLoadedChunks.isEmpty()) {
            return cachedLoadedChunks;
        }
        lastChunkQueryTime = now;
        initReflection();

        LongSet set = new LongOpenHashSet();
        Minecraft mc = Minecraft.getInstance();

        // 1. Из интегрированного сервера (singleplayer) — полный список серверных чанков
        if (mc.getSingleplayerServer() != null && mc.level != null) {
            try {
                ServerLevel sLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());
                if (sLevel != null && serverVisibleChunkMapField != null) {
                    ChunkMap map = sLevel.getChunkSource().chunkMap;
                    Long2ObjectLinkedOpenHashMap<?> holders = (Long2ObjectLinkedOpenHashMap<?>) serverVisibleChunkMapField.get(map);
                    if (holders != null) {
                        for (Object val : holders.values()) {
                            if (val instanceof ChunkHolder holder) {
                                set.add(holder.getPos().toLong());
                            }
                        }
                        if (!set.isEmpty()) {
                            cachedLoadedChunks = set;
                            return set;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Из клиентского кэша чанков (ClientChunkCache)
        if (mc.level != null && clientStorageField != null && clientChunksField != null) {
            try {
                ClientChunkCache clientCache = mc.level.getChunkSource();
                Object storage = clientStorageField.get(clientCache);
                if (storage != null) {
                    AtomicReferenceArray<?> chunks = (AtomicReferenceArray<?>) clientChunksField.get(storage);
                    if (chunks != null) {
                        for (int i = 0; i < chunks.length(); i++) {
                            Object obj = chunks.get(i);
                            if (obj instanceof LevelChunk chunk) {
                                set.add(chunk.getPos().toLong());
                            }
                        }
                        if (!set.isEmpty()) {
                            cachedLoadedChunks = set;
                            return set;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Запасной вариант: опрос чанков вокруг игрока через публичный hasChunk
        if (mc.level != null && mc.player != null) {
            int pcx = (int) Math.floor(mc.player.getX()) >> 4;
            int pcz = (int) Math.floor(mc.player.getZ()) >> 4;
            int radius = 16;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (mc.level.getChunkSource().hasChunk(cx, cz)) {
                        set.add(ChunkPos.asLong(cx, cz));
                    }
                }
            }
        }

        cachedLoadedChunks = set;
        return set;
    }

    private static void initReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        try {
            for (Field f : ChunkMap.class.getDeclaredFields()) {
                if (Long2ObjectLinkedOpenHashMap.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    serverVisibleChunkMapField = f;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            for (Field f : ClientChunkCache.class.getDeclaredFields()) {
                if (f.getType().getName().contains("Storage")) {
                    f.setAccessible(true);
                    clientStorageField = f;
                    for (Field sf : f.getType().getDeclaredFields()) {
                        if (AtomicReferenceArray.class.isAssignableFrom(sf.getType())) {
                            sf.setAccessible(true);
                            clientChunksField = sf;
                            break;
                        }
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
