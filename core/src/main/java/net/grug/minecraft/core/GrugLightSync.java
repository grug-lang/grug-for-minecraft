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

    /**
     * How many chunks around the player's own the client is certain to hold, which is the render
     * distance a run shrinks the view to. Anything further may never be sent. See #948.
     */
    public static final int CLIENT_CHUNK_RADIUS = 2;

    /**
     * The lowest block coordinate on an axis that the client holds, at least {@code wanted}, for a
     * player standing at {@code playerBlock}. Pure, so a unit test covers every case.
     */
    public static int clientHeldMin(int playerBlock, int wanted) {
        return Math.max(wanted, ((playerBlock >> 4) - CLIENT_CHUNK_RADIUS) << 4);
    }

    /** The highest block coordinate on an axis that the client holds, at most {@code wanted}. */
    public static int clientHeldMax(int playerBlock, int wanted) {
        return Math.min(wanted, (((playerBlock >> 4) + CLIENT_CHUNK_RADIUS) << 4) + 15);
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
