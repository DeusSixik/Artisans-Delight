package dev.sixik.artisans_delight.libs.world;

/**
 * Thrown when attaching a component whose owner's state forbids it.
 */
public final class DetachedException extends WorldObjectException {

    public DetachedException(String message) {
        super(message);
    }
}
