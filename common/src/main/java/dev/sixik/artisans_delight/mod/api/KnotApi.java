package dev.sixik.artisans_delight.mod.api;

import dev.sixik.artisans_delight.mod.core.knot.KnotType;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.regex.Pattern;

public final class KnotApi {

    public static final Pattern VILLAGE_REGEX = Pattern.compile("(?i).*village.*", Pattern.CASE_INSENSITIVE);

    public static KnotType getType(Holder<Structure> structureHolder) {
        final boolean isSupport = VILLAGE_REGEX.matcher(structureHolder.getRegisteredName()).find();
        return isSupport ? KnotType.TOWN : KnotType.NONE;
    }


}
