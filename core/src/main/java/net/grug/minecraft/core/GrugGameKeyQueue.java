package net.grug.minecraft.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;

/**
 * Test only: the key presses a test queued for the game's own LWJGL 2 key loop, and which of them
 * is being delivered right now.
 *
 * <p>LWJGL 2's event queue cannot be written to, so a loader whose game reads {@code
 * Keyboard.next()} in its tick redirects those calls here: a queued key is answered as a press and
 * then as its release, and once nothing is queued the real keyboard answers as it always did. Each
 * key therefore takes the game's ordinary key path, with the game's own numbering, so a binding the
 * game polls itself sees it. This class holds the queue's state and nothing of the game, so it is
 * measured without a game.
 */
public final class GrugGameKeyQueue {

    /** The keys still to deliver, each as one press followed by its release. */
    private static final Deque<Integer> pending = new ArrayDeque<Integer>();

    /** Whether the head key's press has been delivered, so its release is due next. */
    private static boolean pressDelivered;

    /** Whether the last {@link #next} answered a queued key rather than the game's keyboard. */
    private static boolean serving;

    /** The key the last {@link #next} answered, which its release repeats. */
    private static int servedKey;

    /** Whether the last {@link #next} answered a press rather than a release. */
    private static boolean servedPress;

    private GrugGameKeyQueue() {}

    /**
     * Drops every key still to deliver and the press the game is being served, so a press a test
     * queued as it ended cannot reach the next test. See #1926.
     */
    public static void forget() {
        pending.clear();
        pressDelivered = false;
        serving = false;
        servedKey = 0;
        servedPress = false;
    }

    /** Queues one press of {@code gameKey}, in this loader's own key numbering. */
    public static void queue(int gameKey) {
        pending.addLast(Integer.valueOf(gameKey));
    }

    /**
     * Answers {@code gameNext} once a queued key is due, or asks the game's own keyboard when none
     * is: {@code true} means the loop has an event, and {@link #eventKey} and {@link
     * #eventKeyState} then describe it.
     */
    public static boolean next(BooleanSupplier gameNext) {
        serving = false;
        Integer key = pending.peekFirst();
        if (key == null) return gameNext.getAsBoolean();
        serving = true;
        servedKey = key.intValue();
        if (pressDelivered) {
            pending.removeFirst();
            pressDelivered = false;
            servedPress = false;
        } else {
            pressDelivered = true;
            servedPress = true;
        }
        return true;
    }

    /**
     * The key of the event the game is handling, or {@code gameKey} while the game's keyboard has
     * it.
     */
    public static int eventKey(int gameKey) {
        return serving ? servedKey : gameKey;
    }

    /** Whether the event is a press, or {@code gameState} while the game's keyboard has it. */
    public static boolean eventKeyState(boolean gameState) {
        return serving ? servedPress : gameState;
    }
}
