package net.grug.minecraft.core;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * The decisions of the 1.2.5 dedicated server's glue (#1645) that depend only on values the loader
 * reads, so every combination is a unit test here where the coverage gate counts it, and the
 * loader's classes keep only the calls that need the game. Arms that need a race, a failing run or
 * a client that fell through the ground to be taken live here as pure functions, and the loader
 * calls them from a place no test has to force.
 */
public final class GrugForge125Arms {
    private GrugForge125Arms() {}

    /** What a weak reference still holds, or null for a missing or cleared reference. */
    public static <T> T referent(WeakReference<T> reference) {
        return reference == null ? null : reference.get();
    }

    /** Whether both values are known: a connection and its player are learnt together. */
    public static boolean bothKnown(Object first, Object second) {
        return first != null && second != null;
    }

    /** {@code value} when {@code condition} holds, else null. */
    public static <T> T onlyIf(boolean condition, T value) {
        return condition ? value : null;
    }

    /** Whether both conditions hold. */
    public static boolean both(boolean first, boolean second) {
        return first && second;
    }

    /** {@code value}, or an {@link IllegalStateException} with {@code message} when it is null. */
    public static <T> T required(T value, String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    /** {@code first} when it is known, else what {@code other} gives. */
    public static <T> T orElseGet(T first, Supplier<T> other) {
        return first != null ? first : other.get();
    }

    /**
     * Whether the server's tick drives the run: not once the run is over, not from inside a tick
     * the run itself advances, and only in a CI run.
     */
    public static boolean runs(boolean done, boolean advancing, boolean ci) {
        return !done && !advancing && ci;
    }

    /** Runs {@code body}, and hands whatever it throws to {@code onFail}. */
    public static void guarded(Runnable body, Consumer<Throwable> onFail) {
        try {
            body.run();
        } catch (Throwable thrown) {
            onFail.accept(thrown);
        }
    }

    /**
     * Whether the run has lost its test player: they died, or are no longer the one it started
     * with.
     */
    public static boolean playerLost(boolean dead, int health, boolean sameAsStarted) {
        return dead || health <= 0 || !sameAsStarted;
    }

    /** How much health the player is missing, which the run heals back each tick. */
    public static int healed(int health, int maxHealth) {
        return Math.max(0, maxHealth - health);
    }

    /**
     * The feet height the hold keeps the player at, 0 before the run has started. {@code
     * feetBelowPosition} is asked only once it has: a player does not exist before that.
     */
    public static double heldFeet(double holdY, DoubleSupplier feetBelowPosition) {
        return holdY == 0 ? 0 : holdY - feetBelowPosition.getAsDouble();
    }

    /**
     * Whether the run may start: a player is here, the run has a starter, that starter's player is
     * found and their world is ready. {@code worldReady} is asked last, only for a found starter.
     */
    public static boolean ready(
            boolean hasPlayers,
            boolean hasStarter,
            boolean starterFound,
            BooleanSupplier worldReady) {
        return hasPlayers && hasStarter && starterFound && worldReady.getAsBoolean();
    }

    /** Whether the test player is no longer on the server. */
    public static boolean playerGone(List<?> players, Object testPlayer) {
        return players.isEmpty() || !players.contains(testPlayer);
    }

    /** When the wait for a client began: the first call's time, kept by every later one. */
    public static long startedAt(long waitStartMillis, long nowMillis) {
        return waitStartMillis == 0 ? nowMillis : waitStartMillis;
    }
}
