package net.grug.minecraft.core;

/**
 * The box of blocks a test placed since a joined client last answered a settle ask settled, and the
 * checksum of the block ids the server holds in it (#1664).
 *
 * <p>A joined client's world is its own copy: the server's {@code place_block} reaches it only in a
 * packet the client reads on a later tick, so two idle frames after a revision change do not show
 * that the client holds the blocks the test then asks it about (a selection box reads the
 * neighbours of a block, which are the client's). The settle ask therefore carries the box and the
 * checksum, and the client answers false until the same checksum of its own copy matches. A
 * checksum of the box rather than a list of the placements keeps the ask inside the wire's name
 * limit however many blocks a room placed.
 *
 * <p>The decisions are pure functions so that {@code GrugBlockRegionTest} covers every combination.
 */
public final class GrugBlockRegion {
    /** Reads the block id a level holds at a position. */
    @FunctionalInterface
    public interface Ids {
        int idAt(int x, int y, int z);
    }

    /** An axis-aligned box, both corners included, with its server checksum. */
    public static final class Expected {
        public final int[] box;
        public final long checksum;

        Expected(int[] box, long checksum) {
            this.box = box;
            this.checksum = checksum;
        }
    }

    private int[] box;

    /** Records a block the test placed. */
    public synchronized void note(int x, int y, int z) {
        if (box == null) {
            box = new int[] {x, y, z, x, y, z};
            return;
        }
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.min(box[2], z);
        box[3] = Math.max(box[3], x);
        box[4] = Math.max(box[4], y);
        box[5] = Math.max(box[5], z);
    }

    /**
     * What a settle ask carries: the placed box and the server's checksum of it, as {@link
     * #encode}, or the empty text when nothing was placed since the last settled answer.
     */
    public synchronized String ask(Ids server) {
        return box == null ? "" : encode(box, checksum(box, server));
    }

    /** The client answered settled, so what was placed is held and the next ask carries none. */
    public synchronized void answeredSettled() {
        box = null;
    }

    /** The ask's text: the box's six numbers and the checksum in hex, comma separated. */
    public static String encode(int[] box, long checksum) {
        return box[0]
                + ","
                + box[1]
                + ","
                + box[2]
                + ","
                + box[3]
                + ","
                + box[4]
                + ","
                + box[5]
                + ","
                + Long.toHexString(checksum);
    }

    /**
     * Reads an ask's text, or null for the empty text. A text that is not {@link #encode}'s output
     * is a bug in the sender, so it throws instead of being treated as no expectation.
     */
    public static Expected decode(String text) {
        if (text.isEmpty()) {
            return null;
        }
        String[] parts = text.split(",", -1);
        if (parts.length != 7) {
            throw new IllegalArgumentException("a block region ask has 7 fields: " + text);
        }
        int[] box = new int[6];
        for (int i = 0; i < 6; i++) {
            box[i] = Integer.parseInt(parts[i]);
        }
        return new Expected(box, Long.parseUnsignedLong(parts[6], 16));
    }

    /**
     * The checksum of the ids a level holds in the box, in x, y, z order. A position the level
     * cannot answer (an unloaded chunk) reads as id 0, so a client missing the chunk differs from a
     * server holding a block there, and agrees only where the server holds air too.
     */
    public static long checksum(int[] box, Ids level) {
        long hash = 0xcbf29ce484222325L;
        for (int x = box[0]; x <= box[3]; x++) {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    hash = (hash ^ level.idAt(x, y, z)) * 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    /** Whether the client's copy of the box has the checksum the server sent. */
    public static boolean holds(Expected expected, Ids client) {
        return expected == null || checksum(expected.box, client) == expected.checksum;
    }
}
