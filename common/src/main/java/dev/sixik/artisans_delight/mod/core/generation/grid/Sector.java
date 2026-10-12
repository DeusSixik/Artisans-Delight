package dev.sixik.artisans_delight.mod.core.generation.grid;

import net.minecraft.core.BlockPos;

/**
 * Сектор мира — базовая ячейка грубой сетки для детерминированного размещения узлов.
 * 
 * <p>Мир делится на равные квадратные секции размером {@code SECTOR_SIZE_CHUNKS × SECTOR_SIZE_CHUNKS} чанков.
 * Каждая секция содержит один детерминированный вейпоинт, который служит якорем для привязки узлов.
 * 
 * <p><b>Детерминированность:</b> Координаты сектора вычисляются исключительно по мировым координатам
 * без привязки к состоянию чанков. Два вызова для одной точки мира всегда вернут одинаковый сектор.
 * 
 * <p><b>Пример использования:</b>
 * <pre>{@code
 * BlockPos townPos = new BlockPos(1200, 64, -800);
 * Sector sector = Sector.fromWorldPos(townPos);
 * BlockPos waypointCandidate = sector.centerBlock();
 * }</pre>
 * 
 * @param sectorX X-координата сектора в сетке (не блоки, а номер сектора)
 * @param sectorZ Z-координата сектора в сетке (не блоки, а номер сектора)
 */
public record Sector(int sectorX, int sectorZ) {
    
    /**
     * Размер сектора в чанках (по умолчанию 6×6 чанков = 96×96 блоков).
     * 
     * <p>Этот размер подобран так, чтобы:
     * <ul>
     *   <li>Сектор был достаточно большим для поиска оптимального вейпоинта (проходимая местность)
     *   <li>Сектор был достаточно малым, чтобы узлы не располагались слишком далеко друг от друга
     *   <li>Размер кратен RegionFile (32×32 чанка), что упрощает кэширование
     * </ul>
     * 
     * <p><b>Настройка:</b> Можно сделать конфигурируемым параметром для модпаков.
     */
    public static final int SECTOR_SIZE_CHUNKS = 6;
    
    /**
     * Размер сектора в блоках (96 блоков по умолчанию).
     */
    public static final int SECTOR_SIZE_BLOCKS = SECTOR_SIZE_CHUNKS * 16;
    
    /**
     * Создаёт сектор из мировых координат блока.
     * 
     * <p>Используется {@link Math#floorDiv} для корректной работы с отрицательными координатами:
     * <ul>
     *   <li>Блок (0, 0) → Сектор (0, 0)
     *   <li>Блок (95, 95) → Сектор (0, 0)
     *   <li>Блок (96, 96) → Сектор (1, 1)
     *   <li>Блок (-1, -1) → Сектор (-1, -1)
     * </ul>
     * 
     * @param worldX X-координата блока в мире
     * @param worldZ Z-координата блока в мире
     * @return Сектор, содержащий данный блок
     */
    public static Sector fromWorldPos(int worldX, int worldZ) {
        return new Sector(
            Math.floorDiv(worldX, SECTOR_SIZE_BLOCKS),
            Math.floorDiv(worldZ, SECTOR_SIZE_BLOCKS)
        );
    }
    
    /**
     * Создаёт сектор из мировых координат блока.
     * 
     * @param pos Позиция блока в мире
     * @return Сектор, содержащий данный блок
     */
    public static Sector fromWorldPos(BlockPos pos) {
        return fromWorldPos(pos.getX(), pos.getZ());
    }
    
    /**
     * Возвращает мировые координаты центра сектора.
     * 
     * <p>Центр сектора — это кандидат по умолчанию для размещения вейпоинта.
     * Координата Y всегда равна 64 (уровень моря) и должна быть скорректирована генератором.
     * 
     * @return Координаты центра сектора (Y=64)
     */
    public BlockPos centerBlock() {
        return new BlockPos(
            sectorX * SECTOR_SIZE_BLOCKS + SECTOR_SIZE_BLOCKS / 2,
            64,
            sectorZ * SECTOR_SIZE_BLOCKS + SECTOR_SIZE_BLOCKS / 2
        );
    }
    
    /**
     * Возвращает мировые координаты юго-западного угла сектора (минимальные X, Z).
     * 
     * @return Координаты угла сектора (Y=64)
     */
    public BlockPos minCorner() {
        return new BlockPos(
            sectorX * SECTOR_SIZE_BLOCKS,
            64,
            sectorZ * SECTOR_SIZE_BLOCKS
        );
    }
    
    /**
     * Возвращает мировые координаты северо-восточного угла сектора (максимальные X, Z).
     * 
     * @return Координаты угла сектора (Y=64)
     */
    public BlockPos maxCorner() {
        return new BlockPos(
            (sectorX + 1) * SECTOR_SIZE_BLOCKS - 1,
            64,
            (sectorZ + 1) * SECTOR_SIZE_BLOCKS - 1
        );
    }
    
    /**
     * Проверяет, содержится ли данная мировая позиция внутри этого сектора.
     * 
     * @param worldX X-координата блока в мире
     * @param worldZ Z-координата блока в мире
     * @return {@code true}, если блок находится внутри сектора
     */
    public boolean contains(int worldX, int worldZ) {
        int minX = sectorX * SECTOR_SIZE_BLOCKS;
        int minZ = sectorZ * SECTOR_SIZE_BLOCKS;
        int maxX = minX + SECTOR_SIZE_BLOCKS;
        int maxZ = minZ + SECTOR_SIZE_BLOCKS;
        
        return worldX >= minX && worldX < maxX && worldZ >= minZ && worldZ < maxZ;
    }
    
    /**
     * Проверяет, содержится ли данная мировая позиция внутри этого сектора.
     * 
     * @param pos Позиция блока в мире
     * @return {@code true}, если блок находится внутри сектора
     */
    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getZ());
    }
    
    /**
     * Вычисляет манхэттенское расстояние до другого сектора (в секторах, не блоках).
     * 
     * @param other Другой сектор
     * @return Расстояние в секторах
     */
    public int distanceTo(Sector other) {
        return Math.abs(this.sectorX - other.sectorX) + Math.abs(this.sectorZ - other.sectorZ);
    }
    
    /**
     * Возвращает детерминированный хеш для данного сектора и сида мира.
     * 
     * <p>Используется для генерации детерминированных случайных чисел в рамках сектора.
     * 
     * @param worldSeed Сид мира
     * @return Детерминированный хеш
     */
    public long hash(long worldSeed) {
        long hash = worldSeed;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        hash += sectorX;
        hash = hash * 6364136223846793005L + 1442695040888963407L;
        hash += sectorZ;
        return hash;
    }
    
    @Override
    public String toString() {
        return "Sector[" + sectorX + ", " + sectorZ + "]";
    }
}
