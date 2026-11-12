package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

/**
 * The give host functions' path from a script to the player, in one place, so the process that runs
 * the script and the process that holds the player give the same way (#1099).
 *
 * <p>A give runs where the script's call runs. In singleplayer, and on a dedicated server's own
 * test, that process holds the player and the adapter gives there. A client joined to a server that
 * drives a run is the case that differs: the overlay's {@code click()} is the caller, and the
 * player the test asserts on is the server's copy, so the client names the item to the server and
 * the server gives it. A write to the client's copy would be overwritten by the next inventory
 * packet and the test would never see it (#882).
 *
 * <p>The item is named by {@link ModLoaderAdapter#itemName}, which both processes compute from the
 * same game and mods, so the give names an item rather than a place in a list: two lists can index
 * the same item differently, and an index would then give the wrong item with no error.
 */
public final class GrugGiveCalls {
    @net.grug.minecraft.grug.GrugGenerated("utility class: never instantiated")
    private GrugGiveCalls() {}

    /**
     * Gives {@code count} of the item to {@code player}, which is the test player of the side this
     * runs on. A client that has started a run names the item to the server instead, so the give
     * lands in the copy of the player the test reads.
     *
     * <p>{@code method} is the host function's name, which the report of an item the client cannot
     * name has to carry: it is what tells a mod which of its calls did not give.
     */
    public static void give(
            ModLoaderAdapter adapter, Object player, Object item, int count, String method) {
        if (GrugControlClient.runActiveHere()) {
            String name = adapter.itemName(item);
            if (name == null || name.isEmpty()) {
                // A joined client can only name an item its own loader can name, and a give it
                // cannot name is reported rather than written to a copy the server would overwrite.
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr,
                        method
                                + ": This loader cannot name the item, so a client joined to a"
                                + " server cannot ask the server that holds the player to give"
                                + " it.");
                return;
            }
            GrugControlClient.giveToServer(name, count);
            return;
        }
        adapter.giveItemCountToPlayer(player, item, count);
    }

    /**
     * Applies a give the joined client sent, on the server's own copy of the test player (#1099).
     * Called on the server thread, by the drain that follows the forwarded call the give was part
     * of.
     *
     * <p>The item is found by the name the client sent, not by an index, so a client and a server
     * whose item lists differ still give the same item: the client's name is compared with each of
     * the server's own names. A name no item has is reported as the error of the test that asked,
     * rather than given as some other item.
     *
     * <p>The give moves the world revision here, explicitly: the local give does it through {@code
     * HostFunctions.worldChanged}, which counts only a write made while a test's own export is
     * running, and this drain runs between two runner ticks (see {@code
     * GrugControlServer.applyClientWork}). Without the bump a settle answer the server remembered
     * for the old revision would outlive the give, so a test that clicks the overlay, asks for a
     * settled world and then captures would take a frame from before the inventory sync reached the
     * client.
     */
    public static void giveOnServer(ModLoaderAdapter adapter, String itemName, int count) {
        Object player = adapter.testPlayer();
        if (player == null) {
            report(itemName, count, "no player has joined yet");
            return;
        }
        Object item = itemNamed(adapter, itemName);
        if (item == null) {
            report(itemName, count, "the item list has no item with that name");
            return;
        }
        GrugWorldRevision.bump();
        adapter.giveItemCountToPlayer(player, item, count);
    }

    /**
     * Applies a give that was queued for another thread, unless a test's reset has run since it was
     * queued (#1144).
     *
     * <p>A give the client asked for as its test ended belongs to no test's inventory: the reset
     * that starts the next test clears the player's inventory and bumps the count before the queued
     * give lands, so applying it now would leak an item into a test that never asked for it. A give
     * queued while its own test runs still applies, because no reset runs between the two reads.
     * {@code resets} is the count the give captured when it was queued and {@code current} the
     * count read where the give would land.
     *
     * <p>The decision lives here rather than in the loader's queue lambda so it is a pure function
     * of the two counts: the unit tests enter both arms, whereas a loader's game run reaches the
     * drop only when the give-queue tests happen to run in one order.
     */
    public static void applyQueuedGiveIfCurrent(long resets, long current, Runnable apply) {
        if (resets != current) return;
        apply.run();
    }

    /**
     * The item this loader lists under {@code itemName}, or null when it lists none. The scan
     * compares names, not objects, because the name is the whole of what a client can send: the
     * item object lives in one process and the server's copy is its own.
     */
    static Object itemNamed(ModLoaderAdapter adapter, String itemName) {
        int count = adapter.itemCount();
        for (int index = 0; index < count; index++) {
            Object candidate = adapter.itemByIndex(index);
            if (candidate != null && itemName.equals(adapter.itemName(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Reports a give that was dropped, so the test that clicked for an item fails rather than
     * passing with an empty hand. This drain runs between two calls of the test that sent the give
     * and can see no call to fail, so it reports the bounded defect that fails that test, the way
     * the joined client's half does when its loader cannot name the item: the give did not happen,
     * and the test's own process cannot tell.
     */
    private static void report(String itemName, int count, String reason) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "A give of "
                        + count
                        + " of "
                        + itemName
                        + " that the run's client named was dropped: "
                        + reason
                        + ".");
    }
}
