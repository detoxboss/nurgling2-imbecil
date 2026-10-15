package nurgling.tools;

import haven.Disposable;

/**
 * Holds one cached, disposable value (a built tooltip texture) and frees the previous one when it is
 * replaced or dropped.
 * <p>
 * The tooltip texture of an item is rebuilt whenever Shift changes or the item's info changes, and the
 * engine only frees the textures it created itself, so without this every rebuild would leave the old
 * texture to the garbage collector's finalizer. Disposing is safe while a frame that used the texture
 * is still in flight: render objects are reference counted and freed only once unused. A texture that
 * is drawn again after being disposed is simply uploaded again, so this can never blank a tooltip.
 */
public final class DisposableSlot<T extends Disposable> {
    private T value = null;

    public T get() {
        return value;
    }

    /** Make {@code next} the held value, disposing the previous one (unless it is the same object). */
    public T replace(T next) {
        T old = value;
        value = next;
        if(old != null && old != next)
            old.dispose();
        return next;
    }

    /** Drop the held value, disposing it. */
    public void clear() {
        replace(null);
    }
}
