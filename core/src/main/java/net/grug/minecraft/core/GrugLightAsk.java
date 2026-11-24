package net.grug.minecraft.core;

/**
 * The box a dedicated server last sent its joined client the light of, and the fingerprint of the
 * field the server holds in it, carried on the settle ask so a client that lit the same blocks
 * differently says so instead of letting a capture bake its own field (#1695).
 *
 * <p>On the loaders whose client lights its own copy incrementally, a block-change packet makes the
 * client relight the blocks around that change from the light it already has, while the server's
 * field is what its own relight over the whole box computed. The region packet is the one carrier
 * of the server's field, so the settle wait sends it after the changes; a client that relit a
 * change that landed after that packet holds a field the server's engine never produced, and
 * nothing looks at the light, so the divergence is permanent and the frame a capture reads is drawn
 * from it. A client's own relight of the blocks around a stale heightmap reads sky where the
 * server's room is enclosed, which is a room drawn a shade of full brightness from one wall inward.
 *
 * <p>The ask therefore names the box and the fingerprint of both light fields in it, which is the
 * pair the frame pass bakes the room from ({@link GrugLightSync#lightFingerprint(int, int, int,
 * GrugLightSync.BlockLight, GrugLightSync.BlockLight)}). A client that holds the boxes and the
 * frames but not the light answers {@link GrugControlWire#SETTLE_LIGHT_DIVERGED}, and the server
 * sends the region again rather than settling.
 *
 * <p>The text travels in the settle ask's name slot, which {@link GrugBlockRegion} also uses, so it
 * is told apart by {@link #PREFIX}. A loader sends one or the other and never both. The box is read
 * from {@link GrugLightResend#lastBox}, whose height is whole pairs of rows, which is what a region
 * packet's light copy covers.
 *
 * <p>The decisions are pure functions so that {@code GrugLightAskTest} covers every combination.
 */
public final class GrugLightAsk {
    /** The prefix that makes a settle ask's text a light ask rather than a block region. */
    static final String PREFIX = "light:";

    /** The box an ask names, both corners included, and the server's fingerprint of its light. */
    public static final class Expected {
        public final int[] box;
        public final long fingerprint;

        Expected(int[] box, long fingerprint) {
            this.box = box;
            this.fingerprint = fingerprint;
        }
    }

    private GrugLightAsk() {}

    /** Whether a settle ask's text is one of these rather than a block region. */
    public static boolean isAsk(String text) {
        return text.startsWith(PREFIX);
    }

    /**
     * Reads an ask's text, or null when the text is not one of these, which is the empty text and
     * every block region {@link GrugBlockRegion} sends. A text that begins with {@link #PREFIX} and
     * is not {@link #encode}'s output is a bug in the sender, so it throws instead of being treated
     * as no expectation.
     */
    public static Expected decode(String text) {
        if (!isAsk(text)) {
            return null;
        }
        int bar = text.indexOf('|');
        if (bar < 0) {
            throw new IllegalArgumentException("a light ask has a fingerprint: " + text);
        }
        String[] parts = text.substring(PREFIX.length(), bar).split(",", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("a light ask's box has 6 numbers: " + text);
        }
        int[] box = new int[6];
        for (int at = 0; at < 6; at++) {
            box[at] = Integer.parseInt(parts[at]);
        }
        return new Expected(box, Long.parseUnsignedLong(text.substring(bar + 1), 16));
    }

    /** The ask's text: {@link #PREFIX}, the box as six comma separated numbers, then {@code |}. */
    public static String encode(int[] box, long fingerprint) {
        StringBuilder text = new StringBuilder(PREFIX);
        for (int at = 0; at < 6; at++) {
            if (at > 0) {
                text.append(',');
            }
            text.append(box[at]);
        }
        return text.append('|').append(Long.toHexString(fingerprint)).toString();
    }

    /**
     * The ask for a box, or the empty text when there is no box to ask about: before the first
     * region send, and after a run that never sent one.
     */
    public static String ask(
            int[] box, GrugLightSync.BlockLight block, GrugLightSync.BlockLight sky) {
        return box.length == 6 ? encode(box, fingerprint(box, block, sky)) : "";
    }

    /**
     * The fingerprint of both light fields in a box, the same for two boxes that hold the same
     * light, with a box that holds none at all giving 0.
     *
     * <p>Each position hashes its block level in the low four bits and its sky level in the next
     * four, in x, then y, then z order, which both ends of an ask read the same way. A renderer
     * that draws a position with the larger of its two fields has to compare both: a stale sky
     * field of open sky over an enclosed room fingerprints equal to the server's on the block field
     * alone while the frame is drawn at full brightness (#1695). This is {@link
     * GrugLightSync#lightFingerprint(int, int, int, GrugLightSync.BlockLight,
     * GrugLightSync.BlockLight)} over a box rather than one section, because a region packet's box
     * is what the client was sent.
     */
    public static long fingerprint(
            int[] box, GrugLightSync.BlockLight block, GrugLightSync.BlockLight sky) {
        long hash = 0xcbf29ce484222325L;
        boolean lit = false;
        for (int x = box[0]; x <= box[3]; x++) {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    int level =
                            (block.levelAt(x, y, z) & 0xF) | ((sky.levelAt(x, y, z) & 0xF) << 4);
                    if (level != 0) {
                        lit = true;
                    }
                    hash ^= level;
                    hash *= 0x100000001b3L;
                }
            }
        }
        return lit ? hash & 0x1FFFFFFFFFFFFFL : 0;
    }

    /** Whether the client's copy of the box holds the light the server's fingerprint describes. */
    public static boolean holds(
            Expected expected, GrugLightSync.BlockLight block, GrugLightSync.BlockLight sky) {
        return expected == null || fingerprint(expected.box, block, sky) == expected.fingerprint;
    }

    /**
     * What a loader makes of the client's settle answer: whether it settled, and whether the box
     * has to be sent again because the client's own light is not this server's.
     *
     * <p>The divergent answer is not settled, and it runs {@code rearm} so the next poll sends the
     * region again ({@link GrugLightResend#rearm}). That is how a client whose relight landed after
     * the first region ends up holding this server's field rather than its own (#1695). The poll
     * after the re-armed send asks again, and the answer is settled once the copied bytes are what
     * the comparison reads.
     */
    public static boolean settled(byte answer, Runnable rearm) {
        if (answer == GrugControlWire.SETTLE_LIGHT_DIVERGED) {
            rearm.run();
            return false;
        }
        return answer == GrugControlWire.SETTLE_YES;
    }
}
