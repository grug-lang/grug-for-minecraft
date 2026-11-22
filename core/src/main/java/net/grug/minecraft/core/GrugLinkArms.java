package net.grug.minecraft.core;

import java.util.Collection;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The decisions of a loader's {@link GrugControlServer.Link} that only a race or a missing handle
 * reaches, as pure functions: a starter who left between the server's check and its send, a start
 * that arrives before its sender finished login, and a drain with no server or no starter to drain.
 *
 * <p>A link reads the game for the handle, and these functions answer what to do with it, so the
 * unit test of every combination lives here where the coverage gate counts it, and the link keeps
 * only the calls that need the game.
 */
public final class GrugLinkArms {
    private GrugLinkArms() {}

    /**
     * Whether {@code player} is known: a start from a sender whose player is not found has not
     * finished login.
     */
    public static boolean known(Object player) {
        return player != null;
    }

    /**
     * Runs {@code send} on {@code player} and answers true, or answers false without calling it
     * when the player is gone.
     */
    public static <T> boolean deliver(T player, Consumer<T> send) {
        return deliverIf(player != null, player, send);
    }

    /**
     * Runs {@code send} on {@code target} and answers true, or answers false when not {@code here}.
     */
    public static <T> boolean deliverIf(boolean here, T target, Consumer<T> send) {
        if (!here) {
            return false;
        }
        send.accept(target);
        return true;
    }

    /**
     * Whether {@code player} is still in the world: it is known, the world exists and its players
     * hold it. A dead connection takes a packet without a word, so the world is what is asked.
     */
    public static <W> boolean stillHere(
            Object player, W world, Function<W, Collection<?>> playersOf) {
        return player != null && world != null && playersOf.apply(world).contains(player);
    }

    /** Whether {@code player} is known and {@code connected} says they are still on the server. */
    public static <T> boolean stillConnected(T player, Predicate<T> connected) {
        return player != null && connected.test(player);
    }

    /**
     * Polls {@code poll} until it answers false, and does nothing when there is no {@code owner}.
     */
    public static void drain(Object owner, BooleanSupplier poll) {
        if (owner == null) {
            return;
        }
        while (poll.getAsBoolean()) {}
    }

    /** Hands {@code value} to {@code action} when it is an instance of {@code type}. */
    public static <T> void applyTo(Object value, Class<T> type, Consumer<T> action) {
        if (type.isInstance(value)) {
            action.accept(type.cast(value));
        }
    }
}
