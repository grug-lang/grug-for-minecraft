package net.grug.minecraft.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The block light a test's room rebuild changed, and whether the client holds it yet.
 *
 * <p>Building the room clears the test area and puts the room back, which changes the block light
 * of every section it touches. On 1.20.6 the light is computed by the integrated server and the
 * client learns of it from a packet it reads on a later frame, so a settle answer built from the
 * client's queues alone can describe a client that has not been told about the rebuild yet: the
 * client's light still holds what the world was before it, and a capture that follows shows the
 * room meshed from that older light. The sections the build touched are therefore owed to the
 * client, and the world is not settled again until the client's block light for every one of them
 * is the simulated world's. See #686.
 *
 * <p>What the client owes is the simulated world's light as it is now, not the light the rebuild
 * asked for: the light engine's answer for a section is the truth, and a second change can land on
 * top of the first. So the expected side is read from the world at the moment of the question, the
 * way {@link GrugBlockSync} reads the block it is owed.
 *
 * <p>The comparison is also what catches a light packet that is in flight. Everything else in the
 * settle answer describes a queue, and a packet the client has not read is in no queue on either
 * side; a section whose light the world has and the client has not compares unequal until the
 * client has applied it, whatever the queues say.
 */
public final class GrugLightSync {
    /** One section's block light as 2048 bytes, or null when the level has none stored. */
    @FunctionalInterface
    public interface SectionLight {
        byte[] layerAt(int sectionX, int sectionY, int sectionZ);
    }

    /**
     * The sections a rebuild left light in, packed one per entry. A set rather than a range because
     * a loader with no separate copy of the world never owes anything, and a handful of sections
     * compared per tick is cheaper than the bookkeeping that would skip an unchanged rebuild.
     */
    private final Set<Long> owed = new LinkedHashSet<>();

    /**
     * Records that the client has to hold the world's block light for every section the box covers
     * before the world is settled again.
     *
     * <p>Called on the thread that builds the room: the server thread on 1.20.6, and the client's
     * own on the loaders that share one world. The box is the one the light can change in, which is
     * the build's own box plus the block around it that the torches' light reaches.
     */
    public synchronized void oweBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int sectionY = minY >> 4; sectionY <= maxY >> 4; sectionY++) {
            for (int sectionZ = minZ >> 4; sectionZ <= maxZ >> 4; sectionZ++) {
                for (int sectionX = minX >> 4; sectionX <= maxX >> 4; sectionX++) {
                    owed.add(pack(sectionX, sectionY, sectionZ));
                }
            }
        }
    }

    /**
     * The one client copy a run has, owed by the room build and read by the settle answer. Static
     * because exactly one client runs per process, the same way the loader that owns the settle
     * answer owns its single set of counted frames.
     */
    private static final GrugLightSync ROOM = new GrugLightSync();

    /**
     * Records that the running client owes the room build at this box its light. See {@link
     * #oweBox}.
     */
    public static void oweRoom(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        ROOM.oweBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Whether the running client holds the world's light for the room build's sections. */
    public static boolean roomSettled(SectionLight simulated, SectionLight client) {
        return ROOM.settled(simulated, client);
    }

    /** How many sections the client is still owed, for a diagnostic line. */
    public static int pendingRoomSections() {
        return ROOM.pendingSections();
    }

    /** How many sections the client is still owed, for a diagnostic line. */
    public synchronized int pendingSections() {
        return owed.size();
    }

    /**
     * Whether the client holds the world's block light for every owed section, forgetting the debt
     * once it does.
     *
     * <p>Called on the server thread while it is free to tick, which is the test's own settle wait.
     * The world's side is read from the simulated world and the client's from the client's copy,
     * which the client thread writes. A read that races an update can therefore report the light
     * from before it, which makes this answer wait another tick; what is compared is the bytes each
     * side holds, so a section the client has not been sent yet compares unequal rather than
     * passing early. A section either side has no layer for is one at block light 0, which is why a
     * missing layer and an all-zero one are the same light.
     *
     * <p>Forgetting the debt is what makes the answer cheap once it has passed: the sections are
     * only compared while a rebuild's light is still arriving.
     */
    public synchronized boolean settled(SectionLight simulated, SectionLight client) {
        if (owed.isEmpty()) {
            return true;
        }
        long[] sections = new long[owed.size()];
        int at = 0;
        for (Long section : owed) {
            sections[at++] = section;
        }
        for (long section : sections) {
            int sectionX = unpackX(section);
            int sectionY = unpackY(section);
            int sectionZ = unpackZ(section);
            if (!lightsMatch(
                    simulated.layerAt(sectionX, sectionY, sectionZ),
                    client.layerAt(sectionX, sectionY, sectionZ))) {
                return false;
            }
        }
        owed.clear();
        return true;
    }

    /**
     * Whether two sections hold the same block light, where a missing layer is the zero light of a
     * section nothing has lit.
     *
     * <p>Pure, so that its unit test covers every combination of the four answers a loader can give
     * (both missing, one missing, an all-zero layer, a lit one) instead of each loader carrying a
     * null check whose arm only a section the light engine never wrote meets. See #686.
     */
    public static boolean lightsMatch(byte[] simulated, byte[] client) {
        if (simulated == null || client == null) {
            return allZero(simulated) && allZero(client);
        }
        return Arrays.equals(simulated, client);
    }

    /**
     * Whether a client's copy of a section holds the world's light, from the world's light sampled
     * either side of the client's answer, with a check that the light engine has no work left
     * before and after them.
     *
     * <p>A single sample is not enough, and taking it before the settled check is wrong: on 1.20.6
     * the light is computed on a thread of its own, so a batch the engine had already scheduled can
     * publish between two reads of the query. The wait that sampled the world before asking the
     * engine answered true when that happened: its sample held the light from before the batch, the
     * check after it reported the batch finished, and the client, which learns of the batch from a
     * packet, still held those same older bytes. That is the capture the wait exists to prevent
     * (#1248). The settled check therefore comes first, so a batch already scheduled when the wait
     * began answers false rather than being sampled.
     *
     * <p>The two samples cover the batch that was scheduled when the first was taken and published
     * between them: they differ, and the answer waits another tick instead of comparing either.
     * They do not cover a change the engine takes on after the check, which is one that arrived
     * during this wait rather than light the world already owed: one that publishes before the
     * second sample makes the samples differ, and one that publishes after it is in neither. The
     * engine is asked once more after both samples, so a batch still pending then is not read as
     * idle either.
     *
     * <p>A client answer of -1 means the client cannot verify its copy, which is read as applied
     * rather than as a mismatch, the same way {@code GrugClientLight.Forwarded} reads it: a client
     * whose light engine cannot reproduce the world's would otherwise wait until its deadline over
     * a difference that is not its fault (#1240). A world with no level to sample holds no section,
     * so -1 there still answers false.
     *
     * <p>Pure, and unit-tested in every combination, because the moment the light thread publishes
     * a batch is not one a test can force: a game test can place a block and wait, but it cannot
     * make the batch land between two reads of the same tick.
     *
     * @param engineSettled whether the engine had no work left after both samples
     * @param before the world's light sampled after the first settled check
     * @param after the world's light sampled after the client's answer
     * @param client the client's copy of the same section, or -1 where it cannot verify it
     */
    public static boolean lightApplied(
            boolean engineSettled, long before, long after, long client) {
        return engineSettled && before >= 0 && before == after && (client < 0 || before == client);
    }

    /**
     * A fingerprint of one section's block light, the same for two sections that hold the same
     * light, with a missing layer and an all-zero one both giving 0.
     *
     * <p>This is {@link #lightsMatch} for a copy that is not in this process: a run on a dedicated
     * server compares its own section with the one its joined client answers, and 2048 bytes do not
     * fit a control message's number. The fingerprint is 53 bits, which a double carries exactly,
     * and two sections of a room differ in many bytes, so a stale copy does not collide with the
     * copy the world holds. A caller that wants whole bytes compares them with {@link #lightsMatch}
     * instead.
     */
    public static long lightFingerprint(byte[] layer) {
        if (allZero(layer)) {
            return 0;
        }
        // FNV-1a, whose 64-bit form is simple enough to read and whose low 53 bits vary with every
        // byte, unlike a sum or a polynomial that a uniform light step can leave unchanged.
        long hash = 0xcbf29ce484222325L;
        for (byte value : layer) {
            hash ^= value & 0xFF;
            hash *= 0x100000001b3L;
        }
        return hash & 0x1FFFFFFFFFFFFFL;
    }

    /** Whether a layer is missing or holds no light at all. */
    private static boolean allZero(byte[] layer) {
        if (layer == null) {
            return true;
        }
        for (byte value : layer) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /** Packs a section position, whose coordinates fit the same bits a block position does. */
    private static long pack(int sectionX, int sectionY, int sectionZ) {
        return ((long) (sectionX & 0x3FFFFFF) << 38)
                | ((long) (sectionY & 0xFFF) << 26)
                | (sectionZ & 0x3FFFFFF);
    }

    private static int unpackX(long key) {
        return (int) (key >> 38);
    }

    private static int unpackY(long key) {
        return (int) (key << 26 >> 52);
    }

    private static int unpackZ(long key) {
        return (int) (key << 38 >> 38);
    }
}
