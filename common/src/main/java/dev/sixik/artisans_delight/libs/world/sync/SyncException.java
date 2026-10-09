package dev.sixik.artisans_delight.libs.world.sync;

/**
 * Base for all {@code libs.world.sync} failures. Unchecked to keep tick paths clean.
 */
public class SyncException extends RuntimeException {

    public SyncException(String message) {
        super(message);
    }

    public SyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
