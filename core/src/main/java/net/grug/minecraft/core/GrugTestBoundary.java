package net.grug.minecraft.core;

/**
 * Whether the client has caught up with the clearing the room build made for the next test.
 *
 * <p>On 1.20.6 the room build clears the test area on the integrated server, and the client learns
 * of that clearing from block update packets it processes a tick or more later. Until it has, its
 * chunks still hold the block entities of the test before, and a rebuild of one of their sections
 * draws them on a chunk worker thread. A draw there enters the VM in the middle of whichever call
 * the new test has suspended on the client thread, and an err it reports stops that call: the
 * engine's error flag is not per call. That is the residual window of #583, still open after #650
 * and tracked by #836.
 *
 * <p>The clear cannot be found by looking at the client's chunks, because the block entities of the
 * test before can still be in flight when the look happens: the room build runs a tick after that
 * test placed them, and their packets were sent at the end of that tick, so a section can look
 * clean and gain the entities a moment later. What proves the client has caught up is the order of
 * the packets on its connection. The blocks of the test before were sent before the clearing, and
 * the clearing is sent at the end of the first server tick after the build. A ping sent after that
 * tick is therefore queued behind the clearing, and the client answers it only after it has
 * processed everything in front of it, so a pong that comes back proves the client holds the
 * cleared blocks. The removed entities answer {@code isRemoved()}, so no later rebuild can draw
 * them, whether its compile started before or after the clearing.
 *
 * <p>Both answers are plain reads the loader makes, so the rule is a pure function and {@code
 * GrugTestBoundaryTest} covers every combination rather than leaving it to a packet landing at the
 * right moment.
 */
public final class GrugTestBoundary {
    private GrugTestBoundary() {}

    /**
     * Whether the runner still has to hold the next test back.
     *
     * @param clearingBroadcast whether a server tick has passed since the room build, so the
     *     clearing packets have been sent
     * @param clearingAcknowledged whether the client has answered a ping sent after that tick
     * @return whether the client may not hold the cleared blocks yet
     */
    public static boolean clientCatchingUp(
            boolean clearingBroadcast, boolean clearingAcknowledged) {
        return !clearingBroadcast || !clearingAcknowledged;
    }
}
