package net.grug.minecraft.core;

/**
 * What a loader makes of the joined client's answer to the settle ask's light, and the report that
 * names the first cell that differs when the answers keep saying the client's field is not this
 * server's (#1695).
 *
 * <p>A client relights a block change it receives after the region packet landed, so one divergent
 * answer is a race the loader fixes by sending the box again ({@link GrugLightResend#rearm}). A
 * second divergent answer after that send is a client whose copy holds a field this server's engine
 * never produced, and no further send replaces it: the run reports the cell and both levels instead
 * of asking until its tick budget is gone. A divergent answer stands until a settled one, because a
 * client that goes back to drawing still holds the field it relit.
 *
 * <p>The report asks the client the same settle question over smaller boxes, so it needs no wire
 * message of its own: {@link GrugControlServer#probeLight} asks without recording the answer as the
 * world's. The decisions and the box arithmetic are pure, so {@code GrugLightRetryTest} covers them
 * without a game.
 */
public final class GrugLightRetry {
    /** What the loader does with one answer. */
    public enum Verdict {
        /** The client holds this server's boxes, frames and light. */
        SETTLED,
        /** The client is drawing or has not received the blocks yet, so the wait asks again. */
        UNSETTLED,
        /** The client drew the world but relit the box differently, so the box is sent again. */
        DIVERGED,
        /** A divergence that survived the box being sent again, so the run reports the cell. */
        PERSISTED
    }

    /** A settle ask whose answer locates a differing cell, as a loader's server makes it. */
    public interface Probe {
        byte probe(String ask) throws GrugControlServer.ControlException;
    }

    /** Whether one box's light differs, for the splits {@link #firstDifferingCell} makes. */
    interface Differs {
        boolean differs(int[] box) throws GrugControlServer.ControlException;
    }

    private boolean diverged;

    /**
     * What the loader does with {@code answer}, as {@link GrugControlServer#settle} returns it.
     *
     * <p>{@link Verdict#PERSISTED} repeats for every further divergent answer, so a caller that
     * reports the cell once keeps reporting it rather than falling back to sending the box again.
     */
    public Verdict answered(byte answer) {
        if (answer == GrugControlWire.SETTLE_LIGHT_DIVERGED) {
            boolean again = diverged;
            diverged = true;
            return again ? Verdict.PERSISTED : Verdict.DIVERGED;
        }
        if (answer == GrugControlWire.SETTLE_YES) {
            diverged = false;
            return Verdict.SETTLED;
        }
        return Verdict.UNSETTLED;
    }

    /**
     * The first cell of {@code box}, in x then y then z order, whose light on the client is not
     * this server's, with both fields' levels on each side.
     *
     * <p>{@code probe} asks the client whether it holds the light of a box this server describes,
     * which is the question the settle wait asks over the whole box. The report bisects the box one
     * axis at a time, then asks which level byte the cell holds, its own levels first and then the
     * ones that differ in a single field: a client's field is usually off in the field it relit, so
     * the usual report costs a few asks rather than 256. A run that reaches this is already
     * failing, so the worst case is the price of naming the cell rather than asking until the
     * budget ends.
     */
    public static String difference(
            int[] box, GrugLightSync.BlockLight block, GrugLightSync.BlockLight sky, Probe probe)
            throws GrugControlServer.ControlException {
        if (box.length != 6) {
            return "the joined client's light differs from this server's, and no box was sent for"
                    + " it to differ over";
        }
        int[] cell = firstDifferingCell(box, part -> differs(part, block, sky, probe));
        if (cell == null) {
            return "the joined client says the light of "
                    + describe(box)
                    + " differs from this server's, and no cell of it was found to differ";
        }
        int serverBlock = block.levelAt(cell[0], cell[1], cell[2]) & 0xF;
        int serverSky = sky.levelAt(cell[0], cell[1], cell[2]) & 0xF;
        int client = clientLevel(cell, serverBlock | (serverSky << 4), probe);
        String levels =
                client < 0
                        ? "the client's level there could not be read"
                        : "sky "
                                + serverSky
                                + " on this server and "
                                + (client >> 4)
                                + " on the client, block "
                                + serverBlock
                                + " on this server and "
                                + (client & 0xF)
                                + " on the client";
        return "the joined client's light still differs from this server's after the box was sent"
                + " again, first at "
                + cell[0]
                + ","
                + cell[1]
                + ","
                + cell[2]
                + ": "
                + levels;
    }

    /**
     * The first cell of {@code box}, in x then y then z order, that {@code differs} says differs,
     * or null when no cell of it does.
     *
     * <p>Each axis is bisected while the other two keep the range the earlier axes narrowed to, so
     * the answer is the cell the fingerprint's own x, y, z order reaches first.
     */
    static int[] firstDifferingCell(int[] box, Differs differs)
            throws GrugControlServer.ControlException {
        int[] cell = box.clone();
        for (int axis = 0; axis < 3; axis++) {
            int low = cell[axis];
            int high = cell[axis + 3];
            while (low < high) {
                int middle = low + (high - low) / 2;
                int[] left = cell.clone();
                left[axis + 3] = middle;
                if (differs.differs(left)) {
                    high = middle;
                } else {
                    low = middle + 1;
                }
            }
            cell[axis] = low;
            cell[axis + 3] = low;
        }
        return differs.differs(cell) ? new int[] {cell[0], cell[1], cell[2]} : null;
    }

    /**
     * Every level byte, the server's own first and then the ones that differ in one field, so a
     * client that got a single field wrong is found in at most sixteen asks.
     */
    static int[] candidates(int serverLevel) {
        int[] order = new int[256];
        boolean[] seen = new boolean[256];
        int at = candidate(order, seen, 0, serverLevel);
        for (int level = 0; level < 16; level++) {
            at = candidate(order, seen, at, (serverLevel & 0xF0) | level);
        }
        for (int level = 0; level < 16; level++) {
            at = candidate(order, seen, at, (serverLevel & 0x0F) | (level << 4));
        }
        for (int level = 0; level < 256; level++) {
            at = candidate(order, seen, at, level);
        }
        return order;
    }

    private static int candidate(int[] order, boolean[] seen, int at, int level) {
        if (seen[level]) {
            return at;
        }
        seen[level] = true;
        order[at] = level;
        return at + 1;
    }

    /** Whether the client holds this server's light over {@code box}, as one probe answers it. */
    private static boolean differs(
            int[] box, GrugLightSync.BlockLight block, GrugLightSync.BlockLight sky, Probe probe)
            throws GrugControlServer.ControlException {
        byte answer = probe.probe(GrugLightAsk.ask(box, block, sky));
        if (answer == GrugControlWire.SETTLE_NO) {
            throw new GrugControlServer.ControlException(
                    "the joined client stopped drawing while the differing light cell was being"
                            + " located");
        }
        return answer == GrugControlWire.SETTLE_LIGHT_DIVERGED;
    }

    /** The level byte the client holds at {@code cell}, or -1 when none of the asks matched. */
    private static int clientLevel(int[] cell, int serverLevel, Probe probe)
            throws GrugControlServer.ControlException {
        int[] single = {cell[0], cell[1], cell[2], cell[0], cell[1], cell[2]};
        for (int level : candidates(serverLevel)) {
            String ask =
                    GrugLightAsk.ask(
                            single, (x, y, z) -> level & 0xF, (x, y, z) -> (level >> 4) & 0xF);
            if (probe.probe(ask) == GrugControlWire.SETTLE_YES) {
                return level;
            }
        }
        return -1;
    }

    /** The box as the two corners it spans, both included. */
    private static String describe(int[] box) {
        return box[0] + ".." + box[3] + ", " + box[1] + ".." + box[4] + ", " + box[2] + ".."
                + box[5];
    }
}
