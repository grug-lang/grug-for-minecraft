package net.grug.minecraft.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks a test placed since a joined client last answered a settle ask settled, one box per
 * chunk, and the checksum of the block ids the server holds in them (#1664).
 *
 * <p>A joined client's world is its own copy: the server's {@code place_block} reaches it only in a
 * packet the client reads on a later tick, so two idle frames after a revision change do not show
 * that the client holds the blocks the test then asks it about (a selection box reads the
 * neighbours of a block, which are the client's). The settle ask therefore carries the box and the
 * checksum, and the client answers false until the same checksum of its own copy matches. A
 * checksum rather than a list of the placements keeps the ask inside the wire's name limit however
 * many blocks a room placed. The boxes are per chunk, so two distant placements do not make the
 * server read, and perhaps generate, the chunks between them, and a chunk the server no longer
 * holds is left out of the ask so both ends hash the same positions. A client that has not received
 * a chunk of the ask answers {@link #ABSENT} for it, which no server id equals. The ask holds as
 * many boxes as the wire's name limit allows, oldest first; a test building in more chunks than
 * that has the rest unverified.
 *
 * <p>The decisions are pure functions so that {@code GrugBlockRegionTest} covers every combination.
 */
public final class GrugBlockRegion {
    /** The id a client answers for a position in a chunk it has not received. */
    public static final int ABSENT = -1;

    /** The most characters of boxes an ask carries, under the wire's {@code MAX_NAME}. */
    static final int BUDGET = 200;

    /** Reads the block id a level holds at a position, or {@link #ABSENT}. */
    @FunctionalInterface
    public interface Ids {
        int idAt(int x, int y, int z);
    }

    /** Whether the server still holds a chunk. */
    @FunctionalInterface
    public interface Loaded {
        boolean loaded(int chunkX, int chunkZ);
    }

    /** The boxes an ask names, both corners included, with the server's checksum of them. */
    public static final class Expected {
        public final List<int[]> boxes;
        public final long checksum;

        Expected(List<int[]> boxes, long checksum) {
            this.boxes = boxes;
            this.checksum = checksum;
        }
    }

    private final Map<Long, int[]> boxes = new LinkedHashMap<>();

    /** Records a block the test placed. */
    public synchronized void note(int x, int y, int z) {
        long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
        int[] box = boxes.get(chunk);
        if (box == null) {
            boxes.put(chunk, new int[] {x, y, z, x, y, z});
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
     * What a settle ask carries: the boxes of the chunks the server still holds and its checksum of
     * them, as {@link #encode}, or the empty text when there are none.
     */
    public synchronized String ask(Ids server, Loaded loaded) {
        List<int[]> sent = new ArrayList<>();
        int length = 0;
        for (Map.Entry<Long, int[]> entry : boxes.entrySet()) {
            int[] box = entry.getValue();
            if (!loaded.loaded(box[0] >> 4, box[2] >> 4)) {
                continue;
            }
            length += boxText(box).length() + 1;
            if (length > BUDGET) {
                break;
            }
            sent.add(box.clone());
        }
        return sent.isEmpty() ? "" : encode(sent, checksum(sent, server));
    }

    /** The client answered settled, so what was placed is held and the next ask carries none. */
    public synchronized void answeredSettled() {
        boxes.clear();
    }

    /** A new test or run starts, so what an earlier one placed is not owed. */
    public synchronized void reset() {
        boxes.clear();
    }

    private static String boxText(int[] box) {
        return box[0] + "," + box[1] + "," + box[2] + "," + box[3] + "," + box[4] + "," + box[5];
    }

    /** The ask's text: the boxes, each six comma separated numbers, then {@code |} and the hex. */
    public static String encode(List<int[]> boxes, long checksum) {
        StringBuilder text = new StringBuilder();
        for (int[] box : boxes) {
            text.append(boxText(box)).append(';');
        }
        return text.append('|').append(Long.toHexString(checksum)).toString();
    }

    /**
     * Reads an ask's text, or null for the empty text. A text that is not {@link #encode}'s output
     * is a bug in the sender, so it throws instead of being treated as no expectation.
     */
    public static Expected decode(String text) {
        if (text.isEmpty()) {
            return null;
        }
        int bar = text.indexOf('|');
        if (bar < 0) {
            throw new IllegalArgumentException("a block region ask has a checksum: " + text);
        }
        List<int[]> boxes = new ArrayList<>();
        for (String one : text.substring(0, bar).split(";")) {
            String[] parts = one.split(",", -1);
            if (parts.length != 6) {
                throw new IllegalArgumentException("a block region box has 6 numbers: " + one);
            }
            int[] box = new int[6];
            for (int i = 0; i < 6; i++) {
                box[i] = Integer.parseInt(parts[i]);
            }
            boxes.add(box);
        }
        return new Expected(boxes, Long.parseUnsignedLong(text.substring(bar + 1), 16));
    }

    /** The checksum of the ids a level holds in the boxes, in order, each in x, y, z order. */
    public static long checksum(List<int[]> boxes, Ids level) {
        long hash = 0xcbf29ce484222325L;
        for (int[] box : boxes) {
            for (int x = box[0]; x <= box[3]; x++) {
                for (int y = box[1]; y <= box[4]; y++) {
                    for (int z = box[2]; z <= box[5]; z++) {
                        hash = (hash ^ level.idAt(x, y, z)) * 0x100000001b3L;
                    }
                }
            }
        }
        return hash;
    }

    /** Whether the client's copy of the boxes has the checksum the server sent. */
    public static boolean holds(Expected expected, Ids client) {
        return expected == null || checksum(expected.boxes, client) == expected.checksum;
    }
}
