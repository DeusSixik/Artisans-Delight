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
import dev.sixik.unigui.api.event.Event;
import dev.sixik.unigui.api.event.PointerEnteredEvent;
import dev.sixik.unigui.api.event.PointerMovedEvent;
import dev.sixik.unigui.api.event.PointerPressedEvent;
import dev.sixik.unigui.api.event.ScrollEvent;
import dev.sixik.unigui.api.layout.Align;
import dev.sixik.unigui.api.layout.EdgeInsets;
import dev.sixik.unigui.api.layout.Justify;
import dev.sixik.unigui.api.layout.LayoutConstraints;
import dev.sixik.unigui.api.layout.Overflow;
import dev.sixik.unigui.api.layout.PositionType;
import dev.sixik.unigui.api.math.MutableColor;
import dev.sixik.unigui.api.math.RectView;
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
import dev.sixik.unigui.widgets.containers.Box;
import dev.sixik.unigui.widgets.containers.HBox;
import dev.sixik.unigui.widgets.containers.ScrollView;
import dev.sixik.unigui.widgets.containers.VBox;
import dev.sixik.unigui.widgets.display.Label;
import dev.sixik.unigui.widgets.feedback.OverlayLayer;
import dev.sixik.unigui.widgets.feedback.Tooltip;
import dev.sixik.unigui.widgets.feedback.WindowWidget;
import dev.sixik.unigui.widgets.interaction.Button;
import dev.sixik.unigui.widgets.interaction.SearchField;
import dev.sixik.unigui.widgets.world.AnchorWidget;
import dev.sixik.unigui.widgets.world.WorldCanvas;
import org.lwjgl.glfw.GLFW;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.sixik.unigui.widgets.minecraft.MinecraftItemPreviewWidget;
import dev.sixik.unigui.widgets.minecraft.MinecraftItemTooltip;
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
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import dev.sixik.artisans_delight.mod.core.generation.path.KnotNetworkGraph;
import dev.sixik.artisans_delight.mod.core.generation.path.RouteGenerator;
import dev.sixik.artisans_delight.mod.core.knot.components.KnotRoutesComponent;
import dev.sixik.artisans_delight.mod.core.knot.components.town.TownPortComponent;
import dev.sixik.artisans_delight.mod.core.knot.data.KnotRouteEntry;
import dev.sixik.artisans_delight.mod.core.knot.data.RoadPoint2D;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class TestMap {

    // === РЕЖИМ ОТОБРАЖЕНИЯ ДОРОГ И ПУТЕЙ (ЛОГИЧЕСКАЯ СЕТЬ vs РЕАЛЬНЫЕ ПУТИ) ===
    private static boolean showRoadPaths = false;

    private static final MutableColor COLOR_WHITE = new MutableColor(1.0f, 1.0f, 1.0f, 1.0f);
    private static final TextureHandle ICON_NODE_TOWN = new MinecraftTextureHandle(
            ResourceLocation.fromNamespaceAndPath("artisans_delight", "textures/gui/icon_node_town.png"), 124, 140);
    private static final TextureHandle ICON_NODE_KNOT = new MinecraftTextureHandle(
            ResourceLocation.fromNamespaceAndPath("artisans_delight", "textures/gui/icon_node_knot.png"), 124, 140);
    private static final TextureHandle ICON_NODE_HOUSE = new MinecraftTextureHandle(
            ResourceLocation.fromNamespaceAndPath("artisans_delight", "textures/gui/icon_node_group_house.png"), 123, 123);
    private static final TextureHandle ICON_LOUPE = new MinecraftTextureHandle(
            ResourceLocation.fromNamespaceAndPath("artisans_delight", "textures/gui/loupe.png"), 63, 63);
    private static final TextureHandle ICON_FILTER = new MinecraftTextureHandle(
            ResourceLocation.fromNamespaceAndPath("artisans_delight", "textures/gui/filter.png"), 63, 63);
    private static final TextureHandle ICON_PICKAXE = new MinecraftTextureHandle(
            ResourceLocation.fromNamespaceAndPath("artisans_delight", "textures/gui/pickaxe.png"), 63, 63);

    // === BDO TOWN & HOUSE SYSTEM STATE ===
    private static WorldCanvas activeCanvas;
    private static Label activeStatus;
    private static WorldObject currentSelectedTown;
    private static Box activeLeftPanel;
    private static VBox activeLeftContent;
    private static Box activeSearchPanel;
    private static OverlayLayer activeUiLayer;
    private static OverlayLayer activeRootOverlay;
    private static WindowWidget activeHouseWindow;
    private static WindowWidget activeStorageWindow;
    private static final Map<UUID, List<TownHouseData>> TOWN_HOUSES = new HashMap<>();
    private static final Map<String, Tooltip> HOUSE_TOOLTIPS = new HashMap<>();
    private static final Map<UUID, TownStorageData> TOWN_STORAGES = new HashMap<>();
    private static final List<Tooltip> STORAGE_TOOLTIPS = new ArrayList<>();
    private static final int TOTAL_STORAGE_SLOTS = 56;

    // Сохраненный вьюпорт до перехода в режим города для точного возврата
    private static float savedViewportX = 0.0f;
    private static float savedViewportY = 0.0f;
    private static float savedViewportZoom = 0.35f;
    private static boolean hasSavedViewport = false;

    // Ограничение движения камеры по размерам деревни (3 чанка = 48 блоков)
    private static final float TOWN_PAN_LIMIT = 3 * 16.0f;
    private static boolean isClampingTownViewport = false;
    private static Field panDragField;
    private static java.lang.reflect.Constructor<?> panDragConstructor;

    // === CONTRIBUTION POINTS & WORKERS (BDO HUD) ===
    private static final int TOTAL_PLAYER_MAX_CP = 50;
    private static List<WorldObject> currentKnotsCache = new ArrayList<>();
    private static Box activeCpBar;
    private static Label activeCpLabel;
    private static Tooltip activeCpTooltip;
    private static WindowWidget activeWorkerWindow;

    public static final class WorkerData {
        public final String id;
        public final String name;
        public final String raceIcon;
        public final String race;
        public int level;
        public String townName;
        public String currentTask;
        public int energy;
        public int maxEnergy;
        public boolean isWorking;

        public WorkerData(String id, String name, String raceIcon, String race, int level, String townName, String currentTask, int energy, int maxEnergy, boolean isWorking) {
            this.id = id;
            this.name = name;
            this.raceIcon = raceIcon;
            this.race = race;
            this.level = level;
            this.townName = townName;
            this.currentTask = currentTask;
            this.energy = energy;
            this.maxEnergy = maxEnergy;
            this.isWorking = isWorking;
        }
    }

    private static final List<WorkerData> WORKERS = new ArrayList<>(List.of(
            new WorkerData("w1", "Ловкий гоблин", "👺", "Гоблин", 24, "Велия", "Добыча железа", 7, 15, true),
            new WorkerData("w2", "Трудолюбивый человек", "👤", "Человек", 18, "Велия", "Свободен", 23, 23, false),
            new WorkerData("w3", "Крепкий гигант", "🗿", "Гигант", 30, "Хайдель", "Ковка в мастерской", 12, 35, true)
    ));

    public enum HousePurpose {
        STORAGE("Склад", "Увеличивает вместимость городского склада", "📦", 5),
        SMELTERY("Плавильня", "Переплавка руд и слитков металлов", "🔥", 5),
        WORKSHOP("Мастерская", "Изготовление инструментов и снаряжения", "⚙", 5),
        LODGING("Барак рабочих", "Предоставляет жильё для наёмных рабочих", "🛏", 3),
        FORGE("Кузница", "Ковка улучшений и специализированных деталей", "🔨", 5);

        public final String title;
        public final String desc;
        public final String icon;
        public final int maxLevel;

        HousePurpose(String title, String desc, String icon, int maxLevel) {
            this.title = title;
            this.desc = desc;
            this.icon = icon;
            this.maxLevel = maxLevel;
        }

        public String getEffectDescription(int level) {
            return switch (this) {
                case STORAGE -> "+" + (level * 6) + " слотов городского склада";
                case SMELTERY -> switch (level) {
                    case 1 -> "Переплавка: Медь и Железо";
                    case 2 -> "Переплавка: Золото и Олово";
                    case 3 -> "Переплавка: Сталь и Бронза";
                    case 4 -> "Переплавка: Мифрил и Титан";
                    default -> "Переплавка: Незерит и Редкие сплавы";
                };
                case WORKSHOP -> switch (level) {
                    case 1 -> "Ремесло: Базовые инструменты";
                    case 2 -> "Ремесло: Улучшенные инструменты";
                    case 3 -> "Ремесло: Механизмы и тележки";
                    case 4 -> "Ремесло: Продвинутые станки";
                    default -> "Ремесло: Мастерские шедевры";
                };
                case LODGING -> "+" + level + " мест(а) для наёмных рабочих";
                case FORGE -> switch (level) {
                    case 1 -> "Ковка: Начальная обработка";
                    case 2 -> "Ковка: Закалённая сталь";
                    case 3 -> "Ковка: Зачарованное снаряжение";
                    case 4 -> "Ковка: Артефактная ковка";
                    default -> "Ковка: Легендарное оружие";
                };
            };
        }
    }

    public static final class TownHouseData {
        public final String id;
        public final String name;
        public final float relX;
        public final float relZ;
        public HousePurpose purpose;
        public int level;
        public AnchorWidget anchor;

        public TownHouseData(String id, String name, float relX, float relZ, HousePurpose purpose, int level) {
            this.id = id;
            this.name = name;
            this.relX = relX;
            this.relZ = relZ;
            this.purpose = purpose;
            this.level = level;
        }
    }

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

        // В одиночной игре гарантируем расчет маршрутов между существующими узлами по топологии графа
        if (minecraft.getSingleplayerServer() != null && minecraft.level != null && knots != null) {
            try {
                ServerLevel sLevel = minecraft.getSingleplayerServer().getLevel(minecraft.level.dimension());
                if (sLevel != null) {
                    ChunkGenerator gen = sLevel.getChunkSource().getGenerator();
                    RandomState state = sLevel.getChunkSource().randomState();
                    KnotNetworkGraph.rebuildNetwork(knots, gen, sLevel, state);
                }
            } catch (Throwable ignored) {
            }
        }

        DefaultUIContext context = new DefaultUIContext(new MinecraftClipboardService());
        UnityLikeUIScaleProvider scale = new UnityLikeUIScaleProvider()
                .referenceResolution(1920.0f, 1080.0f)
                .matchBalanced()
                .scaleRange(0.75f, 6.0f)
                .userScale(2.15f);
        context.scaleProvider(scale);
//        context.debugFlags(DebugFlags.PROFILER_OVERLAY);

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

            @Override
            public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
                // 1. Ctrl + O: переключение DebugFlags.PROFILER_OVERLAY
                if (keyCode == GLFW.GLFW_KEY_O && Screen.hasControlDown()) {
                    if ((context.debugFlags() & DebugFlags.PROFILER_OVERLAY) != 0) {
                        context.disableDebugFlags(DebugFlags.PROFILER_OVERLAY);
                    } else {
                        context.enableDebugFlags(DebugFlags.PROFILER_OVERLAY);
                    }
                    return true;
                }

                // 2. Ctrl + R: принудительный пересчет всех маршрутов дорог (сброс кэша путей)
                if (keyCode == GLFW.GLFW_KEY_R && Screen.hasControlDown()) {
                    if (currentKnotsCache != null && minecraft.getSingleplayerServer() != null && minecraft.level != null) {
                        try {
                            ServerLevel sLevel = minecraft.getSingleplayerServer().getLevel(minecraft.level.dimension());
                            if (sLevel != null) {
                                ChunkGenerator gen = sLevel.getChunkSource().getGenerator();
                                RandomState state = sLevel.getChunkSource().randomState();
                                for (WorldObject obj : currentKnotsCache) {
                                    obj.getSingle(KnotRoutesComponent.class).ifPresent(r -> r.routes.get().clear());
                                    obj.getSingle(KnotConnectionComponent.class).ifPresent(c -> c.neighbors.get().clear());
                                }
                                KnotNetworkGraph.rebuildNetwork(currentKnotsCache, gen, sLevel, state);
                                if (activeStatus != null) {
                                    activeStatus.text("Сеть узлов пересчитана и строится заново по графу...");
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }

                // 3. Escape: закрытие поиска -> закрытие окна склада -> закрытие окна рабочих -> закрытие окна дома -> выход из режима города -> закрытие карты
                if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                    if (activeSearchPanel != null && activeSearchPanel.visible()) {
                        activeSearchPanel.visible(false);
                        return true;
                    }
                    if (activeStorageWindow != null && activeStorageWindow.opened()) {
                        activeStorageWindow.close();
                        return true;
                    }
                    if (activeWorkerWindow != null && activeWorkerWindow.opened()) {
                        activeWorkerWindow.close();
                        return true;
                    }
                    if (activeHouseWindow != null && activeHouseWindow.opened()) {
                        activeHouseWindow.close();
                        return true;
                    }
                    if (currentSelectedTown != null) {
                        exitTownMode(activeCanvas, activeStatus);
                        return true;
                    }
                }

                return super.keyPressed(keyCode, scanCode, modifiers);
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

        WorldCanvas canvas = new WorldCanvas() {
            @Override
            public void handle(Event event) {
                super.handle(event);
                if (event instanceof PointerMovedEvent pme) {
                    clampTownViewport(this, pme.rootX(), pme.rootY());
                } else if (event instanceof ScrollEvent) {
                    clampTownViewport(this, 0.0f, 0.0f);
                }
            }
        }
        .viewport(offsetX, offsetY, initialZoom)
        .zoomRange(0.03f, 4.0f)
        .wheelPanStep(36.0f);
        canvas.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));

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
                Optional<KnotRoutesComponent> rOpt = obj.getSingle(KnotRoutesComponent.class);
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
                        boolean active = fromInvested && Boolean.TRUE.equals(investedMap.get(toId));
                        Optional<KnotRouteEntry> routeOpt = rOpt.flatMap(r -> r.getRouteTo(toId));

                        if (showRoadPaths && routeOpt.isPresent() && routeOpt.get().waypoints().size() >= 2) {
                            KnotRouteEntry route = routeOpt.get();
                            ObjectList<RoadPoint2D> pts = route.waypoints();

                            if (route.isSeaRoute()) {
                                // Морской фарватер: пунктирная лазурная линия
                                MutableColor seaColor = active
                                        ? new MutableColor(0.20f, 0.85f, 0.95f, 0.95f)
                                        : new MutableColor(0.30f, 0.60f, 0.80f, 0.60f);
                                for (int i = 0; i < pts.size() - 1; i++) {
                                    RoadPoint2D p1 = pts.get(i);
                                    RoadPoint2D p2 = pts.get(i + 1);
                                    float x1 = world.worldToRootX((float) p1.x());
                                    float y1 = world.worldToRootY((float) p1.z());
                                    float x2 = world.worldToRootX((float) p2.x());
                                    float y2 = world.worldToRootY((float) p2.z());
                                    drawDashedLine(draw, x1, y1, x2, y2, seaColor, 2.5f, 8.0f, 5.0f);
                                }
                            } else {
                                // Сухопутная дорога: полилиния по точкам (X, Z)
                                MutableColor roadGlow = new MutableColor(1.0f, 0.65f, 0.15f, 0.35f);
                                MutableColor roadColor = active
                                        ? new MutableColor(1.0f, 0.85f, 0.35f, 0.95f)
                                        : new MutableColor(0.60f, 0.55f, 0.45f, 0.70f);

                                for (int i = 0; i < pts.size() - 1; i++) {
                                    RoadPoint2D p1 = pts.get(i);
                                    RoadPoint2D p2 = pts.get(i + 1);
                                    float x1 = world.worldToRootX((float) p1.x());
                                    float y1 = world.worldToRootY((float) p1.z());
                                    float x2 = world.worldToRootX((float) p2.x());
                                    float y2 = world.worldToRootY((float) p2.z());

                                    boolean isBridge = p1.isBridge() && p2.isBridge();
                                    if (isBridge) {
                                        // Опоры моста по краям на берегу
                                        draw.addCircleFilled(x1, y1, 3.2f, new MutableColor(0.28f, 0.20f, 0.14f, 1.0f), 8);
                                        draw.addCircleFilled(x2, y2, 3.2f, new MutableColor(0.28f, 0.20f, 0.14f, 1.0f), 8);

                                        // Темная балка моста (подложка / перила)
                                        draw.addLine(x1, y1, x2, y2, new MutableColor(0.24f, 0.16f, 0.10f, 0.95f), active ? 5.5f : 4.5f);
                                        // Деревянный настил моста
                                        MutableColor bridgeDeck = active
                                                ? new MutableColor(1.0f, 0.85f, 0.45f, 1.0f)
                                                : new MutableColor(0.88f, 0.68f, 0.42f, 0.95f);
                                        draw.addLine(x1, y1, x2, y2, bridgeDeck, active ? 3.0f : 2.2f);
                                    } else {
                                        if (active) {
                                            draw.addLine(x1, y1, x2, y2, roadGlow, 5.5f);
                                        }
                                        draw.addLine(x1, y1, x2, y2, roadColor, active ? 3.0f : 2.0f);
                                    }
                                }
                            }
                        } else {
                            // Логическая сеть узлов: прямая связь
                            float x1 = world.worldToRootX((float) fromPos.getX());
                            float y1 = world.worldToRootY((float) fromPos.getZ());
                            float x2 = world.worldToRootX((float) toPos.getX());
                            float y2 = world.worldToRootY((float) toPos.getZ());

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
            }

            // === 5. ПСЕВДО-ЛИНИИ СОЕДИНЕНИЙ (ДЛЯ ТЕСТА И ПЛАНИРОВАНИЯ МАРШРУТОВ) ===
            MutableColor pseudoColor = new MutableColor(0.45f, 0.65f, 0.85f, 0.40f);
            MutableColor pseudoPointColor = new MutableColor(0.45f, 0.65f, 0.85f, 0.60f);

            for (PseudoEdge edge : pseudoEdges) {
                if (drawnEdges.contains(edge.key())) continue;

                if (showRoadPaths && edge.waypoints() != null && edge.waypoints().size() >= 2) {
                    ObjectList<RoadPoint2D> pts = edge.waypoints();
                    if (edge.isSea()) {
                        MutableColor pseudoSeaColor = new MutableColor(0.30f, 0.70f, 0.90f, 0.50f);
                        for (int i = 0; i < pts.size() - 1; i++) {
                            RoadPoint2D p1 = pts.get(i);
                            RoadPoint2D p2 = pts.get(i + 1);
                            float x1 = world.worldToRootX((float) p1.x());
                            float y1 = world.worldToRootY((float) p1.z());
                            float x2 = world.worldToRootX((float) p2.x());
                            float y2 = world.worldToRootY((float) p2.z());
                            drawDashedLine(draw, x1, y1, x2, y2, pseudoSeaColor, 1.8f, 7.0f, 5.0f);
                        }
                    } else {
                        MutableColor pseudoRoadColor = new MutableColor(0.55f, 0.60f, 0.68f, 0.55f);
                        for (int i = 0; i < pts.size() - 1; i++) {
                            RoadPoint2D p1 = pts.get(i);
                            RoadPoint2D p2 = pts.get(i + 1);
                            float x1 = world.worldToRootX((float) p1.x());
                            float y1 = world.worldToRootY((float) p1.z());
                            float x2 = world.worldToRootX((float) p2.x());
                            float y2 = world.worldToRootY((float) p2.z());

                            boolean isBridge = p1.isBridge() && p2.isBridge();
                            if (isBridge) {
                                draw.addCircleFilled(x1, y1, 2.5f, new MutableColor(0.28f, 0.20f, 0.14f, 0.85f), 8);
                                draw.addCircleFilled(x2, y2, 2.5f, new MutableColor(0.28f, 0.20f, 0.14f, 0.85f), 8);
                                draw.addLine(x1, y1, x2, y2, new MutableColor(0.24f, 0.16f, 0.10f, 0.80f), 3.8f);
                                draw.addLine(x1, y1, x2, y2, new MutableColor(0.78f, 0.62f, 0.40f, 0.90f), 2.0f);
                            } else {
                                draw.addLine(x1, y1, x2, y2, pseudoRoadColor, 1.8f);
                            }
                        }
                    }
                } else {
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

                if (kType == KnotType.TOWN) {
                    // Зона юрисдикции города (отображается вокруг города)
                    float zoneRadius = 45.0f * world.viewport().zoom();
                    draw.addCircle(kx, ky, zoneRadius,
                            new MutableColor(0.25f, 0.65f, 1.0f, 0.30f), 32, 1.5f);
                    draw.addCircleFilled(kx, ky, zoneRadius,
                            new MutableColor(0.20f, 0.55f, 0.90f, 0.06f), 32);

                    if (currentSelectedTown == obj) {
                        // Граница ограничения камеры (3 чанка = 48 блоков вокруг центра)
                        float limitRadius = TOWN_PAN_LIMIT * world.viewport().zoom();
                        draw.addCircle(kx, ky, limitRadius,
                                new MutableColor(1.0f, 0.85f, 0.30f, 0.75f), 48, 2.0f);
                        draw.addCircleFilled(kx, ky, limitRadius,
                                new MutableColor(1.0f, 0.80f, 0.20f, 0.05f), 48);
                    }

                    // Если город прибрежный (порт) - рисуем маркер причала у воды
                    Optional<TownPortComponent> portOpt = obj.getSingle(TownPortComponent.class);
                    if (portOpt.isPresent()) {
                        BlockPos pier = portOpt.get().pierPos.get();
                        float px = world.worldToRootX((float) pier.getX());
                        float py = world.worldToRootY((float) pier.getZ());

                        // Причал / верфь на берегу
                        draw.addCircleFilled(px, py, 5.0f, new MutableColor(0.20f, 0.85f, 0.95f, 0.85f), 12);
                        draw.addCircle(px, py, 7.5f, new MutableColor(1.0f, 1.0f, 1.0f, 0.70f), 12, 1.2f);

                        // Пунктирный съезд от центра поселения к причалу
                        drawDashedLine(draw, kx, ky, px, py, new MutableColor(0.30f, 0.80f, 0.95f, 0.50f), 1.5f, 4.0f, 3.0f);
                    }
                }

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

        // Сохраняем ссылки для горячих клавиш и интерактивных переключений
        activeCanvas = canvas;
        activeStatus = null;
        currentSelectedTown = null;
        currentKnotsCache = knotList;
        HOUSE_TOOLTIPS.clear();

        // Слой базового UI интерфейса (плашки, левая панель, окна и поиск)
        OverlayLayer uiLayer = new OverlayLayer(canvas);
        uiLayer.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));
        activeUiLayer = uiLayer;

        // Полноэкранный корневой оверлей, в котором поверх uiLayer отрисовываются ВСЕ всплывающие подсказки (Tooltips)
        OverlayLayer rootOverlay = new OverlayLayer(uiLayer);
        rootOverlay.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));
        activeRootOverlay = rootOverlay;

        // 0. Верхняя компактная плашка BDO: Поиск (loupe.png) | Фильтр (filter.png) | Очки влияния N/MAX | Рабочие (pickaxe.png)
        Box topBadge = new Box();
        topBadge.backgroundVisible(true);
        topBadge.borderVisible(true);
        topBadge.background().set(0.05f, 0.06f, 0.09f, 0.92f);
        topBadge.borderColor().set(0.40f, 0.50f, 0.65f, 0.75f);
        topBadge.radius(5.0f);
        topBadge.borderWidth(1.2f);
        topBadge.layout(style -> style
                .position(PositionType.ABSOLUTE)
                .left(16.0f)
                .top(12.0f)
                .height(30.0f)
                .padding(2.0f, 4.0f, 2.0f, 4.0f)
                .overflow(Overflow.HIDDEN));

        HBox topBar = new HBox();
        topBar.spacing(4.0f);
        topBar.layout(style -> style
                .size(LayoutConstraints.AUTO, LayoutConstraints.AUTO)
                .alignItems(Align.CENTER));

        // 0.1 Кнопка Поиска (loupe.png)
        Button loupeBtn = new Button("");
        loupeBtn.layout(s -> s.width(28.0f).height(26.0f));
        loupeBtn.backgroundVisible(false);
        loupeBtn.borderVisible(false);
        loupeBtn.renderer((draw, w) -> {
            RectView b = w.layoutBounds();
            float x = b.x();
            float y = b.y();
            float width = b.width();
            float height = b.height();
            boolean hovered = loupeBtn.hovered();

            if (hovered) {
                draw.addRectFilled(x, y, width, height, 3.0f, new MutableColor(1.0f, 1.0f, 1.0f, 0.14f));
            }
            float sz = 18.0f;
            float ix = x + (width - sz) * 0.5f;
            float iy = y + (height - sz) * 0.5f;
            MutableColor tint = hovered ? new MutableColor(1.0f, 0.90f, 0.45f, 1.0f) : new MutableColor(0.85f, 0.88f, 0.92f, 0.90f);
            draw.addImage(ICON_LOUPE, ix, iy, sz, sz, tint);
        });

        Tooltip loupeTooltip = new Tooltip(loupeBtn, "Поиск городов и узлов");
        loupeTooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
        loupeTooltip.borderColor().set(0.85f, 0.70f, 0.25f, 0.90f);
        loupeTooltip.radius(4.0f);
        loupeTooltip.borderWidth(1.2f);
        loupeTooltip.offset(4.0f, 6.0f);
        rootOverlay.addOverlay(loupeTooltip);
        topBar.addChild(loupeBtn);

        topBar.addChild(createTopBarDivider());

        // 0.2 Кнопка Фильтра (filter.png)
        Button filterBtn = new Button("");
        filterBtn.layout(s -> s.width(28.0f).height(26.0f));
        filterBtn.backgroundVisible(false);
        filterBtn.borderVisible(false);
        filterBtn.renderer((draw, w) -> {
            RectView b = w.layoutBounds();
            float x = b.x();
            float y = b.y();
            float width = b.width();
            float height = b.height();
            boolean hovered = filterBtn.hovered();

            if (hovered) {
                draw.addRectFilled(x, y, width, height, 3.0f, new MutableColor(1.0f, 1.0f, 1.0f, 0.14f));
            }
            float sz = 18.0f;
            float ix = x + (width - sz) * 0.5f;
            float iy = y + (height - sz) * 0.5f;
            MutableColor tint = hovered ? new MutableColor(1.0f, 0.90f, 0.45f, 1.0f) : new MutableColor(0.85f, 0.88f, 0.92f, 0.90f);
            draw.addImage(ICON_FILTER, ix, iy, sz, sz, tint);
        });

        Tooltip filterTooltip = new Tooltip(filterBtn, "Фильтры узлов (В разработке)");
        filterTooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
        filterTooltip.borderColor().set(0.85f, 0.70f, 0.25f, 0.90f);
        filterTooltip.radius(4.0f);
        filterTooltip.borderWidth(1.2f);
        filterTooltip.offset(4.0f, 6.0f);
        rootOverlay.addOverlay(filterTooltip);
        topBar.addChild(filterBtn);

        topBar.addChild(createTopBarDivider());

        // 0.25 Кнопка переключения режима дорог и путей
        Button roadModeBtn = new Button("");
        roadModeBtn.layout(s -> s.width(28.0f).height(26.0f));
        roadModeBtn.backgroundVisible(false);
        roadModeBtn.borderVisible(false);
        roadModeBtn.renderer((draw, w) -> {
            RectView b = w.layoutBounds();
            float x = b.x();
            float y = b.y();
            float width = b.width();
            float height = b.height();
            boolean hovered = roadModeBtn.hovered();

            if (showRoadPaths) {
                draw.addRectFilled(x, y, width, height, 3.0f, new MutableColor(1.0f, 0.80f, 0.20f, 0.25f));
                draw.addRect(x, y, width, height, 3.0f, new MutableColor(1.0f, 0.85f, 0.35f, 0.85f), 1.0f);
            } else if (hovered) {
                draw.addRectFilled(x, y, width, height, 3.0f, new MutableColor(1.0f, 1.0f, 1.0f, 0.14f));
            }

            float sz = 18.0f;
            float ix = x + (width - sz) * 0.5f;
            float iy = y + (height - sz) * 0.5f;
            MutableColor tint = showRoadPaths
                    ? new MutableColor(1.0f, 0.88f, 0.30f, 1.0f)
                    : (hovered ? new MutableColor(1.0f, 0.90f, 0.45f, 1.0f) : new MutableColor(0.85f, 0.88f, 0.92f, 0.90f));
            draw.addImage(ICON_NODE_KNOT, ix, iy, sz, sz, tint);
        });

        Tooltip roadModeTooltip = new Tooltip(roadModeBtn, "Режим отображения: Логическая сеть узлов (Нажмите для включения реальных дорог)");
        roadModeTooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
        roadModeTooltip.borderColor().set(0.85f, 0.70f, 0.25f, 0.90f);
        roadModeTooltip.radius(4.0f);
        roadModeTooltip.borderWidth(1.2f);
        roadModeTooltip.offset(4.0f, 6.0f);
        rootOverlay.addOverlay(roadModeTooltip);

        roadModeBtn.onClick(e -> {
            showRoadPaths = !showRoadPaths;
            roadModeTooltip.richText(RichText.builder().append(showRoadPaths
                    ? "Режим отображения: Реальные дороги и фарватеры (Активен)"
                    : "Режим отображения: Логическая сеть узлов (BDO)").build());
            roadModeTooltip.invalidate(InvalidationFlags.LAYOUT | InvalidationFlags.VISUAL);
        });
        topBar.addChild(roadModeBtn);

        topBar.addChild(createTopBarDivider());

        // 0.3 Секция Очков Влияния (BDO style)
        activeCpBar = new Box();
        activeCpBar.backgroundVisible(false);
        activeCpBar.borderVisible(false);
        activeCpBar.layout(style -> style
                .height(26.0f)
                .padding(2.0f, 8.0f, 2.0f, 8.0f)
                .justifyContent(Justify.CENTER)
                .alignItems(Align.CENTER));

        HBox cpRow = new HBox();
        cpRow.spacing(6.0f);
        cpRow.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).alignItems(Align.CENTER));

        Label cpTitle = new Label("✨ Очки влияния");
        cpTitle.color(new MutableColor(0.82f, 0.85f, 0.90f, 0.95f));
        cpRow.addChild(cpTitle);

        int initialAvailCP = Math.max(0, TOTAL_PLAYER_MAX_CP - calculateTotalUsedCP());
        activeCpLabel = new Label(initialAvailCP + " / " + TOTAL_PLAYER_MAX_CP);
        activeCpLabel.color(new MutableColor(1.0f, 0.80f, 0.22f, 1.0f));
        cpRow.addChild(activeCpLabel);

        activeCpBar.addChild(cpRow);

        activeCpBar.renderer((draw, w) -> {
            RectView b = w.layoutBounds();
            float x = b.x();
            float y = b.y();
            float width = b.width();
            float height = b.height();

            // Тёмная подложка
            draw.addRectFilled(x, y, width, height, 3.0f, new MutableColor(0.03f, 0.04f, 0.07f, 0.65f));

            // Тонкая аккуратная полоска прогресса снизу
            int usedCP = calculateTotalUsedCP();
            int availCP = Math.max(0, TOTAL_PLAYER_MAX_CP - usedCP);
            float ratio = Math.clamp((float) availCP / (float) TOTAL_PLAYER_MAX_CP, 0.0f, 1.0f);
            if (ratio > 0.0f) {
                float fillW = (width - 4.0f) * ratio;
                draw.addRectFilled(x + 2.0f, y + height - 2.5f, fillW, 1.8f, 1.0f,
                        new MutableColor(0.20f, 0.70f, 1.0f, 0.85f));
            }
        });

        activeCpTooltip = new Tooltip(activeCpBar, buildCpTooltip());
        activeCpTooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
        activeCpTooltip.borderColor().set(0.85f, 0.70f, 0.25f, 0.90f);
        activeCpTooltip.radius(4.0f);
        activeCpTooltip.borderWidth(1.2f);
        activeCpTooltip.offset(4.0f, 6.0f);
        activeCpTooltip.maxWidth(260.0f);
        activeCpBar.on(PointerEnteredEvent.TYPE, e -> {
            activeCpTooltip.richText(buildCpTooltip());
            activeCpTooltip.invalidate(InvalidationFlags.LAYOUT | InvalidationFlags.VISUAL);
        });
        rootOverlay.addOverlay(activeCpTooltip);
        topBar.addChild(activeCpBar);

        topBar.addChild(createTopBarDivider());

        // 0.4 Кнопка Рабочих (pickaxe.png)
        Button workerBtn = new Button("");
        workerBtn.layout(s -> s.width(28.0f).height(26.0f));
        workerBtn.backgroundVisible(false);
        workerBtn.borderVisible(false);
        workerBtn.renderer((draw, w) -> {
            RectView b = w.layoutBounds();
            float x = b.x();
            float y = b.y();
            float width = b.width();
            float height = b.height();
            boolean hovered = workerBtn.hovered();

            if (hovered) {
                draw.addRectFilled(x, y, width, height, 3.0f, new MutableColor(1.0f, 1.0f, 1.0f, 0.14f));
            }
            float sz = 18.0f;
            float ix = x + (width - sz) * 0.5f;
            float iy = y + (height - sz) * 0.5f;
            MutableColor tint = hovered ? new MutableColor(1.0f, 0.90f, 0.45f, 1.0f) : new MutableColor(0.85f, 0.88f, 0.92f, 0.90f);
            draw.addImage(ICON_PICKAXE, ix, iy, sz, sz, tint);
        });
        workerBtn.onClick(e -> openWorkerWindow());

        Tooltip workerTooltip = new Tooltip(workerBtn, buildWorkerTooltip());
        workerTooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
        workerTooltip.borderColor().set(0.40f, 0.75f, 1.0f, 0.90f);
        workerTooltip.radius(4.0f);
        workerTooltip.borderWidth(1.2f);
        workerTooltip.offset(4.0f, 6.0f);
        workerTooltip.maxWidth(260.0f);
        workerBtn.on(PointerEnteredEvent.TYPE, e -> {
            workerTooltip.richText(buildWorkerTooltip());
            workerTooltip.invalidate(InvalidationFlags.LAYOUT | InvalidationFlags.VISUAL);
        });
        rootOverlay.addOverlay(workerTooltip);
        topBar.addChild(workerBtn);

        topBadge.addChild(topBar);
        uiLayer.addOverlay(topBadge);

        // 0.5 Выпадающая панель поиска городов и узлов
        activeSearchPanel = new Box();
        activeSearchPanel.backgroundVisible(true);
        activeSearchPanel.borderVisible(true);
        activeSearchPanel.background().set(0.05f, 0.06f, 0.09f, 0.96f);
        activeSearchPanel.borderColor().set(0.85f, 0.70f, 0.25f, 0.85f);
        activeSearchPanel.radius(6.0f);
        activeSearchPanel.borderWidth(1.5f);
        activeSearchPanel.layout(style -> style
                .position(PositionType.ABSOLUTE)
                .left(16.0f)
                .top(48.0f)
                .width(280.0f)
                .height(240.0f)
                .padding(8.0f)
                .overflow(Overflow.HIDDEN));
        activeSearchPanel.visible(false);

        VBox searchContainer = new VBox();
        searchContainer.spacing(6.0f);
        searchContainer.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));

        SearchField searchField = new SearchField();
        searchField.placeholder("Поиск узлов и городов...");
        searchField.layout(s -> s.height(24.0f));
        searchContainer.addChild(searchField);

        VBox searchResultsBox = new VBox();
        searchResultsBox.spacing(4.0f);
        searchResultsBox.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f));

        ScrollView searchScroll = new ScrollView(searchResultsBox)
                .scrollStep(20.0f)
                .scrollbarGap(2.0f);
        searchScroll.layout(style -> style
                .size(LayoutConstraints.AUTO, LayoutConstraints.AUTO)
                .flexGrow(1.0f)
                .flexShrink(1.0f)
                .overflowX(Overflow.HIDDEN)
                .overflowY(Overflow.AUTO));
        searchContainer.addChild(searchScroll);

        activeSearchPanel.addChild(searchContainer);

        loupeBtn.onClick(e -> {
            boolean nowVis = !activeSearchPanel.visible();
            activeSearchPanel.visible(nowVis);
            if (nowVis) {
                uiLayer.removeOverlay(activeSearchPanel);
                uiLayer.addOverlay(activeSearchPanel);
                searchField.text("");
                populateSearchResults("", searchResultsBox, canvas);
            }
        });

        searchField.onSearchChanged(e -> {
            populateSearchResults(e.newQuery(), searchResultsBox, canvas);
        });

        searchField.onSearchSubmitted(e -> {
            if (!searchResultsBox.children().isEmpty() && searchResultsBox.children().get(0) instanceof Button firstBtn) {
                firstBtn.click();
            }
        });

        // 1. Левая панель управления городом / инвестициями (BDO Style)
        activeLeftPanel = new Box();
        activeLeftPanel.backgroundVisible(true);
        activeLeftPanel.borderVisible(true);
        activeLeftPanel.background().set(0.05f, 0.06f, 0.09f, 0.94f);
        activeLeftPanel.borderColor().set(0.85f, 0.70f, 0.25f, 0.85f);
        activeLeftPanel.radius(6.0f);
        activeLeftPanel.borderWidth(1.5f);
        activeLeftPanel.layout(style -> style
                .position(PositionType.ABSOLUTE)
                .left(16.0f)
                .top(48.0f)
                .bottom(14.0f)
                .width(280.0f)
                .padding(8.0f)
                .overflow(Overflow.HIDDEN));
        activeLeftPanel.visible(false);

        VBox panelContainer = new VBox();
        panelContainer.spacing(6.0f);
        panelContainer.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));

        activeLeftContent = new VBox();
        activeLeftContent.spacing(4.0f);
        activeLeftContent.layout(style -> style.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f));

        ScrollView leftScroll = new ScrollView(activeLeftContent)
                .scrollStep(20.0f)
                .scrollbarGap(2.0f);
        leftScroll.layout(style -> style
                .size(LayoutConstraints.AUTO, LayoutConstraints.AUTO)
                .flexGrow(1.0f)
                .flexShrink(1.0f)
                .overflowX(Overflow.HIDDEN)
                .overflowY(Overflow.AUTO));
        panelContainer.addChild(leftScroll);

        Button backBtn = new Button("← Назад к карте мира");
        backBtn.layout(s -> s.height(26.0f).flexShrink(0.0f));
        backBtn.onClick(e -> {
            exitTownMode(canvas, null);
        });
        panelContainer.addChild(backBtn);

        activeLeftPanel.addChild(panelContainer);
        uiLayer.addOverlay(activeLeftPanel);

        // 2. Движимое окно управления недвижимостью/домами (BDO Style)
        activeHouseWindow = new WindowWidget();
        activeHouseWindow.title("Управление недвижимостью");
        activeHouseWindow.minWindowSize(320.0f, 360.0f);
        activeHouseWindow.layout(style -> style
                .position(PositionType.ABSOLUTE)
                .left(310.0f)
                .top(28.0f)
                .size(340.0f, 410.0f));
        activeHouseWindow.background().set(0.06f, 0.07f, 0.10f, 0.96f);
        activeHouseWindow.borderColor().set(0.85f, 0.70f, 0.25f, 0.90f);
        activeHouseWindow.headerColor().set(0.10f, 0.12f, 0.16f, 0.98f);
        activeHouseWindow.radius(6.0f);
        activeHouseWindow.borderWidth(1.5f);
        activeHouseWindow.close();
        uiLayer.addOverlay(activeHouseWindow);

        // 3. Движимое окно управления наёмными рабочими (BDO Style)
        activeWorkerWindow = new WindowWidget();
        activeWorkerWindow.title("Управление наёмными рабочими");
        activeWorkerWindow.minWindowSize(340.0f, 380.0f);
        activeWorkerWindow.layout(style -> style
                .position(PositionType.ABSOLUTE)
                .left(310.0f)
                .top(28.0f)
                .size(360.0f, 430.0f));
        activeWorkerWindow.background().set(0.06f, 0.07f, 0.10f, 0.96f);
        activeWorkerWindow.borderColor().set(0.40f, 0.75f, 1.0f, 0.90f);
        activeWorkerWindow.headerColor().set(0.10f, 0.14f, 0.20f, 0.98f);
        activeWorkerWindow.radius(6.0f);
        activeWorkerWindow.borderWidth(1.5f);
        activeWorkerWindow.close();
        uiLayer.addOverlay(activeWorkerWindow);

        // 4. Движимое окно склада города (BDO Style)
        activeStorageWindow = new WindowWidget();
        activeStorageWindow.title("Склад города");
        activeStorageWindow.minWindowSize(360.0f, 420.0f);
        activeStorageWindow.layout(style -> style
                .position(PositionType.ABSOLUTE)
                .left(310.0f)
                .top(28.0f)
                .size(380.0f, 480.0f));
        activeStorageWindow.background().set(0.06f, 0.07f, 0.10f, 0.96f);
        activeStorageWindow.borderColor().set(0.85f, 0.70f, 0.25f, 0.90f);
        activeStorageWindow.headerColor().set(0.10f, 0.13f, 0.18f, 0.98f);
        activeStorageWindow.radius(6.0f);
        activeStorageWindow.borderWidth(1.5f);
        activeStorageWindow.onClosed(e -> clearStorageTooltips());
        activeStorageWindow.close();
        uiLayer.addOverlay(activeStorageWindow);

        // Выпадающая панель поиска добавляется последней в uiLayer, гарантируя Z-порядок выше панелей и окон
        uiLayer.addOverlay(activeSearchPanel);

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
                    draw.addCircleFilled(x + w0 * 0.5f, y + h0 * 0.45f, w0 * 0.55f,
                            new MutableColor(1.0f, 0.90f, 0.40f, 0.35f), 16);
                    draw.addImage(icon, x - 1.0f, y - 2.0f, w0 + 2.0f, h0 + 2.0f,
                            new MutableColor(1.0f, 1.0f, 0.85f, 1.0f));
                } else {
                    draw.addImage(icon, x, y, w0, h0, COLOR_WHITE);
                }
            });

            Tooltip tooltip = new Tooltip(marker, buildNodeTooltip(meta, connOpt, prodOpt,
                    knot.getSingle(TownPortComponent.class), knot.getSingle(KnotRoutesComponent.class)));
            tooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
            tooltip.borderColor().set(0.85f, 0.70f, 0.25f, 0.90f);
            tooltip.radius(4.0f);
            tooltip.borderWidth(1.2f);
            tooltip.offset(4.0f, 6.0f);
            tooltip.maxWidth(260.0f);

            marker.on(PointerEnteredEvent.TYPE, event -> {
                tooltip.invalidate(InvalidationFlags.LAYOUT | InvalidationFlags.VISUAL);
            });

            if (type == KnotType.TOWN) {
                // При клике на город: переход в режим города (BDO), приближение камеры к городу, открытие левой панели
                marker.onClick(event -> {
                    enterTownView(knot, canvas, null);
                });

                // Создаем домики (icon_node_group_house) в зоне города
                List<TownHouseData> houses = getOrCreateTownHouses(knot);
                for (TownHouseData house : houses) {
                    Button houseBtn = new Button("");
                    houseBtn.renderer((draw, w) -> {
                        dev.sixik.unigui.api.math.RectView b = w.layoutBounds();
                        float x = b.x();
                        float y = b.y();
                        float w0 = b.width();
                        float h0 = b.height();

                        boolean hovered = houseBtn.hovered();
                        if (hovered) {
                            draw.addCircleFilled(x + w0 * 0.5f, y + h0 * 0.5f, w0 * 0.55f,
                                    new MutableColor(0.30f, 0.80f, 1.0f, 0.35f), 16);
                            draw.addImage(ICON_NODE_HOUSE, x - 1.0f, y - 1.0f, w0 + 2.0f, h0 + 2.0f,
                                    new MutableColor(1.0f, 1.0f, 1.0f, 1.0f));
                        } else {
                            draw.addImage(ICON_NODE_HOUSE, x, y, w0, h0, COLOR_WHITE);
                        }
                    });

                    Tooltip houseTooltip = new Tooltip(houseBtn, buildHouseTooltip(house));
                    houseTooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
                    houseTooltip.borderColor().set(0.35f, 0.75f, 1.0f, 0.90f);
                    houseTooltip.radius(4.0f);
                    houseTooltip.borderWidth(1.2f);
                    houseTooltip.offset(4.0f, 6.0f);
                    houseTooltip.maxWidth(260.0f);

                    houseBtn.on(PointerEnteredEvent.TYPE, e -> {
                        houseTooltip.invalidate(InvalidationFlags.LAYOUT | InvalidationFlags.VISUAL);
                    });

                    houseBtn.onClick(e -> {
                        openHouseWindow(knot, house, canvas, null);
                    });

                    HOUSE_TOOLTIPS.put(house.id, houseTooltip);
                    rootOverlay.addOverlay(houseTooltip);

                    AnchorWidget houseAnchor = canvas.anchorLayer().add(
                            "house_" + house.id,
                            worldX + house.relX,
                            worldY + house.relZ,
                            houseBtn
                    )
                    .screenSize(28.0f, 28.0f)
                    .pivot(0.5f, 0.5f)
                    .visibleZoomRange(0.85f, 10.0f)
                    .visible(false);

                    house.anchor = houseAnchor;
                }
            } else {
                marker.onClick(event -> {
                    connOpt.ifPresent(conn -> conn.isInvested.update(v -> !v));
                    updateContributionPointsDisplay();
                    tooltip.richText(buildNodeTooltip(meta, connOpt, prodOpt,
                            knot.getSingle(TownPortComponent.class), knot.getSingle(KnotRoutesComponent.class)));
                });
            }

            float pinW = 32.0f;
            float pinH = 36.0f;
            canvas.anchorLayer().add("knot_" + knotId, worldX, worldY, marker)
                    .screenSize(pinW, pinH)
                    .pivot(0.5f, 1.0f);

            rootOverlay.addOverlay(tooltip);
        }

        canvas.onViewportChanged(event -> {
            clampTownViewport(canvas, 0.0f, 0.0f);
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
        return buildNodeTooltip(meta, connOpt, prodOpt, Optional.empty(), Optional.empty());
    }

    private static RichText buildNodeTooltip(
            KnotMetaComponent meta,
            Optional<KnotConnectionComponent> connOpt,
            Optional<KnotProductionComponent> prodOpt,
            Optional<TownPortComponent> portOpt,
            Optional<KnotRoutesComponent> routesOpt) {
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

        // Если узел является морским портом
        if (portOpt != null && portOpt.isPresent()) {
            BlockPos pier = portOpt.get().pierPos.get();
            builder.color(new MutableColor(0.20f, 0.85f, 0.95f, 1.0f))
                   .append(String.format(Locale.ROOT, "⚓ Port Pier: [%d, %d] (Ships: %d)\n",
                           pier.getX(), pier.getZ(), portOpt.get().shipCapacity.get()));
        }

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

        if (routesOpt != null && routesOpt.isPresent() && !routesOpt.get().routes.get().isEmpty()) {
            int routesCount = routesOpt.get().routes.get().size();
            builder.color(new MutableColor(0.85f, 0.75f, 0.40f, 1.0f))
                   .append("Physical Routes: " + routesCount + "\n");
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
    private record PseudoEdge(UUID a, UUID b, BlockPos posA, BlockPos posB, double distance,
                              ObjectList<RoadPoint2D> waypoints, boolean isSea) {
        public String key() {
            return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
        }
    }


    private static PseudoEdge createPseudoEdge(WorldObject objA, WorldObject objB, BlockPos posA, BlockPos posB, double dist) {
        Optional<KnotRoutesComponent> rOptA = objA.getSingle(KnotRoutesComponent.class);
        Optional<KnotRouteEntry> routeA = rOptA.flatMap(r -> r.getRouteTo(objB.id()));
        if (routeA.isPresent() && routeA.get().waypoints().size() >= 2) {
            KnotRouteEntry r = routeA.get();
            return new PseudoEdge(objA.id(), objB.id(), posA, posB, dist, r.waypoints(), r.isSeaRoute());
        }

        // Если путь еще не построен, но доступен сервер мира (singleplayer): запускаем асинхронный расчет без фриза UI
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null && mc.level != null) {
            try {
                ServerLevel sLevel = mc.getSingleplayerServer().getLevel(mc.level.dimension());
                if (sLevel != null) {
                    ChunkGenerator gen = sLevel.getChunkSource().getGenerator();
                    RandomState state = sLevel.getChunkSource().randomState();
                    RouteGenerator.connectKnotsAsync(objA, objB, gen, sLevel, state);
                }
            } catch (Throwable ignored) {
            }
        }

        // Прямая линия между узлами (без ложных кривых через реки/океаны)
        ObjectList<RoadPoint2D> directWaypoints = new ObjectArrayList<>();
        directWaypoints.add(new RoadPoint2D(posA.getX(), posA.getZ()));
        directWaypoints.add(new RoadPoint2D(posB.getX(), posB.getZ()));
        return new PseudoEdge(objA.id(), objB.id(), posA, posB, dist, directWaypoints, false);
    }

    private static List<PseudoEdge> buildPseudoEdges(List<WorldObject> knots) {
        if (knots == null || knots.size() < 2) return List.of();

        List<KnotNetworkGraph.GraphEdge> topology = KnotNetworkGraph.calculateTopology(knots);
        List<PseudoEdge> edges = new ArrayList<>();

        for (KnotNetworkGraph.GraphEdge edge : topology) {
            BlockPos pA = edge.a().getSingle(KnotMetaComponent.class).get().pos.get();
            BlockPos pB = edge.b().getSingle(KnotMetaComponent.class).get().pos.get();
            edges.add(createPseudoEdge(edge.a(), edge.b(), pA, pB, edge.distance()));
        }

        return edges;
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

        try {
            panDragField = WorldCanvas.class.getDeclaredField("panDrag");
            panDragField.setAccessible(true);
            for (Class<?> inner : WorldCanvas.class.getDeclaredClasses()) {
                if (inner.getSimpleName().equals("PanDragState")) {
                    panDragConstructor = inner.getDeclaredConstructor(int.class, float.class, float.class, float.class, float.class);
                    panDragConstructor.setAccessible(true);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // === BDO TOWN & HOUSE SYSTEM HELPER METHODS ===
    // =========================================================================

    private static List<TownHouseData> getOrCreateTownHouses(WorldObject town) {
        return TOWN_HOUSES.computeIfAbsent(town.id(), id -> {
            List<TownHouseData> list = new ArrayList<>();
            float[][] offsets = new float[][] {
                {-20.0f, -16.0f},
                {22.0f, -14.0f},
                {-18.0f, 22.0f},
                {24.0f, 18.0f},
                {4.0f, 30.0f}
            };
            HousePurpose[] defaultPurposes = new HousePurpose[] {
                HousePurpose.STORAGE,
                HousePurpose.LODGING,
                HousePurpose.WORKSHOP,
                HousePurpose.SMELTERY,
                HousePurpose.FORGE
            };
            int[] defaultLevels = new int[] {2, 1, 1, 1, 1};

            for (int i = 0; i < offsets.length; i++) {
                String houseId = town.id() + "_house_" + (i + 1);
                String houseName = "Дом " + (i + 1) + "-й";
                list.add(new TownHouseData(
                    houseId,
                    houseName,
                    offsets[i][0],
                    offsets[i][1],
                    defaultPurposes[i],
                    defaultLevels[i]
                ));
            }
            return list;
        });
    }

    private static RichText buildHouseTooltip(TownHouseData house) {
        RichText.Builder b = RichText.builder();
        b.color(new MutableColor(1.0f, 0.85f, 0.25f, 1.0f))
         .append(house.name + "\n");
        b.color(new MutableColor(0.35f, 0.80f, 1.0f, 1.0f))
         .append(house.purpose.icon + " " + house.purpose.title + " (Уровень " + house.level + "/" + house.purpose.maxLevel + ")\n");
        b.color(new MutableColor(0.85f, 0.90f, 0.95f, 1.0f))
         .append("Эффект: " + house.purpose.getEffectDescription(house.level) + "\n");
        b.color(new MutableColor(0.65f, 0.70f, 0.75f, 1.0f))
         .append(house.purpose.desc + "\n");
        b.color(new MutableColor(0.50f, 0.75f, 0.50f, 1.0f))
         .append("Клик: Управление недвижимостью");
        return b.build();
    }

    private static void updateHouseTooltip(TownHouseData house) {
        Tooltip tooltip = HOUSE_TOOLTIPS.get(house.id);
        if (tooltip != null) {
            tooltip.richText(buildHouseTooltip(house));
            tooltip.invalidate(InvalidationFlags.LAYOUT | InvalidationFlags.VISUAL);
        }
    }

    private static void setTownHousesVisible(WorldObject town, boolean visible) {
        if (town == null) return;
        List<TownHouseData> houses = TOWN_HOUSES.get(town.id());
        if (houses != null) {
            for (TownHouseData h : houses) {
                if (h.anchor != null) {
                    h.anchor.visible(visible);
                }
            }
        }
    }

    private static void setAllTownHousesVisible(boolean visible) {
        for (List<TownHouseData> list : TOWN_HOUSES.values()) {
            for (TownHouseData h : list) {
                if (h.anchor != null) {
                    h.anchor.visible(visible);
                }
            }
        }
    }

    private static void enterTownView(WorldObject town, WorldCanvas canvas, Label status) {
        if (town == null || canvas == null) return;

        // 1. Сохраняем предыдущий масштаб и позицию до перехода в режим города
        if (currentSelectedTown == null) {
            savedViewportX = canvas.viewport().x();
            savedViewportY = canvas.viewport().y();
            savedViewportZoom = canvas.viewport().zoom();
            hasSavedViewport = true;
        }

        currentSelectedTown = town;

        KnotMetaComponent meta = town.getSingle(KnotMetaComponent.class).orElse(null);
        if (meta == null) return;
        BlockPos pos = meta.pos.get();
        float worldX = (float) pos.getX();
        float worldY = (float) pos.getZ();

        // 2. Ограничиваем отдаление (максимальное отдаление ограничено зумом 0.85f)
        canvas.zoomRange(0.85f, 3.0f);

        // 3. Приближаем камеру и центрируем её с учетом левой панели (280px)
        float screenW = canvas.layoutBounds().width();
        float screenH = canvas.layoutBounds().height();
        if (screenW <= 10.0f) screenW = 1920.0f;
        if (screenH <= 10.0f) screenH = 1080.0f;

        float targetScreenX = (screenW + 280.0f) * 0.5f;
        float targetScreenY = screenH * 0.5f;
        float townZoom = 1.35f;

        float newX = targetScreenX - worldX * townZoom;
        float newY = targetScreenY - worldY * townZoom;
        canvas.viewport(newX, newY, townZoom);

        // 4. Скрываем домики других городов, включаем домики этого города
        setAllTownHousesVisible(false);
        setTownHousesVisible(town, true);

        // 5. Открываем левую панель управления городом
        if (activeLeftPanel != null) {
            activeLeftPanel.visible(true);
            updateTownLeftPanel(town, canvas, status);
        }

        if (status != null) {
            status.text(String.format(Locale.ROOT,
                    "Режим города: %s | Область ограничена 3 чанками вокруг | ESC / Кнопка назад для выхода",
                    meta.name.get()));
        }
    }

    private static void exitTownMode(WorldCanvas canvas, Label status) {
        currentSelectedTown = null;
        if (activeLeftPanel != null) {
            activeLeftPanel.visible(false);
        }
        if (activeStorageWindow != null && activeStorageWindow.opened()) {
            activeStorageWindow.close();
            clearStorageTooltips();
        }
        if (activeHouseWindow != null && activeHouseWindow.opened()) {
            activeHouseWindow.close();
        }
        setAllTownHousesVisible(false);

        if (canvas != null) {
            // Восстанавливаем полный диапазон зума карты мира
            canvas.zoomRange(0.03f, 4.0f);

            // Восстанавливаем сохраненный масштаб и позицию до перехода в город
            if (hasSavedViewport) {
                canvas.viewport(savedViewportX, savedViewportY, savedViewportZoom);
                hasSavedViewport = false;
            } else {
                float cx = canvas.layoutBounds().width() * 0.5f;
                float cy = canvas.layoutBounds().height() * 0.5f;
                if (cx <= 10.0f) cx = 1920.0f * 0.5f;
                if (cy <= 10.0f) cy = 1080.0f * 0.5f;
                float worldX = canvas.rootToWorldX(cx);
                float worldY = canvas.rootToWorldY(cy);
                float newZoom = 0.25f;
                canvas.viewport(cx - worldX * newZoom, cy - worldY * newZoom, newZoom);
            }
        }

        if (status != null) {
            status.text("Обзор карты мира. Выберите узел или город.");
        }
    }

    private static void clampTownViewport(WorldCanvas canvas, float eventRootX, float eventRootY) {
        if (currentSelectedTown == null || canvas == null || isClampingTownViewport) return;

        KnotMetaComponent meta = currentSelectedTown.getSingle(KnotMetaComponent.class).orElse(null);
        if (meta == null) return;
        BlockPos townPos = meta.pos.get();
        float townX = (float) townPos.getX();
        float townZ = (float) townPos.getZ();

        float screenW = canvas.layoutBounds().width();
        float screenH = canvas.layoutBounds().height();
        if (screenW <= 10.0f) screenW = 1920.0f;
        if (screenH <= 10.0f) screenH = 1080.0f;

        float targetScreenX = (screenW + 280.0f) * 0.5f;
        float targetScreenY = screenH * 0.5f;

        float curCenterWorldX = canvas.rootToWorldX(targetScreenX);
        float curCenterWorldY = canvas.rootToWorldY(targetScreenY);

        float clampedWorldX = Math.max(townX - TOWN_PAN_LIMIT, Math.min(townX + TOWN_PAN_LIMIT, curCenterWorldX));
        float clampedWorldY = Math.max(townZ - TOWN_PAN_LIMIT, Math.min(townZ + TOWN_PAN_LIMIT, curCenterWorldY));

        if (Math.abs(clampedWorldX - curCenterWorldX) > 0.05f || Math.abs(clampedWorldY - curCenterWorldY) > 0.05f) {
            isClampingTownViewport = true;
            try {
                float curZoom = canvas.viewport().zoom();
                float clampedViewportX = targetScreenX - clampedWorldX * curZoom;
                float clampedViewportY = targetScreenY - clampedWorldY * curZoom;
                canvas.viewport(clampedViewportX, clampedViewportY, curZoom);

                if (panDragField != null && panDragConstructor != null) {
                    try {
                        Object curPan = panDragField.get(canvas);
                        if (curPan != null) {
                            int pid = (int) curPan.getClass().getMethod("pointerId").invoke(curPan);
                            panDragField.set(canvas, panDragConstructor.newInstance(pid, eventRootX, eventRootY, clampedViewportX, clampedViewportY));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } finally {
                isClampingTownViewport = false;
            }
        }
    }

    private static void updateTownLeftPanel(WorldObject town, WorldCanvas canvas, Label status) {
        if (town == null || activeLeftContent == null) return;

        KnotMetaComponent meta = town.getSingle(KnotMetaComponent.class).orElse(null);
        if (meta == null) return;
        BlockPos pos = meta.pos.get();

        activeLeftContent.clearChildren();

        // Заголовок города
        Label titleLabel = new Label("🏰 " + meta.name.get());
        titleLabel.color(new MutableColor(1.0f, 0.85f, 0.25f, 1.0f));
        activeLeftContent.addChild(titleLabel);

        Label typeLabel = new Label("Узел поселения (Town Node)");
        typeLabel.color(new MutableColor(0.40f, 0.75f, 1.0f, 0.90f));
        activeLeftContent.addChild(typeLabel);

        Label posLabel = new Label(String.format(Locale.ROOT, "Позиция: [%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ()));
        posLabel.color(new MutableColor(0.70f, 0.75f, 0.80f, 0.85f));
        activeLeftContent.addChild(posLabel);

        // Секция инвестиции
        Optional<KnotConnectionComponent> connOpt = town.getSingle(KnotConnectionComponent.class);
        boolean isInvested = connOpt.map(c -> c.isInvested.get()).orElse(false);
        int investCost = connOpt.map(c -> c.investmentCost.get()).orElse(1);

        Label investTitle = new Label("─── Инвестиция в узел ───");
        investTitle.color(new MutableColor(0.85f, 0.70f, 0.30f, 0.90f));
        activeLeftContent.addChild(investTitle);

        Label investStatus = new Label(isInvested ? "Статус: Инвестирован (Активен)" : "Статус: Не инвестирован");
        investStatus.color(isInvested ? new MutableColor(0.30f, 0.95f, 0.40f, 1.0f) : new MutableColor(1.0f, 0.55f, 0.30f, 1.0f));
        activeLeftContent.addChild(investStatus);

        Label costLabel = new Label("Стоимость вклада: " + investCost + " ОВ (CP)");
        costLabel.color(new MutableColor(0.80f, 0.85f, 0.90f, 0.85f));
        activeLeftContent.addChild(costLabel);

        Button investBtn = new Button(isInvested ? "Отозвать инвестицию (" + investCost + " CP)" : "Инвестировать очки (" + investCost + " CP)");
        investBtn.layout(s -> s.height(26.0f));
        investBtn.onClick(e -> {
            connOpt.ifPresent(conn -> conn.isInvested.update(v -> !v));
            updateContributionPointsDisplay();
            updateTownLeftPanel(town, canvas, status);
            if (status != null) {
                boolean nowInvested = connOpt.map(c -> c.isInvested.get()).orElse(false);
                status.text("Город " + meta.name.get() + ": инвестиция " + (nowInvested ? "активирована" : "отозвана"));
            }
        });
        activeLeftContent.addChild(investBtn);

        // Секция статистики города
        List<TownHouseData> houses = getOrCreateTownHouses(town);
        int totalStorageSlots = 0;
        int totalLodgingBeds = 0;
        int smelteries = 0;
        int workshops = 0;
        int forges = 0;

        for (TownHouseData h : houses) {
            switch (h.purpose) {
                case STORAGE -> totalStorageSlots += h.level * 6;
                case LODGING -> totalLodgingBeds += h.level;
                case SMELTERY -> smelteries++;
                case WORKSHOP -> workshops++;
                case FORGE -> forges++;
            }
        }

        // Управление городом: склад (BDO Style)
        TownStorageData townStorage = getOrCreateTownStorage(town);
        int curUnlocked = townStorage != null ? townStorage.getUnlockedSlots(totalStorageSlots) : 16;

        Label actionsTitle = new Label("─── Управление поселением ───");
        actionsTitle.color(new MutableColor(0.85f, 0.70f, 0.30f, 0.90f));
        activeLeftContent.addChild(actionsTitle);

        Button storageBtn = new Button("📦 Склад города (" + curUnlocked + "/" + TOTAL_STORAGE_SLOTS + ")");
        storageBtn.layout(s -> s.height(28.0f));
        storageBtn.textColor(new MutableColor(1.0f, 0.88f, 0.35f, 1.0f));
        storageBtn.onClick(e -> {
            openTownStorageWindow(town, canvas, status);
        });
        activeLeftContent.addChild(storageBtn);

        Label statsTitle = new Label("─── Статистика города ───");
        statsTitle.color(new MutableColor(0.85f, 0.70f, 0.30f, 0.90f));
        activeLeftContent.addChild(statsTitle);

        Label storageLabel = new Label("📦 Склад: +" + totalStorageSlots + " слотов (от домов)");
        storageLabel.color(new MutableColor(0.85f, 0.88f, 0.92f, 1.0f));
        activeLeftContent.addChild(storageLabel);

        Label lodgingLabel = new Label("🛏 Жильё: " + totalLodgingBeds + " рабочих");
        lodgingLabel.color(new MutableColor(0.85f, 0.88f, 0.92f, 1.0f));
        activeLeftContent.addChild(lodgingLabel);

        Label craftLabel = new Label(String.format(Locale.ROOT, "⚙ Ремесло: %d плав. | %d маст. | %d кузн.", smelteries, workshops, forges));
        craftLabel.color(new MutableColor(0.85f, 0.88f, 0.92f, 1.0f));
        activeLeftContent.addChild(craftLabel);

        Optional<TownPortComponent> portOpt = town.getSingle(TownPortComponent.class);
        if (portOpt.isPresent()) {
            TownPortComponent port = portOpt.get();
            BlockPos pier = port.pierPos.get();
            Label portLabel = new Label(String.format(Locale.ROOT, "⚓ Порт: [%d, %d] (Судов: %d)",
                    pier.getX(), pier.getZ(), port.shipCapacity.get()));
            portLabel.color(new MutableColor(0.25f, 0.85f, 0.95f, 1.0f));
            activeLeftContent.addChild(portLabel);
        }

        // Список недвижимости города
        Label housesTitle = new Label("─── Дома в городе (" + houses.size() + ") ───");
        housesTitle.color(new MutableColor(0.85f, 0.70f, 0.30f, 0.90f));
        activeLeftContent.addChild(housesTitle);

        for (TownHouseData h : houses) {
            Button hBtn = new Button(h.name + ": " + h.purpose.icon + " " + h.purpose.title + " (ур. " + h.level + ")");
            hBtn.layout(s -> s.height(24.0f));
            hBtn.onClick(e -> {
                openHouseWindow(town, h, canvas, status);
            });
            activeLeftContent.addChild(hBtn);
        }
    }

    private static void openHouseWindow(WorldObject town, TownHouseData house, WorldCanvas canvas, Label status) {
        if (activeHouseWindow == null || house == null) return;

        KnotMetaComponent townMeta = town != null ? town.getSingle(KnotMetaComponent.class).orElse(null) : null;
        String townName = townMeta != null ? townMeta.name.get() : "Город";

        activeHouseWindow.title("Дом: " + house.name + " (" + townName + ")");

        VBox winContent = new VBox();
        winContent.spacing(8.0f);
        winContent.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f).padding(8.0f));

        // 1. Текущий статус здания
        Label headerLabel = new Label(house.purpose.icon + " " + house.purpose.title + " (Уровень " + house.level + " / " + house.purpose.maxLevel + ")");
        headerLabel.color(new MutableColor(1.0f, 0.85f, 0.25f, 1.0f));
        winContent.addChild(headerLabel);

        Label effectLabel = new Label("Эффект: " + house.purpose.getEffectDescription(house.level));
        effectLabel.color(new MutableColor(0.40f, 0.90f, 0.50f, 1.0f));
        winContent.addChild(effectLabel);

        Label descLabel = new Label(house.purpose.desc);
        descLabel.color(new MutableColor(0.70f, 0.75f, 0.80f, 1.0f));
        winContent.addChild(descLabel);

        // 2. Выбор уровня улучшения (1 - 5)
        Label levelTitle = new Label("─── Уровень улучшения (1 – " + house.purpose.maxLevel + ") ───");
        levelTitle.color(new MutableColor(0.85f, 0.70f, 0.30f, 0.90f));
        winContent.addChild(levelTitle);

        HBox levelRow = new HBox();
        levelRow.spacing(6.0f);
        levelRow.layout(s -> s.height(30.0f));

        for (int lvl = 1; lvl <= 5; lvl++) {
            final int targetLevel = lvl;
            boolean isCurrent = (house.level == targetLevel);
            boolean isAllowed = (targetLevel <= house.purpose.maxLevel);

            String btnText = isCurrent ? "★ " + targetLevel : "Ур. " + targetLevel;
            Button lvlBtn = new Button(btnText);
            lvlBtn.enabled(isAllowed);
            lvlBtn.layout(s -> s.flexGrow(1.0f).height(28.0f));
            if (isCurrent) {
                lvlBtn.background().set(0.18f, 0.55f, 0.85f, 0.95f);
            }

            lvlBtn.onClick(e -> {
                if (!isAllowed) return;
                house.level = targetLevel;
                updateContributionPointsDisplay();
                updateHouseTooltip(house);
                updateTownLeftPanel(town, canvas, status);
                openHouseWindow(town, house, canvas, status);
                if (activeStorageWindow != null && activeStorageWindow.opened()) {
                    openTownStorageWindow(town, canvas, status);
                }
                if (status != null) {
                    status.text("Дом " + house.name + ": уровень установлен на " + targetLevel);
                }
            });

            levelRow.addChild(lvlBtn);
        }
        winContent.addChild(levelRow);

        // 3. Выбор назначения здания
        Label purposeTitle = new Label("─── Назначение здания ───");
        purposeTitle.color(new MutableColor(0.85f, 0.70f, 0.30f, 0.90f));
        winContent.addChild(purposeTitle);

        for (HousePurpose purpose : HousePurpose.values()) {
            boolean isCurrent = (house.purpose == purpose);
            String titleText = purpose.icon + " " + purpose.title + (isCurrent ? " (Активно)" : "");
            Button pBtn = new Button(titleText);
            pBtn.layout(s -> s.height(26.0f));
            if (isCurrent) {
                pBtn.background().set(0.20f, 0.40f, 0.25f, 0.95f);
            }
            pBtn.onClick(e -> {
                if (house.purpose != purpose) {
                    house.purpose = purpose;
                    if (house.level > purpose.maxLevel) {
                        house.level = purpose.maxLevel;
                    }
                    updateContributionPointsDisplay();
                    updateHouseTooltip(house);
                    updateTownLeftPanel(town, canvas, status);
                    openHouseWindow(town, house, canvas, status);
                    if (activeStorageWindow != null && activeStorageWindow.opened()) {
                        openTownStorageWindow(town, canvas, status);
                    }
                    if (status != null) {
                        status.text("Дом " + house.name + ": назначение изменено на " + purpose.title);
                    }
                }
            });
            winContent.addChild(pBtn);
        }

        activeHouseWindow.content(winContent);
        activeHouseWindow.open();
    }

    // =========================================================================
    // === BDO CONTRIBUTION POINTS & WORKER SYSTEM HELPER METHODS ===
    // =========================================================================

    private static int calculateTotalUsedCP() {
        int total = 0;
        if (currentKnotsCache != null) {
            for (WorldObject knot : currentKnotsCache) {
                Optional<KnotConnectionComponent> connOpt = knot.getSingle(KnotConnectionComponent.class);
                if (connOpt.isPresent() && connOpt.get().isInvested.get()) {
                    total += connOpt.get().investmentCost.get();
                }
            }
        }
        for (List<TownHouseData> houses : TOWN_HOUSES.values()) {
            for (TownHouseData h : houses) {
                total += h.level;
            }
        }
        for (TownStorageData storage : TOWN_STORAGES.values()) {
            total += (storage.extraPurchasedSlots / 8);
        }
        return total;
    }

    private static RichText buildCpTooltip() {
        int totalUsed = calculateTotalUsedCP();
        int avail = Math.max(0, TOTAL_PLAYER_MAX_CP - totalUsed);

        int nodeUsed = 0;
        if (currentKnotsCache != null) {
            for (WorldObject knot : currentKnotsCache) {
                Optional<KnotConnectionComponent> connOpt = knot.getSingle(KnotConnectionComponent.class);
                if (connOpt.isPresent() && connOpt.get().isInvested.get()) {
                    nodeUsed += connOpt.get().investmentCost.get();
                }
            }
        }

        int houseUsed = 0;
        for (List<TownHouseData> houses : TOWN_HOUSES.values()) {
            for (TownHouseData h : houses) {
                houseUsed += h.level;
            }
        }

        int storageUsed = 0;
        for (TownStorageData storage : TOWN_STORAGES.values()) {
            storageUsed += (storage.extraPurchasedSlots / 8);
        }

        RichText.Builder b = RichText.builder();
        b.color(new MutableColor(1.0f, 0.85f, 0.25f, 1.0f))
         .append("✨ Очки влияния (CP)\n");
        b.color(new MutableColor(0.40f, 0.85f, 1.0f, 1.0f))
         .append("Доступно: " + avail + " / " + TOTAL_PLAYER_MAX_CP + " ОВ\n");
        b.color(new MutableColor(0.85f, 0.88f, 0.92f, 1.0f))
         .append("Использовано: " + totalUsed + " ОВ\n");
        b.color(new MutableColor(0.70f, 0.75f, 0.80f, 1.0f))
         .append("  • В узлы и города: " + nodeUsed + " ОВ\n");
        b.color(new MutableColor(0.70f, 0.75f, 0.80f, 1.0f))
         .append("  • В недвижимость (дома): " + houseUsed + " ОВ\n");
        if (storageUsed > 0) {
            b.color(new MutableColor(0.70f, 0.75f, 0.80f, 1.0f))
             .append("  • В склады поселений: " + storageUsed + " ОВ\n");
        }
        b.color(new MutableColor(0.50f, 0.55f, 0.60f, 1.0f))
         .append("Очки влияния требуются для инвестиций в узлы и улучшения зданий.");
        return b.build();
    }

    private static RichText buildWorkerTooltip() {
        int totalWorkers = WORKERS.size();
        int activeWorkers = 0;
        for (WorkerData w : WORKERS) {
            if (w.isWorking) activeWorkers++;
        }
        int idleWorkers = totalWorkers - activeWorkers;

        int totalBeds = 0;
        for (List<TownHouseData> houses : TOWN_HOUSES.values()) {
            for (TownHouseData h : houses) {
                if (h.purpose == HousePurpose.LODGING) {
                    totalBeds += h.level;
                }
            }
        }

        RichText.Builder b = RichText.builder();
        b.color(new MutableColor(1.0f, 0.85f, 0.25f, 1.0f))
         .append("👷 Наёмные рабочие\n");
        b.color(new MutableColor(0.85f, 0.88f, 0.92f, 1.0f))
         .append("Всего рабочих: " + totalWorkers + "\n");
        b.color(new MutableColor(0.40f, 0.90f, 0.50f, 1.0f))
         .append("Занято работой: " + activeWorkers + "\n");
        b.color(new MutableColor(0.40f, 0.75f, 1.0f, 1.0f))
         .append("Свободно: " + idleWorkers + "\n");
        b.color(new MutableColor(0.85f, 0.70f, 0.30f, 1.0f))
         .append("Вместимость бараков: " + totalBeds + " мест\n");
        b.color(new MutableColor(0.50f, 0.55f, 0.60f, 1.0f))
         .append("Клик: Открыть меню управления");
        return b.build();
    }

    private static void updateContributionPointsDisplay() {
        if (activeCpBar == null || activeCpLabel == null) return;
        int usedCP = calculateTotalUsedCP();
        int availCP = Math.max(0, TOTAL_PLAYER_MAX_CP - usedCP);
        activeCpLabel.text(availCP + " / " + TOTAL_PLAYER_MAX_CP);
        activeCpBar.invalidate(InvalidationFlags.VISUAL);
        if (activeCpTooltip != null) {
            activeCpTooltip.richText(buildCpTooltip());
            activeCpTooltip.invalidate(InvalidationFlags.LAYOUT | InvalidationFlags.VISUAL);
        }
    }

    private static Box createTopBarDivider() {
        Box divider = new Box();
        divider.backgroundVisible(true);
        divider.background().set(1.0f, 1.0f, 1.0f, 0.15f);
        divider.layout(s -> s.width(1.0f).height(16.0f).margin(0.0f, 3.0f, 0.0f, 3.0f));
        return divider;
    }

    private static void populateSearchResults(String query, VBox resultsBox, WorldCanvas canvas) {
        resultsBox.clearChildren();
        String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";

        List<WorldObject> matched = new ArrayList<>();
        if (currentKnotsCache != null) {
            for (WorldObject knot : currentKnotsCache) {
                KnotMetaComponent meta = knot.getSingle(KnotMetaComponent.class).orElse(null);
                if (meta == null) continue;
                String name = meta.name.get();
                if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) {
                    matched.add(knot);
                }
            }
        }

        if (matched.isEmpty()) {
            Label emptyLabel = new Label("Ничего не найдено");
            emptyLabel.color(new MutableColor(0.60f, 0.65f, 0.70f, 0.90f));
            resultsBox.addChild(emptyLabel);
            return;
        }

        for (WorldObject knot : matched) {
            KnotMetaComponent meta = knot.getSingle(KnotMetaComponent.class).get();
            BlockPos pos = meta.pos.get();
            boolean isTown = (meta.type.get() == KnotType.TOWN);
            String prefix = isTown ? "🏰 " : "📍 ";
            String labelText = prefix + meta.name.get() + " [" + pos.getX() + ", " + pos.getZ() + "]";

            Button itemBtn = new Button(labelText);
            itemBtn.layout(s -> s.height(24.0f));
            if (isTown) {
                itemBtn.textColor(new MutableColor(1.0f, 0.85f, 0.30f, 1.0f));
            }

            itemBtn.onClick(e -> {
                if (activeSearchPanel != null) {
                    activeSearchPanel.visible(false);
                }
                if (canvas != null) {
                    float screenW = canvas.layoutBounds().width();
                    float screenH = canvas.layoutBounds().height();
                    if (screenW <= 10.0f) screenW = 1920.0f;
                    if (screenH <= 10.0f) screenH = 1080.0f;

                    if (isTown) {
                        enterTownView(knot, canvas, null);
                    } else {
                        if (currentSelectedTown != null) {
                            exitTownMode(canvas, null);
                        }
                        float targetZoom = 0.85f;
                        float newVx = screenW * 0.5f - (float) pos.getX() * targetZoom;
                        float newVy = screenH * 0.5f - (float) pos.getZ() * targetZoom;
                        canvas.viewport(newVx, newVy, targetZoom);
                    }
                }
            });
            resultsBox.addChild(itemBtn);
        }
    }

    private static void openWorkerWindow() {
        if (activeWorkerWindow == null) return;

        VBox winContent = new VBox();
        winContent.spacing(8.0f);
        winContent.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f).padding(8.0f));

        // 1. Верхняя панель действий для всех рабочих
        HBox topActions = new HBox();
        topActions.spacing(6.0f);
        topActions.layout(s -> s.height(28.0f));

        Button beerAllBtn = new Button("Восстановить всех 🍺");
        beerAllBtn.layout(s -> s.flexGrow(1.0f).height(28.0f));
        beerAllBtn.onClick(e -> {
            for (WorkerData w : WORKERS) {
                w.energy = w.maxEnergy;
            }
            if (activeStatus != null) {
                activeStatus.text("Все рабочие восстановили бодрость пивом 🍺!");
            }
            openWorkerWindow();
        });
        topActions.addChild(beerAllBtn);

        Button repeatAllBtn = new Button("Повторить всем 🔁");
        repeatAllBtn.layout(s -> s.flexGrow(1.0f).height(28.0f));
        repeatAllBtn.onClick(e -> {
            for (WorkerData w : WORKERS) {
                if (w.energy > 0) {
                    w.isWorking = true;
                    if (w.currentTask.equals("Свободен")) {
                        w.currentTask = "Сбор ресурсов";
                    }
                }
            }
            if (activeStatus != null) {
                activeStatus.text("Повторение задач для всех рабочих с бодростью 🔁");
            }
            openWorkerWindow();
        });
        topActions.addChild(repeatAllBtn);
        winContent.addChild(topActions);

        // 2. Список карточек рабочих в ScrollView
        VBox workerList = new VBox();
        workerList.spacing(8.0f);
        workerList.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f).flexShrink(1.0f));

        for (WorkerData worker : WORKERS) {
            Box card = new Box();
            card.backgroundVisible(true);
            card.borderVisible(true);
            card.background().set(0.08f, 0.10f, 0.14f, 0.95f);
            card.borderColor().set(0.25f, 0.35f, 0.50f, 0.80f);
            card.radius(5.0f);
            card.borderWidth(1.2f);
            card.layout(s -> s.padding(6.0f));

            VBox cardContent = new VBox();
            cardContent.spacing(4.0f);
            cardContent.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).flexGrow(1.0f));

            // Заголовок рабочего
            HBox header = new HBox();
            header.spacing(6.0f);
            header.layout(s -> s.height(18.0f).alignItems(Align.CENTER));

            Label nameLabel = new Label(worker.raceIcon + " " + worker.name + " (" + worker.level + " ур.)");
            nameLabel.color(new MutableColor(1.0f, 0.85f, 0.30f, 1.0f));
            nameLabel.layout(s -> s.flexGrow(1.0f));
            header.addChild(nameLabel);

            Label townLabel = new Label("📍 " + worker.townName);
            townLabel.color(new MutableColor(0.40f, 0.75f, 1.0f, 0.90f));
            header.addChild(townLabel);
            cardContent.addChild(header);

            // Текущее задание
            Label taskLabel = new Label(worker.isWorking ? "⚙ Задача: " + worker.currentTask : "💤 Статус: Свободен");
            taskLabel.color(worker.isWorking ? new MutableColor(0.85f, 0.90f, 0.95f, 0.95f) : new MutableColor(0.60f, 0.65f, 0.70f, 0.85f));
            cardContent.addChild(taskLabel);

            // Шкала бодрости (Energy)
            HBox energyRow = new HBox();
            energyRow.spacing(6.0f);
            energyRow.layout(s -> s.height(18.0f).alignItems(Align.CENTER));

            Label energyLabel = new Label("Бодрость: " + worker.energy + " / " + worker.maxEnergy);
            energyLabel.color(new MutableColor(0.75f, 0.80f, 0.85f, 0.90f));
            energyRow.addChild(energyLabel);

            Box energyBar = new Box();
            energyBar.backgroundVisible(false);
            energyBar.layout(s -> s.flexGrow(1.0f).height(12.0f));
            final WorkerData currentW = worker;
            energyBar.renderer((draw, w) -> {
                RectView b = w.layoutBounds();
                float bx = b.x();
                float by = b.y();
                float bw = b.width();
                float bh = b.height();

                draw.addRectFilled(bx, by, bw, bh, 3.0f, new MutableColor(0.04f, 0.05f, 0.08f, 0.95f));
                float eRatio = Math.clamp((float) currentW.energy / (float) currentW.maxEnergy, 0.0f, 1.0f);
                if (eRatio > 0.0f) {
                    MutableColor barCol = eRatio > 0.5f ? new MutableColor(0.20f, 0.75f, 0.35f, 0.90f)
                            : (eRatio > 0.2f ? new MutableColor(0.90f, 0.65f, 0.20f, 0.90f)
                            : new MutableColor(0.85f, 0.25f, 0.25f, 0.90f));
                    draw.addRectFilled(bx + 1.0f, by + 1.0f, (bw - 2.0f) * eRatio, bh - 2.0f, 2.0f, barCol);
                }
                draw.addRect(bx, by, bw, bh, 3.0f, new MutableColor(0.30f, 0.40f, 0.55f, 0.60f), 1.0f);
            });
            energyRow.addChild(energyBar);
            cardContent.addChild(energyRow);

            // Кнопки управления карточки
            HBox btnRow = new HBox();
            btnRow.spacing(6.0f);
            btnRow.layout(s -> s.height(24.0f).margin(2.0f, 0.0f, 0.0f, 0.0f));

            Button feedBtn = new Button("Пиво 🍺");
            feedBtn.layout(s -> s.flexGrow(1.0f).height(24.0f));
            feedBtn.enabled(worker.energy < worker.maxEnergy);
            feedBtn.onClick(e -> {
                worker.energy = Math.min(worker.maxEnergy, worker.energy + 5);
                if (activeStatus != null) {
                    activeStatus.text(worker.name + " выпил кружку пива (+5 к бодрости) 🍺");
                }
                openWorkerWindow();
            });
            btnRow.addChild(feedBtn);

            if (worker.isWorking) {
                Button stopBtn = new Button("Остановить ⏹");
                stopBtn.layout(s -> s.flexGrow(1.0f).height(24.0f));
                stopBtn.onClick(e -> {
                    worker.isWorking = false;
                    worker.currentTask = "Свободен";
                    if (activeStatus != null) {
                        activeStatus.text(worker.name + " остановил работу");
                    }
                    openWorkerWindow();
                });
                btnRow.addChild(stopBtn);
            } else {
                Button startBtn = new Button("Назначить ⛏");
                startBtn.layout(s -> s.flexGrow(1.0f).height(24.0f));
                startBtn.enabled(worker.energy > 0);
                startBtn.onClick(e -> {
                    worker.isWorking = true;
                    worker.currentTask = "Сбор ресурсов";
                    if (activeStatus != null) {
                        activeStatus.text(worker.name + " отправлен на сбор ресурсов ⛏");
                    }
                    openWorkerWindow();
                });
                btnRow.addChild(startBtn);
            }

            cardContent.addChild(btnRow);
            card.addChild(cardContent);
            workerList.addChild(card);
        }

        ScrollView scroll = new ScrollView(workerList);
        scroll.scrollStep(18.0f);
        scroll.scrollbarGap(2.0f);
        scroll.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO)
                .flexGrow(1.0f)
                .flexShrink(1.0f)
                .overflowX(Overflow.HIDDEN)
                .overflowY(Overflow.AUTO));
        winContent.addChild(scroll);

        activeWorkerWindow.content(winContent);
        activeWorkerWindow.open();
    }

    // =========================================================================
    // === BDO TOWN STORAGE SYSTEM METHODS ===
    // =========================================================================

    public static final class TownStorageData {
        public final UUID townId;
        public final List<ItemStack> items = new ArrayList<>();
        public int extraPurchasedSlots = 0;
        public int silver = 25480;

        public TownStorageData(UUID townId) {
            this.townId = townId;
            initDefaultItems();
        }

        private void initDefaultItems() {
            items.add(new ItemStack(Items.IRON_INGOT, 48));
            items.add(new ItemStack(Items.COPPER_INGOT, 64));
            items.add(new ItemStack(Items.GOLD_INGOT, 18));
            items.add(new ItemStack(Items.COAL, 64));
            items.add(new ItemStack(Items.RAW_IRON, 32));
            items.add(new ItemStack(Items.OAK_LOG, 64));
            items.add(new ItemStack(Items.WHEAT, 54));
            items.add(new ItemStack(Items.BREAD, 24));
            items.add(new ItemStack(Items.DIAMOND, 4));
            items.add(new ItemStack(Items.LEATHER, 20));
            items.add(new ItemStack(Items.REDSTONE, 45));
            items.add(new ItemStack(Items.EMERALD, 12));
            items.add(new ItemStack(Items.IRON_PICKAXE, 1));
            items.add(new ItemStack(Items.COOKED_BEEF, 16));
        }

        public int getUnlockedSlots(int houseBonus) {
            int base = 16;
            return Math.min(TOTAL_STORAGE_SLOTS, base + houseBonus + extraPurchasedSlots);
        }

        public int getFilledItemCount() {
            int count = 0;
            for (ItemStack s : items) {
                if (s != null && !s.isEmpty()) count++;
            }
            return count;
        }

        public void sortItems() {
            items.sort((a, b) -> {
                boolean aEmpty = (a == null || a.isEmpty());
                boolean bEmpty = (b == null || b.isEmpty());
                if (aEmpty && bEmpty) return 0;
                if (aEmpty) return 1;
                if (bEmpty) return -1;
                int nameCmp = a.getHoverName().getString().compareToIgnoreCase(b.getHoverName().getString());
                if (nameCmp != 0) return nameCmp;
                return Integer.compare(b.getCount(), a.getCount());
            });
        }
    }

    private static TownStorageData getOrCreateTownStorage(WorldObject town) {
        if (town == null) return null;
        return TOWN_STORAGES.computeIfAbsent(town.id(), TownStorageData::new);
    }

    private static void clearStorageTooltips() {
        for (Tooltip t : STORAGE_TOOLTIPS) {
            if (activeRootOverlay != null) {
                activeRootOverlay.removeOverlay(t);
            }
        }
        STORAGE_TOOLTIPS.clear();
    }

    private static void openTownStorageWindow(WorldObject town, WorldCanvas canvas, Label status) {
        if (activeStorageWindow == null || town == null) return;

        KnotMetaComponent townMeta = town.getSingle(KnotMetaComponent.class).orElse(null);
        String townName = townMeta != null ? townMeta.name.get() : "Город";

        activeStorageWindow.title("Склад: " + townName);

        clearStorageTooltips();

        TownStorageData storage = getOrCreateTownStorage(town);

        List<TownHouseData> houses = getOrCreateTownHouses(town);
        int houseStorageBonus = 0;
        for (TownHouseData h : houses) {
            if (h.purpose == HousePurpose.STORAGE) {
                houseStorageBonus += h.level * 6;
            }
        }
        int unlockedSlots = storage.getUnlockedSlots(houseStorageBonus);

        VBox winContent = new VBox();
        winContent.spacing(8.0f);
        winContent.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO)
                .flexGrow(1.0f).flexShrink(1.0f).padding(8.0f));

        // 1. Верхняя информационная панель (статус хранилища, серебро, сортировка)
        HBox topBar = new HBox();
        topBar.spacing(6.0f);
        topBar.layout(s -> s.height(26.0f).alignItems(Align.CENTER));

        Label slotsInfo = new Label("Слоты: " + storage.getFilledItemCount() + " / " + unlockedSlots + " (Всего: " + TOTAL_STORAGE_SLOTS + ")");
        slotsInfo.color(new MutableColor(0.85f, 0.88f, 0.92f, 1.0f));
        slotsInfo.layout(s -> s.flexGrow(1.0f));
        topBar.addChild(slotsInfo);

        Label moneyLabel = new Label("🪙 " + String.format(Locale.ROOT, "%,d", storage.silver));
        moneyLabel.color(new MutableColor(1.0f, 0.85f, 0.30f, 1.0f));
        topBar.addChild(moneyLabel);

        Button sortBtn = new Button("🔀 Сортировка");
        sortBtn.layout(s -> s.height(24.0f).padding(1.0f, 6.0f, 1.0f, 6.0f));
        sortBtn.onClick(e -> {
            storage.sortItems();
            openTownStorageWindow(town, canvas, status);
            if (status != null) {
                status.text("Склад города " + townName + " отсортирован 🔀");
            }
        });
        topBar.addChild(sortBtn);

        winContent.addChild(topBar);

        // Полоса заполненности склада
        Box progressBar = new Box();
        progressBar.backgroundVisible(false);
        progressBar.layout(s -> s.height(6.0f));
        int filledCount = storage.getFilledItemCount();
        progressBar.renderer((draw, w) -> {
            RectView b = w.layoutBounds();
            draw.addRectFilled(b.x(), b.y(), b.width(), b.height(), 2.0f, new MutableColor(0.04f, 0.05f, 0.08f, 0.95f));
            float ratio = Math.clamp((float) filledCount / (float) Math.max(1, unlockedSlots), 0.0f, 1.0f);
            if (ratio > 0.0f) {
                MutableColor col = ratio > 0.85f ? new MutableColor(0.95f, 0.35f, 0.25f, 0.9f)
                        : (ratio > 0.6f ? new MutableColor(0.95f, 0.75f, 0.20f, 0.9f)
                        : new MutableColor(0.25f, 0.75f, 0.95f, 0.9f));
                draw.addRectFilled(b.x(), b.y(), b.width() * ratio, b.height(), 2.0f, col);
            }
            draw.addRect(b.x(), b.y(), b.width(), b.height(), 2.0f, new MutableColor(0.25f, 0.35f, 0.45f, 0.5f), 1.0f);
        });
        winContent.addChild(progressBar);

        // 2. Сетка слотов (8 колонок, 7 рядов)
        VBox grid = new VBox();
        grid.spacing(4.0f);
        grid.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO).alignItems(Align.CENTER));

        int cols = 8;
        int rows = 7;
        for (int r = 0; r < rows; r++) {
            HBox rowBox = new HBox();
            rowBox.spacing(4.0f);
            rowBox.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO));

            for (int c = 0; c < cols; c++) {
                int slotIndex = r * cols + c;
                Widget slotWidget = createStorageSlotWidget(town, storage, slotIndex, unlockedSlots, townName, canvas, status);
                rowBox.addChild(slotWidget);
            }
            grid.addChild(rowBox);
        }

        ScrollView scroll = new ScrollView(grid);
        scroll.scrollStep(20.0f);
        scroll.scrollbarGap(2.0f);
        scroll.layout(s -> s.size(LayoutConstraints.AUTO, LayoutConstraints.AUTO)
                .flexGrow(1.0f).flexShrink(1.0f)
                .overflowX(Overflow.HIDDEN)
                .overflowY(Overflow.AUTO));
        winContent.addChild(scroll);

        // 3. Нижняя панель действий (Расширение склада)
        HBox bottomBar = new HBox();
        bottomBar.spacing(6.0f);
        bottomBar.layout(s -> s.height(26.0f).alignItems(Align.CENTER));

        Label hintLabel = new Label("💡 Открыто " + unlockedSlots + " из " + TOTAL_STORAGE_SLOTS + " слотов");
        hintLabel.color(new MutableColor(0.70f, 0.75f, 0.80f, 0.90f));
        hintLabel.layout(s -> s.flexGrow(1.0f));
        bottomBar.addChild(hintLabel);

        if (unlockedSlots < TOTAL_STORAGE_SLOTS) {
            Button expandBtn = new Button("➕ +8 слотов (1 CP)");
            expandBtn.layout(s -> s.height(24.0f));
            expandBtn.enabled(calculateTotalUsedCP() + 1 <= TOTAL_PLAYER_MAX_CP);
            expandBtn.onClick(e -> {
                if (calculateTotalUsedCP() + 1 <= TOTAL_PLAYER_MAX_CP) {
                    storage.extraPurchasedSlots += 8;
                    updateContributionPointsDisplay();
                    updateTownLeftPanel(town, canvas, status);
                    openTownStorageWindow(town, canvas, status);
                    if (status != null) {
                        status.text("Склад города " + townName + " расширен на +8 слотов (1 ОВ) 📦");
                    }
                }
            });
            bottomBar.addChild(expandBtn);
        }

        winContent.addChild(bottomBar);

        activeStorageWindow.content(winContent);
        activeStorageWindow.open();
    }

    private static Widget createStorageSlotWidget(
            WorldObject town,
            TownStorageData storage,
            int slotIndex,
            int unlockedSlots,
            String townName,
            WorldCanvas canvas,
            Label status) {

        boolean isUnlocked = (slotIndex < unlockedSlots);
        boolean hasItem = isUnlocked && (slotIndex < storage.items.size()) && storage.items.get(slotIndex) != null && !storage.items.get(slotIndex).isEmpty();

        Box slotBox = new Box();
        slotBox.layout(s -> s.size(38.0f, 38.0f).alignItems(Align.CENTER).justifyContent(Justify.CENTER));
        slotBox.backgroundVisible(true);
        slotBox.borderVisible(true);
        slotBox.radius(4.0f);
        slotBox.borderWidth(1.2f);

        if (!isUnlocked) {
            // === ЗАКРЫТЫЙ СЛОТ ===
            slotBox.background().set(0.02f, 0.025f, 0.035f, 0.90f);
            slotBox.borderColor().set(0.18f, 0.20f, 0.25f, 0.50f);

            Label lockIcon = new Label("🔒");
            lockIcon.color(new MutableColor(0.70f, 0.35f, 0.35f, 0.65f));
            slotBox.addChild(lockIcon);

            Tooltip lockTooltip = new Tooltip(slotBox, "🔒 Закрытый слот #" + (slotIndex + 1)
                    + "\nУлучшите дома города («Склад» ур. 1–5)\nили вложите ОВ для расширения.");
            lockTooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
            lockTooltip.borderColor().set(0.85f, 0.35f, 0.25f, 0.90f);
            lockTooltip.radius(4.0f);
            lockTooltip.offset(4.0f, 6.0f);
            lockTooltip.maxWidth(240.0f);

            STORAGE_TOOLTIPS.add(lockTooltip);
            if (activeRootOverlay != null) {
                activeRootOverlay.addOverlay(lockTooltip);
            }

            slotBox.renderer((draw, w) -> {
                RectView b = w.layoutBounds();
                if (slotBox.hovered()) {
                    draw.addRectFilled(b.x(), b.y(), b.width(), b.height(), 4.0f,
                            new MutableColor(0.80f, 0.25f, 0.20f, 0.15f));
                    draw.addRect(b.x(), b.y(), b.width(), b.height(), 4.0f,
                            new MutableColor(0.85f, 0.35f, 0.30f, 0.85f), 1.4f);
                }
            });

            slotBox.on(PointerPressedEvent.TYPE, e -> {
                if (status != null) {
                    status.text("Слот #" + (slotIndex + 1) + " закрыт. Улучшите жилые дома в режим «Склад».");
                }
            });
        } else if (hasItem) {
            // === ОТКРЫТЫЙ СЛОТ С ПРЕДМЕТОМ ===
            ItemStack stack = storage.items.get(slotIndex);

            slotBox.background().set(0.04f, 0.05f, 0.08f, 0.92f);
            slotBox.borderColor().set(0.24f, 0.32f, 0.44f, 0.85f);

            MinecraftItemPreviewWidget preview = new MinecraftItemPreviewWidget("", stack);
            preview.labelVisible(false);
            preview.previewSize(20.0f);
            preview.backgroundVisible(false);
            preview.borderVisible(false);
            preview.layout(s -> s.size(36.0f, 36.0f));

            MinecraftItemTooltip itemTooltip = preview.vanillaTooltip();
            STORAGE_TOOLTIPS.add(itemTooltip);
            if (activeRootOverlay != null) {
                activeRootOverlay.addOverlay(itemTooltip);
            }

            slotBox.addChild(preview);

            slotBox.renderer((draw, w) -> {
                RectView b = w.layoutBounds();
                if (slotBox.hovered()) {
                    draw.addRectFilled(b.x(), b.y(), b.width(), b.height(), 4.0f,
                            new MutableColor(1.0f, 0.85f, 0.30f, 0.15f));
                    draw.addRect(b.x(), b.y(), b.width(), b.height(), 4.0f,
                            new MutableColor(1.0f, 0.85f, 0.30f, 0.95f), 1.5f);
                }
            });

            slotBox.on(PointerPressedEvent.TYPE, e -> {
                if (status != null) {
                    status.text("Склад " + townName + ": [" + stack.getHoverName().getString()
                            + " x" + stack.getCount() + "] в слоте #" + (slotIndex + 1));
                }
            });
        } else {
            // === ОТКРЫТЫЙ ПУСТОЙ СЛОТ ===
            slotBox.background().set(0.035f, 0.045f, 0.065f, 0.85f);
            slotBox.borderColor().set(0.18f, 0.22f, 0.28f, 0.70f);

            Tooltip emptyTooltip = new Tooltip(slotBox, "Пустой слот #" + (slotIndex + 1));
            emptyTooltip.background().set(0.08f, 0.09f, 0.12f, 0.95f);
            emptyTooltip.borderColor().set(0.40f, 0.50f, 0.65f, 0.85f);
            emptyTooltip.radius(4.0f);
            emptyTooltip.offset(4.0f, 6.0f);

            STORAGE_TOOLTIPS.add(emptyTooltip);
            if (activeRootOverlay != null) {
                activeRootOverlay.addOverlay(emptyTooltip);
            }

            slotBox.renderer((draw, w) -> {
                RectView b = w.layoutBounds();
                if (slotBox.hovered()) {
                    draw.addRectFilled(b.x(), b.y(), b.width(), b.height(), 4.0f,
                            new MutableColor(1.0f, 1.0f, 1.0f, 0.08f));
                    draw.addRect(b.x(), b.y(), b.width(), b.height(), 4.0f,
                            new MutableColor(0.45f, 0.60f, 0.80f, 0.80f), 1.2f);
                }
            });

            slotBox.on(PointerPressedEvent.TYPE, e -> {
                if (status != null) {
                    status.text("Слот #" + (slotIndex + 1) + " пуст.");
                }
            });
        }

        return slotBox;
    }
}
