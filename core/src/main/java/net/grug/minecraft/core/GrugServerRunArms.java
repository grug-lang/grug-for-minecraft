package net.grug.minecraft.core;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjDoubleConsumer;

/**
 * The decisions a dedicated server's run loop makes around a player, a held height and a failure,
 * as functions of values the loader reads, so a unit test reaches every arm without a game (#1608).
 *
 * <p>A healthy run settles each of these the same way every time (the server is there, the player
 * is on the height the run holds, nothing throws), so a branch written in the loader has one side
 * no run enters. Each decision lives here once, with both sides tested.
 */
public final class GrugServerRunArms {
    private GrugServerRunArms() {}

    /** Whether either holds. */
    public static boolean either(boolean first, boolean second) {
        return first || second;
    }

    /**
     * {@code action} on what {@code inner} reads off {@code outer}, when both are there: a server
     * that has not seen anyone log in has none, and a server without a player manager has no one to
     * send to.
     */
    public static <S, P> void withPresent(S outer, Function<S, P> inner, Consumer<P> action) {
        if (outer == null) {
            return;
        }
        P value = inner.apply(outer);
        if (value != null) {
            action.accept(value);
        }
    }

    /** {@code feetY} rounded to a millionth, which sheds a float eye height's last bits. */
    public static double roundedHeight(double feetY) {
        return Math.rint(feetY * 1e6) / 1e6;
    }

    /**
     * Holds {@code player} at the height the run keeps them at when they are a tolerance under it,
     * else on the floor when they are below it, else leaves them be. The floor is handed to the
     * on-floor arm, which put the player there, so that arm reads the world once rather than twice.
     *
     * <p>{@code holdsHeight} is separate from {@code held} because 0 is a height a run can hold: a
     * test that moved the player to feet 0 would otherwise read as a run holding none, and the
     * height arm would never run again (#1847).
     */
    public static <P> void hold(
            P player,
            boolean holdsHeight,
            double held,
            double feet,
            double tolerance,
            double floor,
            Consumer<P> atHeight,
            ObjDoubleConsumer<P> onFloor) {
        if (holdsHeight && feet < held - tolerance) {
            atHeight.accept(player);
        } else if (GrugPlayerFloor.below(feet, floor)) {
            onFloor.accept(player, floor);
        }
    }

    /** Runs {@code task}, and hands what it threw to {@code failed} instead of letting it out. */
    public static void guard(Runnable task, Consumer<Throwable> failed) {
        try {
            task.run();
        } catch (Throwable thrown) {
            failed.accept(thrown);
        }
    }
}
