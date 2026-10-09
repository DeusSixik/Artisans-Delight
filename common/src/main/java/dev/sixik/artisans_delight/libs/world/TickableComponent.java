package dev.sixik.artisans_delight.libs.world;

/**
 * Component that receives ticks from {@link WorldObject#tick(long)}.
 *
 * <p>The {@code tick} argument is a manager-wide monotonic counter, not a timestamp.
 * A throwing {@code onTick} does not stop other components: the failure is reported to
 * {@link WorldObjectListener#onComponentError} and aggregated into
 * {@link WorldObject.TickException}.
 */
public interface TickableComponent extends WorldObjectComponent {

    /**
     * Performs one tick. Must be thread-safe, see {@link WorldObjectComponent}.
     */
    void onTick(long tick);
}
