package dev.sixik.artisans_delight.libs.world;

/**
 * Thrown by {@code attach} when the type's {@link
 * WorldObjectComponentType.DuplicatePolicy#REJECT} forbids a second instance.
 */
public final class DuplicateComponentException extends WorldObjectException {

    public DuplicateComponentException(String message) {
        super(message);
    }
}
