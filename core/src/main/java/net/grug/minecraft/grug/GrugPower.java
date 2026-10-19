package net.grug.minecraft.grug;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The energy a grug block entity receives, which is how one machine powers another: an engine hands
 * energy to the pipe it faces, and the pipe spends it.
 *
 * <p>A block entity declares that it is a receiver and how much it can hold, and then anything can
 * add to its store and the receiver itself can take from it. That is the shape of a Forge or Fabric
 * energy storage with the parts no caller needed left out: there is no simulation, no minimum and
 * no limit per delivery, because a sender that wants any of those can do it with what it can read.
 *
 * <p>The limit a block entity declared is kept here, keyed by the block entity itself the way {@link
 * GrugSlotAccess} keeps its rules, and is not saved: the script declares it again whenever its grug
 * entity is created. The energy in the store is saved, as a saved number the script cannot reach
 * (see {@link GrugSavedNumbers#isReserved}), so it is written into the block entity's NBT by every
 * loader's existing code and survives its chunk being unloaded and loaded again.
 *
 * <p>Energy is a whole number from 0 to {@link Integer#MAX_VALUE}, because it is counted in units
 * that cannot be split. Anything else is reported rather than rounded, as {@link
 * GrugTestState#checkTickCount} does for ticks.
 */
public final class GrugPower {

    /** The saved number the stored energy lives in. */
    static final String STORED = GrugSavedNumbers.RESERVED_PREFIX + "energy_stored";

    /** The most a receiver holds, per block entity that declared one. */
    private static final Map<Object, Double> LIMITS = new WeakHashMap<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugPower() {}

    /**
     * Why {@code value} is not an amount of energy, or null when it is one. {@code what} names it
     * in the message, such as "The amount".
     */
    public static String checkEnergy(String what, double value) {
        if (value >= 0 && value <= Integer.MAX_VALUE && value == Math.floor(value)) {
            return null;
        }
        return what
                + " of energy has to be a whole number from 0 to "
                + Integer.MAX_VALUE
                + ", and was "
                + value
                + ".";
    }

    /** Why this block entity cannot be a receiver, or null when it can. */
    public static String checkCanDeclare(Object blockEntity) {
        if (!GrugSavedNumbers.isHolder(blockEntity)) {
            return "Only a grug block entity can receive energy, and this block entity is not one.";
        }
        return null;
    }

    /** Why this block entity is not a receiver, or null when it is. */
    public static String checkIsReceiver(Object blockEntity) {
        if (!isReceiver(blockEntity)) {
            return "This block entity is not an energy receiver. Declare it with"
                    + " set_power_receiver in its init(), and ask is_power_receiver before using a"
                    + " block entity that is not your own.";
        }
        return null;
    }

    /**
     * Declares the block entity a receiver holding at most {@code maxStored}, replacing an earlier
     * declaration. The energy already stored stays, cut down to the new limit when that is lower,
     * which is what a script that declares again after a chunk load needs: the energy it was saved
     * with is still there.
     */
    public static void declare(Object blockEntity, double maxStored) {
        synchronized (LIMITS) {
            LIMITS.put(blockEntity, maxStored);
        }
        double stored = stored(blockEntity);
        if (stored > maxStored) {
            GrugSavedNumbers.set(blockEntity, STORED, maxStored);
        }
    }

    /** Whether the block entity declared itself a receiver. */
    public static boolean isReceiver(Object blockEntity) {
        synchronized (LIMITS) {
            return LIMITS.containsKey(blockEntity);
        }
    }

    /** The energy the block entity holds, which is 0 for one that never received any. */
    public static double stored(Object blockEntity) {
        return GrugSavedNumbers.get(blockEntity, STORED);
    }

    /**
     * Adds {@code amount} to the store as far as it has room, and returns how much was added. The
     * rest is lost, which is what a full store does to whatever is sent to it.
     */
    public static double receive(Object blockEntity, double amount) {
        double limit;
        synchronized (LIMITS) {
            limit = LIMITS.get(blockEntity);
        }
        double stored = stored(blockEntity);
        double added = Math.min(amount, limit - stored);
        // Nothing moved is nothing to save: marking the block entity changed makes the game write
        // its chunk, and a store that is full or sent nothing would do that every tick.
        if (added > 0) {
            GrugSavedNumbers.set(blockEntity, STORED, stored + added);
            return added;
        }
        return 0;
    }

    /**
     * Takes up to {@code max} from the store, everything in it when that is no more than {@code
     * max}, and returns how much was taken.
     */
    public static double extract(Object blockEntity, double max) {
        double stored = stored(blockEntity);
        double taken = Math.min(stored, max);
        // Not saved when nothing moved, for the same reason as in receive.
        if (taken > 0) {
            GrugSavedNumbers.set(blockEntity, STORED, stored - taken);
            return taken;
        }
        return 0;
    }
}
