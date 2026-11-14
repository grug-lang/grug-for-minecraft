package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;

/**
 * The operator host functions' path from a script to the player, in one place, so the process that
 * runs the script and the process that holds the player are told apart before a loader is asked
 * (#1331).
 *
 * <p>Operator status belongs to the server: the operator list is read and written where the player
 * is. A process that runs the script and holds the player (singleplayer, or a dedicated server's
 * own test) answers through its adapter. A client that started a run on a server it joined is the
 * case that differs, because the player the test asserts on is the server's copy and the client has
 * no way to read or change the list there, so a call from that client is reported as the error of
 * the test that made it rather than answered from the client's own, unrelated state. A silent
 * "false" or a dropped grant would let a test pass or fail for a reason it never named.
 */
public final class GrugOperatorCalls {
    @net.grug.minecraft.grug.GrugGenerated("utility class: never instantiated")
    private GrugOperatorCalls() {}

    /** Whether the test player is an operator, or false after reporting why it cannot be known. */
    public static boolean isOperator(ModLoaderAdapter adapter, String function) {
        Object player = player(adapter, function);
        return player != null && adapter.isOperator(player);
    }

    /** Makes the test player an operator, or removes that status, or reports why it cannot. */
    public static void setOperator(ModLoaderAdapter adapter, boolean operator, String function) {
        Object player = player(adapter, function);
        if (player == null) {
            return;
        }
        if (operator) {
            adapter.makeOperator(player);
        } else {
            adapter.removeOperator(player);
        }
    }

    /** The test player, or null after reporting that none can be asked. */
    private static Object player(ModLoaderAdapter adapter, String function) {
        if (GrugControlClient.runActiveHere()) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    function
                            + ": A client joined to a server cannot read or change who is an"
                            + " operator, because the server holds the list. Run the test on the"
                            + " server, where it can.");
            return null;
        }
        Object player = adapter.testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, function + ": There is no player to ask about yet.");
        }
        return player;
    }
}
