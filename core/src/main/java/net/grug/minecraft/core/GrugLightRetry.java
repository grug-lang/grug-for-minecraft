package net.grug.minecraft.core;

/**
 * What a loader makes of the joined client's answer to the settle ask's light, and the report that
 * names the first cell that differs when the answers keep saying the client's field is not this
 * server's (#1695).
 *
 * <p>A client relights a block change it receives after the region packet landed, so a divergent
 * answer is not on its own a defect: the loader sends the box again ({@link GrugLightResend#rearm})
 * and asks once more. A field that keeps differing while it moves is a client still catching up, so
 * a re-send is given until the first differing cell stops moving or {@link #MAX_RESENDS} sends are
 * spent; a field that holds the same cell with the same bytes after a re-send is one this server's
 * engine never produced, and no further send replaces it. The run then reports the cell and both
 * levels instead of asking until its tick budget is gone. A divergent answer stands until a settled
 * one, because a client that goes back to drawing still holds the field it relit.
 *
 * <p>The report asks the client the same settle question over smaller boxes, so it needs no wire
 * message of its own: {@link GrugControlServer#probeLight} asks without recording the answer as the
 * world's. The client's level at the cell comes from one {@link SectionRead} call where the loader
 * has one: the client's section fingerprint is a hash of both fields, so substituting each byte at
 * the cell into this server's section and comparing names the level without a second ask. A client
 * that cannot give a fingerprint falls back to a bounded list of single-field variants rather than
 * walking 256 levels, because every ask freezes the world for the client's whole answer time. The
 * decisions and the box arithmetic are pure, so {@code GrugLightRetryTest} covers them without a
 * game.
 */
public final class GrugLightRetry {
    /**
     * How many times a divergence may send the box again before the run reports it. Above the one
     * re-send a client that relit a late change needs, and small enough that a failing wait costs a
     * few reports rather than a tick budget.
     */
    static final int MAX_RESENDS = 3;

    /**
     * How many level bytes a report tries when the client cannot give a section fingerprint. The
     * server's own byte and every one-field variant come first, which is the shape of a client that
     * got one field wrong; past that the level is reported as unread rather than spending a minute
     * of frozen world on 256 asks.
     */
    static final int CANDIDATE_LIMIT = 48;

    /** What the loader does with one answer. */
    public enum Verdict {
        /** The client holds this server's boxes, frames and light. */
        SETTLED,
        /** The client is drawing or has not received the blocks yet, so the wait asks again. */
        UNSETTLED,
        /** The client drew the world but relit the box differently, so the box is sent again. */
        DIVERGED,
        /** A divergence that stopped moving or outlasted the re-sends, so the run reports it. */
        PERSISTED
    }

    /** A settle ask whose answer locates a differing cell, as a loader's server makes it. */
    public interface Probe {
        byte probe(String ask) throws GrugControlServer.ControlException;
    }

    /** The client's fingerprint of the light of the section a position falls in. */
    @FunctionalInterface
    public interface SectionRead {
        long fingerprintAt(int x, int y, int z) throws GrugControlServer.ControlException;
    }

    /** Whether one box's light differs, for the splits {@link #firstDifferingCell} makes. */
    interface Differs {
        boolean differs(int[] box) throws GrugControlServer.ControlException;
    }

    /** One cell whose light the client holds differently, with both levels on each side. */
    public static final class Difference {
        private final int x;
        private final int y;
        private final int z;
        private final int serverBlock;
        private final int serverSky;
        private final int client;

        Difference(int x, int y, int z, int serverBlock, int serverSky, int client) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.serverBlock = serverBlock;
            this.serverSky = serverSky;
            this.client = client;
        }

        /**
         * Whether the client holds the same bytes at the same cell as {@code other}, which is a
         * field that stopped moving between two re-sends.
         */
        boolean sameAs(Difference other) {
            return x == other.x
                    && y == other.y
                    && z == other.z
                    && serverBlock == other.serverBlock
                    && serverSky == other.serverSky
                    && client == other.client;
        }

        /** The report: the cell, and both fields' levels on each side. */
        public String describe() {
            String levels =
                    client < 0
                            ? "sky "
                                    + serverSky
                                    + " and block "
                                    + serverBlock
                                    + " on this server, and the client's level there could not"
                                    + " be read"
                            : "sky "
                                    + serverSky
                                    + " on this server and "
                                    + (client >> 4)
                                    + " on the client, block "
                                    + serverBlock
                                    + " on this server and "
                                    + (client & 0xF)
                                    + " on the client";
            return "the joined client's light still differs from this server's after the box was"
                    + " sent again, first at "
                    + x
                    + ","
                    + y
                    + ","
                    + z
                    + ": "
                    + levels;
        }
    }

    private boolean diverged;
    private int resends;
    private Difference last;

    /**
     * What the loader does with {@code answer}, as {@link GrugControlServer#settle} returns it.
     *
     * <p>The first divergence only sends the box again. Every later one locates the first differing
     * cell: a cell and bytes the previous re-send did not move are a field that stopped moving, so
     * it reports; a field that still moves gets another send until {@link #MAX_RESENDS} are spent,
     * and the last located difference is the report.
     */
    public Verdict answered(
            byte answer,
            int[] box,
            GrugLightSync.BlockLight block,
            GrugLightSync.BlockLight sky,
            Probe probe,
            SectionRead section)
            throws GrugControlServer.ControlException {
        if (answer == GrugControlWire.SETTLE_LIGHT_DIVERGED) {
            if (!diverged) {
                diverged = true;
                resends = 1;
                last = null;
                return Verdict.DIVERGED;
            }
            Difference now = firstDifference(box, block, sky, probe, section);
            if (resends >= MAX_RESENDS) {
                last = now;
                return Verdict.PERSISTED;
            }
            if (last != null && now != null && now.sameAs(last)) {
                last = now;
                return Verdict.PERSISTED;
            }
            last = now;
            resends++;
            return Verdict.DIVERGED;
        }
        if (answer == GrugControlWire.SETTLE_YES) {
            diverged = false;
            resends = 0;
            last = null;
            return Verdict.SETTLED;
        }
        return Verdict.UNSETTLED;
    }

    /**
     * Forgets a divergence, for the next test's own changes.
     *
     * <p>A wait that saw one divergent answer and ended before it asked again would otherwise leave
     * the next test's first divergence to be reported as one that survived a re-send, though this
     * server never sent that test's box again. The box is forgotten with it ({@link
     * GrugLightResend#forget}).
     */
    public void forget() {
        diverged = false;
        resends = 0;
        last = null;
    }

    /** The report for the last difference located, or the box that could not be asked about. */
    public String report() {
        return last == null
                ? "the joined client's light differs from this server's, and the cell that differs"
                        + " could not be located"
                : last.describe();
    }

    /**
     * The first cell of {@code box}, in x then y then z order, whose light on the client is not
     * this server's, with both fields' levels on each side, or null when no cell of it differs.
     *
     * <p>{@code probe} asks the client whether it holds the light of a box this server describes,
     * which is the question the settle wait asks over the whole box. The report bisects the box one
     * axis at a time, then reads the client's level byte at the cell: from one section fingerprint
     * where the loader has one, else from the bounded candidate list.
     */
    public static Difference firstDifference(
            int[] box,
            GrugLightSync.BlockLight block,
            GrugLightSync.BlockLight sky,
            Probe probe,
            SectionRead section)
            throws GrugControlServer.ControlException {
        if (box.length != 6) {
            return null;
        }
        int[] cell = firstDifferingCell(box, part -> differs(part, block, sky, probe));
        if (cell == null) {
            return null;
        }
        int serverBlock = block.levelAt(cell[0], cell[1], cell[2]) & 0xF;
        int serverSky = sky.levelAt(cell[0], cell[1], cell[2]) & 0xF;
        int client = clientLevel(cell, serverBlock | (serverSky << 4), block, sky, section, probe);
        return new Difference(cell[0], cell[1], cell[2], serverBlock, serverSky, client);
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
     * client that got a single field wrong is found in at most thirty-two asks: the same block
     * level with each sky level, then the same sky level with each block level.
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

    /**
     * The level byte the client holds at {@code cell}, or -1 when it could not be read.
     *
     * <p>One section fingerprint is enough when the loader can ask for it: the client's section
     * hash covers the cell, so this server's own section with each of the 256 bytes substituted at
     * the cell is compared with it, and the byte that reproduces the hash is the client's level.
     * That turns the read into one call and 256 local folds instead of up to 256 asks, each of
     * which freezes the world for the client's answer time. A client that answers no fingerprint
     * ({@link GrugClientLight#CANNOT_VERIFY} or {@link GrugClientLight#NOT_RECEIVED}) falls back to
     * the single-field candidates, bounded so a report cannot spend a minute asking.
     */
    private static int clientLevel(
            int[] cell,
            int serverLevel,
            GrugLightSync.BlockLight block,
            GrugLightSync.BlockLight sky,
            SectionRead section,
            Probe probe)
            throws GrugControlServer.ControlException {
        if (section != null) {
            long client = section.fingerprintAt(cell[0], cell[1], cell[2]);
            if (client >= 0) {
                int found = substitutedLevel(cell, block, sky, client);
                if (found >= 0) {
                    return found;
                }
            }
        }
        int[] single = {cell[0], cell[1], cell[2], cell[0], cell[1], cell[2]};
        int[] order = candidates(serverLevel);
        for (int at = 0; at < CANDIDATE_LIMIT; at++) {
            int level = order[at];
            String ask =
                    GrugLightAsk.ask(
                            single, (x, y, z) -> level & 0xF, (x, y, z) -> (level >> 4) & 0xF);
            if (probe.probe(ask) == GrugControlWire.SETTLE_YES) {
                return level;
            }
        }
        return -1;
    }

    /**
     * The byte this server's section must hold at {@code cell} for its fingerprint to be {@code
     * client}'s, or -1 when no single byte reproduces it (the client's section differs in another
     * cell too).
     */
    private static int substitutedLevel(
            int[] cell, GrugLightSync.BlockLight block, GrugLightSync.BlockLight sky, long client) {
        int sectionX = cell[0] >> 4;
        int sectionY = cell[1] >> 4;
        int sectionZ = cell[2] >> 4;
        for (int level = 0; level < 256; level++) {
            int blockLevel = level & 0xF;
            int skyLevel = (level >> 4) & 0xF;
            long hash =
                    GrugLightSync.lightFingerprint(
                            sectionX,
                            sectionY,
                            sectionZ,
                            (x, y, z) ->
                                    x == cell[0] && y == cell[1] && z == cell[2]
                                            ? blockLevel
                                            : block.levelAt(x, y, z),
                            (x, y, z) ->
                                    x == cell[0] && y == cell[1] && z == cell[2]
                                            ? skyLevel
                                            : sky.levelAt(x, y, z));
            if (hash == client) {
                return level;
            }
        }
        return -1;
    }
}
