package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

/**
 * The give host functions' path from a script to the player, in one place, so the process that runs
 * the script and the process that holds the player give the same way (#1099).
 *
 * <p>A give runs where the script's call runs. In singleplayer, and on a dedicated server's own
 * test, that process holds the player and the adapter gives there. A client joined to a server that
 * drives a run is the case that differs: the overlay's {@code click()} is the caller, and the
 * player the test asserts on is the server's copy, so the client names the item to the server by
 * its index in the loader's item list and the server gives it. A write to the client's copy would
 * be overwritten by the next inventory packet and the test would never see it (#882).
 *
 * <p>The index is the loader's own {@code item_at} order, which both processes enumerate from the
 * same game and mods, so the index names the same item on either side.
 */
public final class GrugGiveCalls {
    @net.grug.minecraft.grug.GrugGenerated("utility class: never instantiated")
    private GrugGiveCalls() {}

    /**
     * Gives {@code count} of the item to {@code player}, which is the test player of the side this
     * runs on. A client that has started a run names the item to the server instead, so the give
     * lands in the copy of the player the test reads.
     *
     * <p>{@code method} is the host function's name, which the report of an item the list cannot
     * name has to carry: it is what tells a mod which of its calls did not give.
     */
    public static void give(
            ModLoaderAdapter adapter, Object player, Object item, int count, String method) {
        if (GrugControlClient.runActiveHere()) {
            int index = indexOf(adapter, item);
            if (index < 0) {
                // A joined client can only name an item its list holds, and a give it cannot name
                // is reported rather than written to a copy the server would overwrite.
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr,
                        method
                                + ": The item is not in this loader's item list, so a client joined"
                                + " to a server cannot name it to the server that holds the"
                                + " player.");
                return;
            }
            GrugControlClient.giveToServer(index, count);
            return;
        }
        adapter.giveItemCountToPlayer(player, item, count);
    }

    /**
     * The index {@link ModLoaderAdapter#itemByIndex} answers with for {@code itemObj}, or -1 when
     * the list does not hold it.
     *
     * <p>A client joined to a server names an item to the server this way (#1099): the item object
     * lives in one process, so the client sends the index the server resolves in its own list. Both
     * processes enumerate the same game and mods, so the index names the same item there. The scan
     * compares objects and not values, because an item is the game's singleton and two items that
     * compare equal are still one entry of the list.
     */
    static int indexOf(ModLoaderAdapter adapter, Object itemObj) {
        int count = adapter.itemCount();
        for (int index = 0; index < count; index++) {
            if (adapter.itemByIndex(index) == itemObj) return index;
        }
        return -1;
    }

    /**
     * Applies a give the joined client sent, on the server's own copy of the test player (#1099).
     * Called on the server thread, by the drain that follows the forwarded call the give was part
     * of.
     */
    public static void giveOnServer(ModLoaderAdapter adapter, int index, int count) {
        Object player = adapter.testPlayer();
        Object item = adapter.itemByIndex(index);
        if (player == null || item == null) {
            System.out.println(
                    "[GRUG CONTROL] a give of "
                            + count
                            + " of item "
                            + index
                            + " was dropped: "
                            + (player == null
                                    ? "no player has joined yet"
                                    : "the item list has no such item"));
            return;
        }
        adapter.giveItemCountToPlayer(player, item, count);
    }
}
