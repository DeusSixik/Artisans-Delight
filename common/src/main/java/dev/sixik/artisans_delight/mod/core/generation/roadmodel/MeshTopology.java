package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Локальная детерминированная топология дорожного меша.
 *
 * <p>Чистая геометрия на плоскости XZ, без Minecraft. Каждое решение здесь
 * зависит только от точек в ограниченной окрестности и <b>не зависит от
 * порядка расчёта</b>. Это нужно для потоковой генерации по регионам: сеть,
 * посчитанная по кусочкам в любом порядке, совпадает с сетью, посчитанной
 * целиком.
 *
 * <p><b>Граф относительного соседства (RNG).</b> Ребро {@code p–q} существует
 * тогда и только тогда, когда внутри «линзы» нет третьей точки {@code r}:
 * <pre>
 *   ∄ r ≠ p, q :  max(|pr|, |qr|) &lt; |pq|
 * </pre>
 * где {@code |ab| = √((ax−bx)² + (az−bz)²)} — евклидово расстояние.
 * Линза — пересечение кругов радиуса {@code |pq|} с центрами в {@code p}
 * и {@code q}.
 *
 * <p>Свойства, ради которых выбран RNG:
 * <ul>
 *   <li>планарность: рёбра RNG не пересекаются (RNG ⊆ Delaunay);</li>
 *   <li>связность: RNG ⊇ EMST (евклидово минимальное остовное дерево), поэтому
 *       без ограничения длины граф связен;</li>
 *   <li>разреженность: средняя степень ≈ 2.5 — редкий меш в духе BDO;</li>
 *   <li>локальность: проверка ребра {@code p–q} смотрит только точки
 *       в радиусе {@code |pq|} от {@code p}.</li>
 * </ul>
 *
 * <p>Ограничение длины {@code maxLen} сохраняет детерминизм и локальность,
 * но может разорвать связность в разреженных местах (через воду и т.п.).
 */
public final class MeshTopology {

    private MeshTopology() {
    }

    /**
     * Строит рёбра графа относительного соседства с ограничением длины.
     *
     * <p>Сложность: {@code O(N · k²)}, где {@code k} — среднее число точек
     * в радиусе {@code maxLen}. Поиск соседей через равномерную сетку
     * с ячейкой {@code maxLen}.
     *
     * <p>Сравнение строгое ({@code <}): при точном равенстве расстояний
     * (вырожденная решётка) ребро сохраняется. При джиттере узлов это
     * практически не встречается.
     *
     * @param xs     координаты X точек
     * @param zs     координаты Z точек (длина равна {@code xs})
     * @param minLen минимальная длина ребра (короче — ребро отбрасывается)
     * @param maxLen максимальная длина ребра (длиннее — отбрасывается); &gt; 0
     * @return пары индексов {@code {i, j}} с {@code i < j}, в порядке
     *         возрастания {@code i}, затем {@code j} (детерминированно)
     * @throws IllegalArgumentException если длины массивов различаются или
     *                                  {@code maxLen <= 0}
     */
    public static List<int[]> relativeNeighborhoodEdges(double[] xs, double[] zs,
                                                        double minLen, double maxLen) {
        if (xs.length != zs.length) {
            throw new IllegalArgumentException("xs/zs length mismatch: " + xs.length + " vs " + zs.length);
        }
        if (!(maxLen > 0.0)) {
            throw new IllegalArgumentException("maxLen must be > 0: " + maxLen);
        }
        int n = xs.length;
        List<int[]> out = new ArrayList<>();
        if (n < 2) {
            return out;
        }

        // Равномерная сетка: ячейка = maxLen, соседи ребра лежат в 3×3 ячейках.
        Map<Long, List<Integer>> grid = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            grid.computeIfAbsent(cellKey(cell(xs[i], maxLen), cell(zs[i], maxLen)),
                k -> new ArrayList<>()).add(i);
        }

        double maxLen2 = maxLen * maxLen;
        double minLen2 = minLen * minLen;
        List<Integer> near = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            collectNear(grid, xs, zs, i, maxLen, maxLen2, near);
            for (int j : near) {
                if (j <= i) {
                    continue; // каждая пара один раз, i < j
                }
                double d2 = dist2(xs, zs, i, j);
                if (d2 < minLen2 || d2 > maxLen2) {
                    continue;
                }
                if (!luneEmpty(xs, zs, i, j, d2, near)) {
                    continue;
                }
                out.add(new int[]{i, j});
            }
        }
        out.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        return out;
    }

    /**
     * Пуста ли линза ребра {@code i–j}.
     *
     * <p>Кандидаты на свидетеля {@code r} обязаны лежать ближе {@code |ij|}
     * к {@code i}, поэтому достаточно перебрать соседей {@code i}
     * в радиусе {@code maxLen ≥ |ij|} — список {@code nearI}.
     *
     * @param d2    квадрат длины ребра {@code |ij|²}
     * @param nearI все точки в радиусе {@code maxLen} от {@code i} (включая j)
     */
    private static boolean luneEmpty(double[] xs, double[] zs, int i, int j, double d2,
                                     List<Integer> nearI) {
        for (int r : nearI) {
            if (r == i || r == j) {
                continue;
            }
            // max(|ir|², |jr|²) < |ij|²  ⇔  r строго внутри линзы
            if (dist2(xs, zs, i, r) < d2 && dist2(xs, zs, j, r) < d2) {
                return false;
            }
        }
        return true;
    }

    /** Все точки (кроме самой {@code i}) в радиусе {@code maxLen} от точки {@code i}. */
    private static void collectNear(Map<Long, List<Integer>> grid, double[] xs, double[] zs,
                                    int i, double maxLen, double maxLen2, List<Integer> out) {
        out.clear();
        long cx = cell(xs[i], maxLen);
        long cz = cell(zs[i], maxLen);
        for (long gx = cx - 1; gx <= cx + 1; gx++) {
            for (long gz = cz - 1; gz <= cz + 1; gz++) {
                List<Integer> bucket = grid.get(cellKey(gx, gz));
                if (bucket == null) {
                    continue;
                }
                for (int r : bucket) {
                    if (r != i && dist2(xs, zs, i, r) <= maxLen2) {
                        out.add(r);
                    }
                }
            }
        }
    }

    private static double dist2(double[] xs, double[] zs, int a, int b) {
        double dx = xs[a] - xs[b];
        double dz = zs[a] - zs[b];
        return dx * dx + dz * dz;
    }

    private static long cell(double v, double size) {
        return (long) Math.floor(v / size);
    }

    private static long cellKey(long cx, long cz) {
        return (cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * Детерминированный 64-битный хеш (SplitMix64-финализатор).
     *
     * <p>Используется для приоритетов ячеек поля и для решений «оставить/убрать»,
     * которые не должны зависеть от порядка обхода.
     *
     * @param v входное значение
     * @return перемешанное значение
     */
    public static long mix64(long v) {
        long z = v + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Детерминированный хеш ячейки сетки поля от сида и координат ячейки.
     *
     * @param seed сид мира
     * @param gx   индекс ячейки по X
     * @param gz   индекс ячейки по Z
     * @return хеш ячейки
     */
    public static long cellHash(long seed, long gx, long gz) {
        return mix64(seed ^ mix64(gx * 0x632BE59BD9B4E019L ^ mix64(gz + 0x2545F4914F6CDD1DL)));
    }
}
