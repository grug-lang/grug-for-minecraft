package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
 * <p>This has nothing to do with {@link GrugSavedNumbers}. The limit a block entity declared is not
 * saved, because the script declares it again whenever its grug entity is created. The energy in
 * the store is saved, under a key of its own in the block entity's NBT: each loader's grug block
 * entity writes {@link #stored} when the game saves it, reads it back with {@link #load} when the
 * game loads it, and marks itself changed when {@link Holder#markEnergyChanged} asks it to, which
 * is what makes the game write its chunk.
 *
 * <p>Both maps are keyed by the block entity itself, the way {@link GrugSlotAccess} keeps its
 * rules, and held weakly, so a block entity that is broken and collected takes its energy with it.
 *
 * <p>Energy is a whole number from 0 to {@link Integer#MAX_VALUE}, because it is counted in units
 * that cannot be split. Anything else is reported rather than rounded, as {@link
 * GrugTestState#checkTickCount} does for ticks.
 */
public final class GrugPower {

    /** The most a receiver holds, per block entity that declared one. */
    private static final Map<Object, Double> LIMITS = new WeakHashMap<>();

    /** The energy a receiver holds. A block entity absent from the map holds none. */
    private static final Map<Object, Double> STORES = new WeakHashMap<>();

    /** How much energy a receiver asks a transport for, per block entity that asked. */
    private static final Map<Object, Double> REQUESTS = new WeakHashMap<>();

    /**
     * One thing a neighbour handed a block entity's inbox: how much, on which side, and the world
     * time it arrived at.
     */
    private static final class Mail {
        final int side;
        final long time;
        final double amount;

        Mail(int side, long time, double amount) {
            this.side = side;
            this.time = time;
            this.amount = amount;
        }
    }

    /**
     * The energy and the requests that arrived in a block entity's inbox, kept apart by whether
     * they are energy or a request. A list rather than six totals, because a delivery made during
     * the world time a take runs in has to stay for a later one: that is what keeps the answer from
     * depending on whether the sending neighbour ticked before or after this block entity.
     */
    private static final Map<Object, List<Mail>> RECEIVED = new WeakHashMap<>();

    private static final Map<Object, List<Mail>> REQUESTED = new WeakHashMap<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugPower() {}

    /**
     * A block entity that can hold energy, which every loader's grug block entity is. An interface
     * rather than a callback for the same reason as {@link GrugSavedNumbers.Holder}: a callback
     * kept as a value of a weak map would hold its block entity strongly.
     */
    public interface Holder {
        /**
         * Marks the block entity changed, so the game saves its chunk. A chunk the game thinks is
         * unchanged is not written, and the energy with it.
         */
        void markEnergyChanged();
    }

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

    /** Why this block entity is not a receiver, or null when it is. */
    public static String checkIsReceiver(Object blockEntity) {
        if (!isReceiver(blockEntity)) {
            return "This block entity is not an energy receiver. Declare it with"
                    + " BlockEntityInitContext.set_power_receiver in its init(), and ask"
                    + " is_power_receiver before using a block entity that is not your own.";
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
        if (stored(blockEntity) > maxStored) {
            store(blockEntity, maxStored);
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
        synchronized (STORES) {
            Double stored = STORES.get(blockEntity);
            return stored != null ? stored : 0.0;
        }
    }

    /**
     * Puts back the energy a block entity was saved with, which is what loading its NBT means. It
     * is not a change, so it does not mark the block entity.
     */
    public static void load(Object blockEntity, double stored) {
        synchronized (STORES) {
            STORES.put(blockEntity, stored);
        }
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
            store(blockEntity, stored + added);
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
            store(blockEntity, stored - taken);
            return taken;
        }
        return 0;
    }

    /**
     * Why {@code value} is not a side, or null when it is. A side is numbered as every side in the
     * API is: 0 down, 1 up, 2 north, 3 south, 4 west and 5 east.
     */
    public static String checkSide(String what, double value) {
        if (value >= 0 && value <= 5 && value == Math.floor(value)) {
            return null;
        }
        return what + " has to be a whole number from 0 to 5, and was " + value + ".";
    }

    /**
     * Why {@code value} is not an amount of energy a transport carries, or null when it is. A
     * transport carries fractions, because it splits what it holds between its sides, so unlike
     * {@link #checkEnergy} this accepts them.
     */
    public static String checkCarriedEnergy(String what, double value) {
        if (value >= 0 && Double.isFinite(value)) {
            return null;
        }
        return what
                + " of carried energy has to be a finite number from 0 up, and was "
                + value
                + ".";
    }

    /** Why this block entity has no inbox, or null when it has one. */
    public static String checkInbox(Object blockEntity) {
        if (!GrugSavedNumbers.isHolder(blockEntity)) {
            return "Only a grug block entity has an energy inbox, and this block entity is not"
                    + " one.";
        }
        return null;
    }

    /**
     * Hands {@code amount} of energy into the block entity's inbox on {@code side}, stamped with
     * the world time it arrived at. The amount is not rounded: a transport carries fractions.
     */
    public static void receiveOn(Object blockEntity, int side, double amount, long time) {
        deliver(RECEIVED, blockEntity, new Mail(side, time, amount));
    }

    /** Hands {@code amount} of a request into the block entity's request inbox on {@code side}. */
    public static void requestOn(Object blockEntity, int side, double amount, long time) {
        deliver(REQUESTED, blockEntity, new Mail(side, time, amount));
    }

    private static void deliver(Map<Object, List<Mail>> mailbox, Object blockEntity, Mail mail) {
        synchronized (mailbox) {
            List<Mail> mails = mailbox.get(blockEntity);
            if (mails == null) {
                mails = new ArrayList<>();
                mailbox.put(blockEntity, mails);
            }
            mails.add(mail);
        }
    }

    /**
     * Takes everything that arrived on {@code side} before {@code now}, adds it up and empties
     * those entries. What arrived during {@code now} stays for a later call, so the answer does not
     * depend on the order the game ticked the sender and the receiver.
     */
    public static double takeReceived(Object blockEntity, int side, long now) {
        return take(RECEIVED, blockEntity, side, now);
    }

    /** Takes what arrived in the request inbox on {@code side} before {@code now}. */
    public static double takeRequested(Object blockEntity, int side, long now) {
        return take(REQUESTED, blockEntity, side, now);
    }

    private static double take(
            Map<Object, List<Mail>> mailbox, Object blockEntity, int side, long now) {
        synchronized (mailbox) {
            List<Mail> mails = mailbox.get(blockEntity);
            if (mails == null) {
                return 0;
            }
            double total = 0;
            Iterator<Mail> iterator = mails.iterator();
            while (iterator.hasNext()) {
                Mail mail = iterator.next();
                if (mail.side == side && mail.time < now) {
                    total += mail.amount;
                    iterator.remove();
                }
            }
            if (mails.isEmpty()) {
                mailbox.remove(blockEntity);
            }
            return total;
        }
    }

    /**
     * Declares how much energy this receiver asks a transport for, which is the reference's {@code
     * IPowerReceptor.powerRequest}. A transport reads it with {@link #request} and asks nothing of
     * a receiver that never declared one, because a machine that is not working wants nothing.
     */
    public static void declareRequest(Object blockEntity, double amount) {
        synchronized (REQUESTS) {
            REQUESTS.put(blockEntity, amount);
        }
    }

    /** The energy the receiver asks a transport for, which is 0 until it declares one. */
    public static double request(Object blockEntity) {
        synchronized (REQUESTS) {
            Double request = REQUESTS.get(blockEntity);
            return request != null ? request : 0.0;
        }
    }

    /** Sets the store and has the block entity marked changed. */
    private static void store(Object blockEntity, double stored) {
        load(blockEntity, stored);
        ((Holder) blockEntity).markEnergyChanged();
    }
}
