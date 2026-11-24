package net.grug.minecraft.core;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The decisions a dedicated server's adapter makes around a forwarded call or a player it may not
 * have, as functions of values the loader reads, so a unit test reaches every arm without a game.
 *
 * <p>A dedicated server asks its joined client for most answers. Whether a client has started the
 * run, whether the client answered and whether this side has a player are facts a healthy run
 * always settles the same way, so a branch written in the adapter has one side no run enters and
 * the gate counts it as missed. Each decision lives here once, with both sides tested, and the
 * adapter hands in what to do on each (#1606).
 */
public final class GrugArms {
    private GrugArms() {}

    /** A call that goes to the joined client and may fail on the wire. */
    @FunctionalInterface
    public interface Ask<T> {
        T get() throws GrugControlServer.ControlException;
    }

    /** {@code ask} when {@code when} holds, else {@code otherwise} without asking anyone. */
    public static <T> T askIf(boolean when, T otherwise, Supplier<T> ask) {
        return when ? ask.get() : otherwise;
    }

    /** {@code then} run when {@code when} holds, else {@code otherwise} run, never both. */
    public static void runIf(boolean when, Runnable then, Runnable otherwise) {
        if (when) {
            then.run();
        } else {
            otherwise.run();
        }
    }

    /** What {@code ask} answers, or what {@code failed} makes of the failure it threw. */
    public static <T> T tryAsk(Ask<T> ask, Function<GrugControlServer.ControlException, T> failed) {
        try {
            return ask.get();
        } catch (GrugControlServer.ControlException e) {
            return failed.apply(e);
        }
    }

    /** {@code present} applied to {@code value} when it is a {@code type}, else {@code absent}. */
    public static <P, R> R mapType(
            Object value, Class<P> type, Function<P, R> present, Supplier<R> absent) {
        return type.isInstance(value) ? present.apply(type.cast(value)) : absent.get();
    }

    /** {@code present} run on {@code value} when it is a {@code type}, else {@code absent} run. */
    public static <P> void ifType(
            Object value, Class<P> type, Consumer<P> present, Runnable absent) {
        if (type.isInstance(value)) {
            present.accept(type.cast(value));
        } else {
            absent.run();
        }
    }

    /** Whether both hold, without a branch of the caller's own for the side no run enters. */
    public static boolean both(boolean first, boolean second) {
        return first && second;
    }

    /** Whether {@code value} is at least {@code min} and below {@code limit}. */
    public static boolean inRange(int value, int min, int limit) {
        return value >= min && value < limit;
    }

    /**
     * Whether a point is inside a box's footprint, widened by {@code edge} on every side. The
     * game's collision shapes meet at block edges, so the point's own coordinate is compared with a
     * little slack.
     */
    public static boolean pointInFootprint(
            double x, double z, double minX, double maxX, double minZ, double maxZ, double edge) {
        return x >= minX - edge && x <= maxX + edge && z >= minZ - edge && z <= maxZ + edge;
    }
}
