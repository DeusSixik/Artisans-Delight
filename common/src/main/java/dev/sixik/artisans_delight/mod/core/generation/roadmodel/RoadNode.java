package dev.sixik.artisans_delight.mod.core.generation.roadmodel;

import dev.sixik.artisans_delight.mod.core.knot.KnotType;

import java.util.Objects;
import java.util.UUID;

/**
 * Виртуальный узел дорожной сети.
 *
 * <p>Чистая модель без привязки к миру: никаких {@code WorldObject},
 * компонентов и менеджера. Только данные для расчёта и отображения.
 *
 * <p>Идентификатор узла стабилен в пределах одного расчёта: города получают
 * детерминированный UUID от сида и позиции (чтобы повторный расчёт давал
 * те же id), промежуточные узлы — порядковый индекс.
 */
public final class RoadNode {

    /** Тип узла: город или промежуточный перекрёсток. */
    public enum Kind {
        TOWN,
        WAYPOINT
    }

    private final UUID id;
    private final Kind kind;
    private final String name;
    private final int x;
    private final int y;
    private final int z;
    /**
     * UUID связанного игрового объекта ({@code WorldObject}) для городов.
     * Нужен карте, чтобы подтянуть живые данные: инвестиции, порты, склады.
     * Для промежуточных узлов — {@code null} (у них нет игрового объекта).
     */
    private final UUID sourceId;

    public RoadNode(UUID id, Kind kind, String name, int x, int y, int z) {
        this(id, kind, name, x, y, z, null);
    }

    public RoadNode(UUID id, Kind kind, String name, int x, int y, int z, UUID sourceId) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = name == null ? "" : name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.sourceId = sourceId;
    }

    public UUID id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isTown() {
        return kind == Kind.TOWN;
    }

    public String name() {
        return name;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /** UUID игрового объекта города или {@code null} для промежуточных узлов. */
    public UUID sourceId() {
        return sourceId;
    }

    /** Совместимость с существующим кодом карты (иконки, тултипы). */
    public KnotType knotType() {
        return kind == Kind.TOWN ? KnotType.TOWN : KnotType.WAYPOINT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RoadNode other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "RoadNode{" + kind + " '" + name + "' [" + x + ", " + z + "]}";
    }
}
