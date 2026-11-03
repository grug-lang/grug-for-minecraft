package net.grug.minecraft.forge125.control;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.NetHandler;

/**
 * What the test-control packet (#1040) hands its message to: the dedicated server's half or the
 * client's half, whichever this process installed. The packet class is shared by both ends, so it
 * names neither half.
 */
@GrugGenerated(
        "the 1.2.5 channel glue of #1040: no test the coverage gate counts can reach it, since only"
                + " the multiplayer job runs it")
public interface GrugControlRouter {
    /**
     * Called on the connection's reader thread with a message just read. Answers true when the
     * message was handled there and the game thread must not handle it.
     */
    boolean onRead(Thread reader, byte[] bytes);

    /** Called on the game thread for every message {@link #onRead} left. */
    void onHandle(NetHandler handler, byte[] bytes);
}
