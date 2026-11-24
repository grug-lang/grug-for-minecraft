package net.grug.minecraft.core;

import java.util.Collection;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

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

    /** The key of the first entry of {@code map} whose value is {@code value} itself, else null. */
    public static <K, V> K keyOf(Map<K, V> map, V value) {
        synchronized (map) {
            for (Map.Entry<K, V> entry : map.entrySet()) {
                if (entry.getValue() == value) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** {@code first} when it is known, else what {@code next} finds. */
    public static <T> T orElse(T first, Supplier<T> next) {
        return first != null ? first : next.get();
    }

    /** Records {@code value} under {@code key} only when both are known. */
    public static <K, V> void learn(Map<K, V> map, K key, V value) {
        if (key != null && value != null) {
            map.put(key, value);
        }
    }

    /** Whether {@code adapter} exists and {@code sideOf} says it is a dedicated server's. */
    public static <A> boolean onDedicatedServer(A adapter, Function<A, GrugSide> sideOf) {
        return adapter != null && sideOf.apply(adapter) == GrugSide.DEDICATED_SERVER;
    }

    /** Answers {@code answer} when {@code here}, else false without asking it. */
    public static boolean ifHere(boolean here, BooleanSupplier answer) {
        return here && answer.getAsBoolean();
    }

    /** Runs {@code task} when {@code here}. */
    public static void runIf(boolean here, Runnable task) {
        if (here) {
            task.run();
        }
    }

    /** Runs {@code onServer} when {@code server}, else {@code otherwise}. */
    public static void choose(boolean server, Runnable onServer, Runnable otherwise) {
        if (server) {
            onServer.run();
        } else {
            otherwise.run();
        }
    }

    /**
     * Runs {@code onServer} on {@code argument} when {@code server}, else {@code otherwise}. The
     * argument is passed through so that neither arm needs a lambda that captures it.
     */
    public static <T> void chooseWith(
            boolean server, T argument, Consumer<T> onServer, Consumer<T> otherwise) {
        if (server) {
            onServer.accept(argument);
        } else {
            otherwise.accept(argument);
        }
    }

    /**
     * A copy of the members {@code membersOf} reads from {@code world}, or an empty list before the
     * world exists.
     */
    public static <W> java.util.List<Object> membersOrNone(
            W world, Function<W, Collection<?>> membersOf) {
        return world == null
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<Object>(membersOf.apply(world));
    }

    /** A task of three values, for {@link #runIfWith} so a caller needs no capturing lambda. */
    public interface Task3<A, B, C> {
        void run(A a, B b, C c);
    }

    /** Runs {@code task} on the three values when {@code here}. */
    public static <A, B, C> void runIfWith(boolean here, A a, B b, C c, Task3<A, B, C> task) {
        if (here) {
            task.run(a, b, c);
        }
    }

    /** What {@code map} makes of {@code value}, or null when there is none. */
    public static <T, R> R mapOrNull(T value, Function<T, R> map) {
        return value == null ? null : map.apply(value);
    }

    /** Whether both are true. */
    public static boolean bothHere(boolean first, boolean second) {
        return first && second;
    }

    /** {@code answer} when {@code here}, else {@code otherwise} without asking it. */
    public static boolean ifHereElse(boolean here, boolean otherwise, BooleanSupplier answer) {
        return here ? answer.getAsBoolean() : otherwise;
    }

    /**
     * The entry of {@code table} at {@code index}, or null when the index is outside it or empty.
     */
    public static <T> T tableEntry(T[] table, int index) {
        return index > 0 && index < table.length ? table[index] : null;
    }

    /**
     * Runs {@code send} on {@code value} when there is one, and else gives {@code report} the
     * {@code message} and runs nothing.
     */
    public static <T> void deliverOrReport(
            T value, String message, Consumer<String> report, Consumer<T> send) {
        if (value == null) {
            report.accept(message);
            return;
        }
        send.accept(value);
    }

    /** A call to the joined client that can be refused. */
    public interface ControlCall<T> {
        T call() throws GrugControlServer.ControlException;
    }

    /** A call to the joined client that answers nothing and can be refused. */
    public interface ControlRun {
        void run() throws GrugControlServer.ControlException;
    }

    /**
     * What {@code call} answers, or, when the client refuses it, {@code prefix} and the refusal
     * given to {@code report} and {@code otherwise} answered.
     */
    public static <T> T callOrReport(
            ControlCall<T> call, String prefix, T otherwise, Consumer<String> report) {
        try {
            return call.call();
        } catch (GrugControlServer.ControlException e) {
            report.accept(prefix + e.getMessage());
            return otherwise;
        }
    }

    /**
     * Runs {@code run}, giving {@code report} {@code prefix} and the refusal when the client
     * refuses.
     */
    public static void runOrReport(ControlRun run, String prefix, Consumer<String> report) {
        callOrReport(
                () -> {
                    run.run();
                    return Boolean.TRUE;
                },
                prefix,
                Boolean.FALSE,
                report);
    }

    /** Hands each of {@code keys} that {@code present} does not hold to {@code onMissing}. */
    public static <K> void forEachMissing(
            Collection<K> keys, Collection<?> present, Consumer<K> onMissing) {
        for (K key : keys) {
            if (!present.contains(key)) {
                onMissing.accept(key);
            }
        }
    }

    /** Runs {@code task} when the environment says this is a CI run ({@code GRUG_CI=true}). */
    public static void runIfCi(Function<String, String> env, Runnable task) {
        runIf("true".equals(env.apply("GRUG_CI")), task);
    }

    /** {@code menu} when it is known and holds window {@code windowId}, else null (a stale id). */
    public static <M> M matching(M menu, int windowId, ToIntFunction<M> idOf) {
        return menu != null && idOf.applyAsInt(menu) == windowId ? menu : null;
    }
}
