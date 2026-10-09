package dev.sixik.artisans_delight.libs.world;

/**
 * Base for all {@code libs.world} failures. Unchecked to keep hot paths clean.
 */
public class WorldObjectException extends RuntimeException {

    public WorldObjectException(String message) {
        super(message);
    }

    public WorldObjectException(String message, Throwable cause) {
        super(message, cause);
    }
}
