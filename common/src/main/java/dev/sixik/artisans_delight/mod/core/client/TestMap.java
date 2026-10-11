package dev.sixik.artisans_delight.mod.core.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.sixik.artisans_delight.libs.world.WorldObject;
import dev.sixik.artisans_delight.libs.world.WorldObjectManager;
import dev.sixik.artisans_delight.libs.world.minecraft.WorldObjectManagerApi;
import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotConnectionComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotMetaComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotProductionComponent;
import dev.sixik.unigui.api.core.InvalidationFlags;
import dev.sixik.unigui.api.core.UnityLikeUIScaleProvider;
import dev.sixik.unigui.api.debug.DebugFlags;
import dev.sixik.unigui.api.event.PointerEnteredEvent;
import dev.sixik.unigui.api.layout.LayoutConstraints;
import dev.sixik.unigui.api.math.MutableColor;
import dev.sixik.unigui.api.render.DrawScope;
import dev.sixik.unigui.api.render.TextureHandle;
import dev.sixik.unigui.api.render.TextureOptions;
import dev.sixik.unigui.api.render.UiRenderPolicy;
import dev.sixik.unigui.api.text.RichText;
import dev.sixik.unigui.api.widget.Widget;
import dev.sixik.unigui.backend.minecraft_impl.MinecraftClipboardService;
import dev.sixik.unigui.backend.minecraft_impl.MinecraftTextureHandle;
import dev.sixik.unigui.backend.minecraft_impl.MinecraftWidgetScreen;
import dev.sixik.unigui.backend.minecraft_impl.UniGuiTextures;
import dev.sixik.unigui.impl.core.DefaultUIContext;
import dev.sixik.unigui.widgets.containers.VBox;
import dev.sixik.unigui.widgets.display.Label;
import dev.sixik.unigui.widgets.feedback.OverlayLayer;
import dev.sixik.unigui.widgets.feedback.Tooltip;
import dev.sixik.unigui.widgets.interaction.Button;
import dev.sixik.unigui.widgets.world.WorldCanvas;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class TestMap {

    private static final MutableColor COLOR_WHITE = new MutableColor(1.0f, 1.0f, 1.0f, 1.0f);
    private static final TextureHandle ICON_NODE_TOWN = new MinecraftTextureHandle(
            ResourceLocation.fromNamespaceAndPath("artisans_delight", "textures/gui/icon_node_town.png"), 124, 140);
    private static final TextureHandle ICON_NODE_KNOT = new MinecraftTextureHandle(
            ResourceLocation.fromNamespaceAndPath("artisans_delight", "textures/gui/icon_node_knot.png"), 124, 140);

    private static final int MAX_CHUNK_BAKES_PER_FRAME = 24;
    private static final float LOD_SWITCH_ZOOM = 0.12f;

    private static final int REGION_CHUNK_SIZE = 16; // 16x16 chunks = 256x256 blocks (LOD 0)
    private static final int MACRO_CHUNK_SIZE = 64;  // 64x64 chunks = 1024x1024 blocks (LOD 1)

    // Хранилище исследованных чанков и LOD-регионов в памяти без лимита на вытеснение
    private static final LongSet BAKED_CHUNKS = new LongOpenHashSet();
    private static final Map<Long, RegionTile> REGIONS_LOD0 = new HashMap<>();
    private static final Map<Long, RegionTile> REGIONS_LOD1 = new HashMap<>();

    private static ResourceKey<Level> lastDimension = null;

    private static Field serverVisibleChunkMapField;
    private static Field clientStorageField;
    private static Field clientChunksField;
    private static boolean reflectionInitialized = false;

    private static long lastChunkQueryTime = 0;
    private static LongSet cachedLoadedChunks = new LongOpenHashSet();

    public static void clearTextureCache() {
        for (RegionTile r : REGIONS_LOD0.values()) {
            r.close();
        }
        REGIONS_LOD0.clear();

        for (RegionTile r : REGIONS_LOD1.values()) {
            r.close();
        }
        REGIONS_LOD1.clear();

        BAKED_CHUNKS.clear();
    }

    public static void openMap() {
        openMap(null, null);
    }

    public static void openMap(WorldObjectManager manager, Collection<WorldObject> knots) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen previous = minecraft.screen;

        if (minecraft.level != null) {
            ResourceKey<Level> currentDim = minecraft.level.dimension();
            if (lastDimension != null && !lastDimension.equals(currentDim)) {
                clearTextureCache();
            }
            lastDimension = currentDim;
        } else {
            clearTextureCache();
        }

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
        UnityLikeUIScaleProvider scale = new UnityLikeUIScaleProvider()
                .referenceResolution(1920.0f, 1080.0f)
                .matchBalanced()
                .scaleRange(0.75f, 6.0f)
                .userScale(2.15f);
        context.scaleProvider(scale);
        context.debugFlags(DebugFlags.PROFILER_OVERLAY);

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
                "WorldCanvas: knots: %d | Explored: %d chunks | Drag empty space, Wheel zoom, Click markers to inspect/invest",
                knotList.size(), BAKED_CHUNKS.size()));
        status.layout(style -> style.size(LayoutConstraints.AUTO, 22.0f).flexGrow(0.0f).flexShrink(0.0f));

        // Вычисляем псевдо-соединения между узлами (KNN / Proximity Graph)
        List<PseudoEdge> pseudoEdges = buildPseudoEdges(knotList);

        // Фоновый и графический слой карты (рельеф мира, сетка, чанки, границы чанков, псевдо-связи, дороги, узлы, игрок)
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

            // === 1. ВАНИЛЬНЫЙ РЕЛЬЕФ МИРА С СИСТЕМОЙ LOD (LOD 0: 256x256 блоков, LOD 1: 1024x1024 блоков) ===
            LongSet loadedChunks = getLoadedChunks();
            int bakedThisFrame = 0;

            if (minecraft.level != null && !loadedChunks.isEmpty()) {
                for (long packed : loadedChunks) {
                    if (BAKED_CHUNKS.contains(packed)) {
                        continue;
                    }
                    if (bakedThisFrame >= MAX_CHUNK_BAKES_PER_FRAME) {
                        break;
                    }
                    int cx = ChunkPos.getX(packed);
                    int cz = ChunkPos.getZ(packed);
                    LevelChunk chunk = getLoadedLevelChunk(minecraft, cx, cz);
                    if (chunk != null && !chunk.isEmpty()) {
                        bakeChunkToRegions(minecraft, chunk, cx, cz);
                        BAKED_CHUNKS.add(packed);
                        bakedThisFrame++;
                    }
                }
            }

            // Подложка для чанков в зоне загрузки, которые ещё ждут своей очереди запекания
            if (minecraft.level != null && !loadedChunks.isEmpty()) {
                MutableColor placeholderTint = new MutableColor(0.065f, 0.14f, 0.25f, 0.22f);
                for (long packed : loadedChunks) {
                    if (!BAKED_CHUNKS.contains(packed)) {
                        int cx = ChunkPos.getX(packed);
                        int cz = ChunkPos.getZ(packed);
                        float bx0 = cx * 16.0f;
                        float bz0 = cz * 16.0f;
                        float bx1 = bx0 + 16.0f;
                        float bz1 = bz0 + 16.0f;
                        if (bx1 < minWorldX || bx0 > maxWorldX || bz1 < minWorldY || bz0 > maxWorldY) {
                            continue;
                        }
                        float sx0 = world.worldToRootX(bx0);
                        float sy0 = world.worldToRootY(bz0);
                        float sx1 = world.worldToRootX(bx1);
                        float sy1 = world.worldToRootY(bz1);
                        draw.addRectFilled(sx0, sy0, sx1 - sx0, sy1 - sy0, placeholderTint);
                    }
                }
            }

            // Отрисовка активных LOD-регионов (LOD 0 для зума >= 0.12f, LOD 1 для зума < 0.12f)
            boolean useLod1 = zoom < LOD_SWITCH_ZOOM;
            Map<Long, RegionTile> activeRegions = useLod1 ? REGIONS_LOD1 : REGIONS_LOD0;

            for (RegionTile tile : activeRegions.values()) {
                // Фрустум-куллинг на уровне регионов
                if (tile.maxWorldX < minWorldX || tile.minWorldX > maxWorldX ||
                        tile.maxWorldZ < minWorldY || tile.minWorldZ > maxWorldY) {
                    continue;
                }

                tile.updateTextureIfNeeded();
                if (tile.handle != null) {
                    float sx0 = world.worldToRootX(tile.minWorldX);
                    float sy0 = world.worldToRootY(tile.minWorldZ);
                    float sx1 = world.worldToRootX(tile.maxWorldX);
                    float sy1 = world.worldToRootY(tile.maxWorldZ);
                    float sw = sx1 - sx0;
                    float sh = sy1 - sy0;

                    draw.addImage(tile.handle, sx0, sy0, sw, sh, COLOR_WHITE);
                }
            }

            // === 2. ДИНАМИЧЕСКАЯ ГРАНИЦА ЗАГРУЖЕННЫХ ЧАНКОВ (ФРОНТИР) ===
            if (!loadedChunks.isEmpty()) {
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

            // === 3. АДАПТИВНАЯ КООРДИНАТНАЯ СЕТКА ===
            float step = 100.0f;
            if (zoom < 0.15f) step = 1000.0f;
            else if (zoom < 0.45f) step = 500.0f;
            else if (zoom < 1.0f) step = 200.0f;

            float startX = (float) (Math.floor(Math.min(minWorldX, maxWorldX) / step) * step);
            float endX = (float) (Math.ceil(Math.max(minWorldX, maxWorldX) / step) * step);
            float startY = (float) (Math.floor(Math.min(minWorldY, maxWorldY) / step) * step);
            float endY = (float) (Math.ceil(Math.max(minWorldY, maxWorldY) / step) * step);

            MutableColor gridColor = new MutableColor(0.18f, 0.25f, 0.36f, 0.20f);
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

            // === 4. РЕАЛЬНЫЕ ДОРОЖНЫЕ СВЯЗИ (neighbors) ===
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

            // === 5. ПСЕВДО-ЛИНИИ СОЕДИНЕНИЙ (ДЛЯ ТЕСТА И ПЛАНИРОВАНИЯ МАРШРУТОВ) ===
            MutableColor pseudoColor = new MutableColor(0.45f, 0.65f, 0.85f, 0.40f);
            MutableColor pseudoPointColor = new MutableColor(0.45f, 0.65f, 0.85f, 0.60f);

            for (PseudoEdge edge : pseudoEdges) {
                if (drawnEdges.contains(edge.key())) continue;

                float x1 = world.worldToRootX((float) edge.posA.getX());
                float y1 = world.worldToRootY((float) edge.posA.getZ());
                float x2 = world.worldToRootX((float) edge.posB.getX());
                float y2 = world.worldToRootY((float) edge.posB.getZ());

                // Пунктирная псевдо-линия
                drawDashedLine(draw, x1, y1, x2, y2, pseudoColor, 1.8f, 10.0f, 6.0f);

                // Точка на середине пути
                float mx = (x1 + x2) * 0.5f;
                float my = (y1 + y2) * 0.5f;
                draw.addCircleFilled(mx, my, 3.0f, pseudoPointColor, 8);
            }

            // === 6. ОРЕОЛЫ И МАРКЕРЫ УЗЛОВ ===
            for (WorldObject obj : knotList) {
                KnotMetaComponent m = obj.getSingle(KnotMetaComponent.class).get();
                BlockPos pos = m.pos.get();
                float kx = world.worldToRootX((float) pos.getX());
                float ky = world.worldToRootY((float) pos.getZ());

                boolean inv = obj.getSingle(KnotConnectionComponent.class)
                        .map(c -> c.isInvested.get()).orElse(false);

                KnotType kType = m.type.get();
                MutableColor haloColor = inv
                        ? new MutableColor(1.0f, 0.82f, 0.22f, 0.65f)
                        : (kType == KnotType.TOWN
                            ? new MutableColor(0.25f, 0.55f, 0.95f, 0.55f)
                            : new MutableColor(0.35f, 0.80f, 0.45f, 0.55f));

                // Круг-основание под кончиком метки на карте
                draw.addCircleFilled(kx, ky, 7.0f, haloColor, 16);
                draw.addCircle(kx, ky, 10.0f, new MutableColor(1.0f, 1.0f, 1.0f, 0.50f), 16, 1.5f);
            }

            // === 7. МАРКЕР ИГРОКА ===
            if (minecraft.player != null) {
                float px = (float) minecraft.player.getX();
                float pz = (float) minecraft.player.getZ();
                float psx = world.worldToRootX(px);
                float psy = world.worldToRootY(pz);

                draw.addCircleFilled(psx, psy, 6.0f, new MutableColor(0.95f, 0.22f, 0.22f, 1.0f), 16);
                draw.addCircle(psx, psy, 11.0f, new MutableColor(1.0f, 0.70f, 0.70f, 0.80f), 16, 1.5f);
            }
        });

        // Собираем в вертикальный контейнер: холст сверху, статус-бар снизу
        VBox rootBox = new VBox();
        rootBox.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));
        rootBox.addChild(canvas);
        rootBox.addChild(status);

        // OverlayLayer для размещения всплывающих подсказок (Tooltips) поверх холста
        OverlayLayer rootOverlay = new OverlayLayer(rootBox);
        rootOverlay.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));

        // Создаем интерактивные якоря (иконка узла с тултипом) для каждого узла
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

            TextureHandle icon = (type == KnotType.TOWN) ? ICON_NODE_TOWN : ICON_NODE_KNOT;

            Button marker = new Button("");
            marker.renderer((draw, w) -> {
                dev.sixik.unigui.api.math.RectView b = w.layoutBounds();
                float x = b.x();
                float y = b.y();
                float w0 = b.width();
                float h0 = b.height();

                boolean hovered = marker.hovered();
                if (hovered) {
                    // Подсветка/свечение при наведении курсора на иконку
                    draw.addCircleFilled(x + w0 * 0.5f, y + h0 * 0.45f, w0 * 0.55f,
                            new MutableColor(1.0f, 0.90f, 0.40f, 0.35f), 16);
                    draw.addImage(icon, x - 1.0f, y - 2.0f, w0 + 2.0f, h0 + 2.0f,
                            new MutableColor(1.0f, 1.0f, 0.85f, 1.0f));
                } else {
                    draw.addImage(icon, x, y, w0, h0, COLOR_WHITE);
                }
            });

            Tooltip tooltip = new Tooltip(marker, buildNodeTooltip(meta, connOpt, prodOpt));
            tooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
            tooltip.borderColor().set(0.85f, 0.70f, 0.25f, 0.90f);
            tooltip.radius(4.0f);
            tooltip.borderWidth(1.2f);
            tooltip.offset(4.0f, 6.0f);
            tooltip.maxWidth(260.0f);

            marker.on(PointerEnteredEvent.TYPE, event -> {
                tooltip.invalidate(InvalidationFlags.LAYOUT | InvalidationFlags.VISUAL);
            });

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

                // Обновляем текст тултипа в реальном времени при клике
                tooltip.richText(buildNodeTooltip(meta, connOpt, prodOpt));
            });

            float pinW = 32.0f;
            float pinH = 36.0f;
            canvas.anchorLayer().add("knot_" + knotId, worldX, worldY, marker)
                    .screenSize(pinW, pinH)
                    .pivot(0.5f, 1.0f);

            rootOverlay.addOverlay(tooltip);
        }

        canvas.onViewportChanged(event -> {
            float curWorldX = canvas.rootToWorldX(screenCenterX);
            float curWorldY = canvas.rootToWorldY(screenCenterY);
            String lodStr = event.newZoom() < LOD_SWITCH_ZOOM ? "LOD 1 (Overview)" : "LOD 0 (Detail)";
            status.text(String.format(Locale.ROOT,
                    "WorldCanvas: Center [%.0f, %.0f] | Zoom %.2fx | Knots: %d | Explored: %d chunks (%s) | Loaded: %d chunks",
                    curWorldX, curWorldY, event.newZoom(), knotList.size(), BAKED_CHUNKS.size(), lodStr, cachedLoadedChunks.size()));
        });

        return rootOverlay;
    }

    /**
     * Формирует форматированный rich-текст для всплывающей подсказки узла/деревни.
     */
    private static RichText buildNodeTooltip(
            KnotMetaComponent meta,
            Optional<KnotConnectionComponent> connOpt,
            Optional<KnotProductionComponent> prodOpt) {
        BlockPos pos = meta.pos.get();
        String name = meta.name.get();
        KnotType type = meta.type.get();
        boolean invested = connOpt.map(c -> c.isInvested.get()).orElse(false);
        int cost = connOpt.map(c -> c.investmentCost.get()).orElse(0);
        int neighborsCount = connOpt.map(c -> c.neighbors.get().size()).orElse(0);

        String typeName = switch (type) {
            case TOWN -> "Town";
            case PRODUCTION -> "Production";
            case WAYPOINT -> "Waypoint";
            case OUTPOST -> "Outpost";
            case PORTAL -> "Portal";
            default -> "Node";
        };

        RichText.Builder builder = RichText.builder();
        // Название узла (золотой цвет)
        builder.color(new MutableColor(1.0f, 0.85f, 0.20f, 1.0f))
               .append(name + "\n");

        // Позиция узла (светло-серый)
        builder.color(new MutableColor(0.80f, 0.82f, 0.85f, 1.0f))
               .append(String.format(Locale.ROOT, "Pos: [%d, %d, %d]\n", pos.getX(), pos.getY(), pos.getZ()));

        // Тип узла (бирюзовый/голубой)
        builder.color(new MutableColor(0.40f, 0.75f, 1.0f, 1.0f))
               .append("Type: " + typeName + "\n");

        // Статус инвестиции (зеленый / оранжевый)
        if (invested) {
            builder.color(new MutableColor(0.35f, 0.95f, 0.45f, 1.0f))
                   .append("Status: Invested\n");
        } else {
            builder.color(new MutableColor(1.0f, 0.60f, 0.30f, 1.0f))
                   .append("Status: Not Invested (Cost: " + cost + " CP)\n");
        }

        if (neighborsCount > 0) {
            builder.color(new MutableColor(0.70f, 0.70f, 0.70f, 1.0f))
                   .append("Connections: " + neighborsCount + "\n");
        }

        // Подсказка действия
        builder.color(new MutableColor(0.50f, 0.55f, 0.60f, 1.0f))
               .append("Click to toggle investment");

        return builder.build();
    }

    /**
     * Класс тайла региона карты с поддержкой системы уровней детализации (LOD).
     */
    private static final class RegionTile {
        final int tileX;
        final int tileZ;
        final int lod; // 0 для LOD 0 (16x16 чанков, 256x256 блоков), 1 для LOD 1 (64x64 чанков, 1024x1024 блоков)
        final float minWorldX;
        final float minWorldZ;
        final float maxWorldX;
        final float maxWorldZ;
        final String texId;
        final int[] pixels; // 256 * 256 RGBA цветов
        TextureHandle handle;
        boolean dirty;
        int bakedChunkCount;

        RegionTile(int tileX, int tileZ, int lod) {
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.lod = lod;
            float blockSize = (lod == 0) ? (REGION_CHUNK_SIZE * 16.0f) : (MACRO_CHUNK_SIZE * 16.0f);
            this.minWorldX = tileX * blockSize;
            this.minWorldZ = tileZ * blockSize;
            this.maxWorldX = this.minWorldX + blockSize;
            this.maxWorldZ = this.minWorldZ + blockSize;

            String xStr = tileX < 0 ? "n" + (-tileX) : "p" + tileX;
            String zStr = tileZ < 0 ? "n" + (-tileZ) : "p" + tileZ;
            this.texId = "artisans_delight:map_lod" + lod + "_" + xStr + "_" + zStr;
            this.pixels = new int[256 * 256];
            this.dirty = false;
            this.bakedChunkCount = 0;
        }

        void updateTextureIfNeeded() {
            if (!dirty) return;
            dirty = false;

            NativeImage image = new NativeImage(NativeImage.Format.RGBA, 256, 256, false);
            for (int y = 0; y < 256; y++) {
                int rowOffset = y * 256;
                for (int x = 0; x < 256; x++) {
                    image.setPixelRGBA(x, y, pixels[rowOffset + x]);
                }
            }
            handle = UniGuiTextures.replace(texId, image, TextureOptions.nearest());
        }

        void close() {
            if (handle != null) {
                UniGuiTextures.unregister(texId);
                handle = null;
            }
            dirty = false;
            bakedChunkCount = 0;
        }
    }

    /**
     * Запекание чанка сразу во все активные слои системы LOD (LOD 0 и LOD 1).
     */
    private static void bakeChunkToRegions(Minecraft mc, LevelChunk chunk, int cx, int cz) {
        int[] chunkPixels = new int[256];
        if (!sampleChunkPixels(mc, chunk, cx, cz, chunkPixels)) {
            return;
        }

        // 1. LOD 0: Регион 16x16 чанков (256x256 блоков, 1 блок = 1 пиксель)
        int rx = Math.floorDiv(cx, REGION_CHUNK_SIZE);
        int rz = Math.floorDiv(cz, REGION_CHUNK_SIZE);
        long rKey = ChunkPos.asLong(rx, rz);

        RegionTile region = REGIONS_LOD0.computeIfAbsent(rKey, k -> new RegionTile(rx, rz, 0));
        int relChunkX = cx - rx * REGION_CHUNK_SIZE;
        int relChunkZ = cz - rz * REGION_CHUNK_SIZE;
        int px0 = relChunkX * 16;
        int pz0 = relChunkZ * 16;

        for (int lz = 0; lz < 16; lz++) {
            int rowOffset = (pz0 + lz) * 256 + px0;
            for (int lx = 0; lx < 16; lx++) {
                region.pixels[rowOffset + lx] = chunkPixels[lz * 16 + lx];
            }
        }
        region.dirty = true;
        region.bakedChunkCount++;

        // 2. LOD 1: Макро-регион 64x64 чанков (1024x1024 блоков, 1 пиксель = 4x4 блока)
        int mx = Math.floorDiv(cx, MACRO_CHUNK_SIZE);
        int mz = Math.floorDiv(cz, MACRO_CHUNK_SIZE);
        long mKey = ChunkPos.asLong(mx, mz);

        RegionTile macro = REGIONS_LOD1.computeIfAbsent(mKey, k -> new RegionTile(mx, mz, 1));
        int mRelChunkX = cx - mx * MACRO_CHUNK_SIZE;
        int mRelChunkZ = cz - mz * MACRO_CHUNK_SIZE;
        int mpx0 = mRelChunkX * 4;
        int mpz0 = mRelChunkZ * 4;

        // Даунскейл 16x16 чанка в 4x4 макро-пикселя
        for (int my = 0; my < 4; my++) {
            int macroRowOffset = (mpz0 + my) * 256 + mpx0;
            for (int mxPix = 0; mxPix < 4; mxPix++) {
                int sampleColor = chunkPixels[(my * 4 + 2) * 16 + (mxPix * 4 + 2)];
                macro.pixels[macroRowOffset + mxPix] = sampleColor;
            }
        }
        macro.dirty = true;
        macro.bakedChunkCount++;
    }

    /**
     * Сэмплирование цветов и высот чанка по точной ванильной формуле 3D-рельефа.
     */
    private static boolean sampleChunkPixels(Minecraft mc, LevelChunk chunk, int cx, int cz, int[] outPixels) {
        if (chunk == null || chunk.isEmpty()) return false;

        try {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos fluidPos = new BlockPos.MutableBlockPos();

            Level level = chunk.getLevel() != null ? chunk.getLevel() : mc.level;
            int minBuildHeight = level != null ? level.getMinBuildHeight() : chunk.getMinBuildHeight();

            // Высоты северных соседей (строка z=15 чанка cz-1) для бесшовного затенения на стыке
            int[] lastHeights = new int[16];
            LevelChunk northChunk = getLoadedLevelChunk(mc, cx, cz - 1);
            for (int lx = 0; lx < 16; lx++) {
                if (northChunk != null && !northChunk.isEmpty()) {
                    lastHeights[lx] = northChunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, 15);
                } else {
                    lastHeights[lx] = -1;
                }
            }

            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int worldX = (cx << 4) + lx;
                    int worldZ = (cz << 4) + lz;

                    int k = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) + 1;
                    BlockState blockstate;
                    int waterDepth = 0;

                    if (k <= minBuildHeight + 1) {
                        blockstate = Blocks.BEDROCK.defaultBlockState();
                    } else {
                        pos.set(worldX, k, worldZ);
                        do {
                            pos.setY(--k);
                            blockstate = chunk.getBlockState(pos);
                        } while (blockstate.getMapColor(chunk, pos) == MapColor.NONE && k > minBuildHeight);

                        FluidState fluidState = blockstate.getFluidState();
                        if (k > minBuildHeight && !fluidState.isEmpty()) {
                            int fluidY = k - 1;
                            fluidPos.set(worldX, fluidY, worldZ);
                            BlockState subState;
                            do {
                                fluidPos.setY(fluidY--);
                                subState = chunk.getBlockState(fluidPos);
                                waterDepth++;
                            } while (fluidY > minBuildHeight && !subState.getFluidState().isEmpty());

                            if (!blockstate.isFaceSturdy(chunk, pos, Direction.UP)) {
                                blockstate = fluidState.createLegacyBlock();
                            }
                        }
                    }

                    MapColor mapColor = blockstate.getMapColor(chunk, pos);
                    if (mapColor == MapColor.NONE) {
                        mapColor = MapColor.DIRT;
                    }

                    int northY = lastHeights[lx] >= 0 ? lastHeights[lx] : k;
                    lastHeights[lx] = k;

                    MapColor.Brightness brightness;
                    if (mapColor == MapColor.WATER) {
                        double d2 = (double) waterDepth * 0.1 + (double) ((worldX + worldZ) & 1) * 0.2;
                        if (d2 < 0.5) {
                            brightness = MapColor.Brightness.HIGH;
                        } else if (d2 > 0.9) {
                            brightness = MapColor.Brightness.LOW;
                        } else {
                            brightness = MapColor.Brightness.NORMAL;
                        }
                    } else {
                        double diff = (double) (k - northY);
                        double d3 = diff * 0.8 + ((double) ((worldX + worldZ) & 1) - 0.5) * 0.4;
                        if (d3 > 0.6) {
                            brightness = MapColor.Brightness.HIGH;
                        } else if (d3 < -0.6) {
                            brightness = MapColor.Brightness.LOW;
                        } else {
                            brightness = MapColor.Brightness.NORMAL;
                        }
                    }

                    int pixel = mapColor.calculateRGBColor(brightness);
                    outPixels[lz * 16 + lx] = pixel;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static LevelChunk getLoadedLevelChunk(Minecraft mc, int cx, int cz) {
        if (mc.level == null) return null;

        // 1. Клиентский уровень (Render thread safe)
        LevelChunk clientChunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        if (clientChunk != null) {
            return clientChunk;
        }

        // 2. Интегрированный сервер в singleplayer (для чанков за пределами клиентского рендера)
        if (mc.getSingleplayerServer() != null) {
            try {
                initReflection();
                ServerLevel sLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());
                if (sLevel != null && serverVisibleChunkMapField != null) {
                    ChunkMap map = sLevel.getChunkSource().chunkMap;
                    Long2ObjectLinkedOpenHashMap<?> holders = (Long2ObjectLinkedOpenHashMap<?>) serverVisibleChunkMapField.get(map);
                    if (holders != null) {
                        Object val = holders.get(ChunkPos.asLong(cx, cz));
                        if (val instanceof ChunkHolder holder) {
                            LevelChunk chunk = holder.getTickingChunk();
                            if (chunk != null) return chunk;
                            ChunkResult<LevelChunk> result = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK);
                            if (result != null && result.isSuccess()) {
                                return result.orElse(null);
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
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
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Запасной вариант: опрос чанков вокруг игрока через публичный hasChunk
        if (set.isEmpty() && mc.level != null && mc.player != null) {
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
                if (f.getName().equals("visibleChunkMap") ||
                        (Long2ObjectLinkedOpenHashMap.class.isAssignableFrom(f.getType()) && (f.getModifiers() & java.lang.reflect.Modifier.VOLATILE) != 0)) {
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
