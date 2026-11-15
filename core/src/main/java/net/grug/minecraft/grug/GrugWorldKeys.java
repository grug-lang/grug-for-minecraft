package net.grug.minecraft.grug;

/**
 * Turns the held state of a key into presses, for a loader that has no key event to subscribe to
 * while no screen is open.
 *
 * <p>A press is reported once, on the first poll that sees the key down, and a key held across
 * polls is not a press again, which is how a toggle flips once per press and not once per tick. The
 * state is kept for every poll, a poll made while a screen was open included, so a key still held
 * when the screen closes is not reported as a press the player never made. Only a letter and a
 * digit are tracked, by the same upper case character code {@code OverlayKeyContext.key} answers.
 */
public final class GrugWorldKeys {
    /** The first code tracked, the digit zero. */
    private static final int FIRST = '0';

    /** One past the last code tracked, the upper case Z. */
    private static final int END = 'Z' + 1;

    private final boolean[] down = new boolean[END - FIRST];

    /** Whether the code is one a world key export is asked about: a letter or a digit. */
    public static boolean tracked(int code) {
        return (code >= '0' && code <= '9') || (code >= 'A' && code <= 'Z');
    }

    /**
     * Records the key's state for this poll and answers whether it just went down.
     *
     * @param code the key's upper case character code, which must be one {@link #tracked} accepts
     * @param isDown whether the key is held now
     */
    public boolean pressed(int code, boolean isDown) {
        int index = code - FIRST;
        boolean was = down[index];
        down[index] = isDown;
        return isDown && !was;
    }

    /**
     * Records the key's state for this poll and answers whether the export is to be asked: the key
     * just went down and no screen is open. A key that went down while a screen was open, or is
     * still held from before it closed, is recorded and never delivered.
     */
    public boolean deliver(int code, boolean isDown, boolean screenClosed) {
        return pressed(code, isDown) && screenClosed;
    }
}
