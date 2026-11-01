package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/** Covers the light comparison and the debt a room rebuild leaves in {@link GrugLightSync}. */
class GrugLightSyncTest {

    /** A section that is all one light level, which is all a test needs to tell them apart. */
    private static byte[] layer(int level) {
        byte[] bytes = new byte[2048];
        java.util.Arrays.fill(bytes, (byte) (level << 4 | level));
        return bytes;
    }

    @Test
    void everyCombinationOfTwoSectionAnswersIsComparedByWhatItHolds() {
        byte[] lit = layer(13);
        byte[] dim = layer(4);
        byte[] dark = layer(0);
        byte[] other = new byte[2048];
        other[777] = 12;

        // Both sides missing a layer is no light on either side.
        assertTrue(GrugLightSync.lightsMatch(null, null));
        // A level that never stored a layer holds no light, which is what a zero layer holds too.
        assertTrue(GrugLightSync.lightsMatch(null, dark));
        assertTrue(GrugLightSync.lightsMatch(dark, null));
        // A missing layer is not a lit one.
        assertFalse(GrugLightSync.lightsMatch(null, lit));
        assertFalse(GrugLightSync.lightsMatch(lit, null));
        assertFalse(GrugLightSync.lightsMatch(dim, lit));
        assertFalse(GrugLightSync.lightsMatch(lit, dim));
        // The same light in two layers is the same light.
        assertTrue(GrugLightSync.lightsMatch(lit, lit));
        assertTrue(GrugLightSync.lightsMatch(dim, dim));
        assertTrue(GrugLightSync.lightsMatch(other, other.clone()));
        assertFalse(GrugLightSync.lightsMatch(other, dark));
    }

    @Test
    void aBoxOwesEverySectionItCoversIncludingTheOnesBeforeZero() {
        GrugLightSync sync = new GrugLightSync();
        // A box from -3 to 3 in x and z and from 62 to 79 in y: one section each way in x and z
        // (the sections either side of zero) and two in y.
        sync.oweBox(-3, 62, -3, 3, 79, 3);

        assertEquals(2 * 2 * 2, sync.pendingSections());
    }

    @Test
    void aClosedBoxOwesOneSection() {
        GrugLightSync sync = new GrugLightSync();
        sync.oweBox(1, 1, 1, 2, 2, 2);

        assertEquals(1, sync.pendingSections());
    }

    @Test
    void theDebtIsForgottenOnceTheClientHoldsEverySection() {
        GrugLightSync sync = new GrugLightSync();
        sync.oweBox(4, 64, 4, 4, 64, 4);
        Map<Long, byte[]> simulated = new HashMap<>();
        Map<Long, byte[]> client = new HashMap<>();

        // The world has light for the section (0, 4, 0) the box is in, and the client has not been
        // told about it yet.
        simulated.put(key(0, 4, 0), layer(14));
        assertFalse(sync.settled(lookup(simulated), lookup(client)));
        assertEquals(1, sync.pendingSections());

        // The client's copy catches up.
        client.put(key(0, 4, 0), layer(14));
        assertTrue(sync.settled(lookup(simulated), lookup(client)));
        assertEquals(0, sync.pendingSections());

        // A later change is not owed by this rebuild, which is what makes the answer cheap: the
        // next rebuild owes it again.
        simulated.put(key(0, 4, 0), layer(3));
        assertTrue(sync.settled(lookup(simulated), lookup(client)));
    }

    @Test
    void everySectionHasToMatchBeforeTheDebtIsForgotten() {
        GrugLightSync sync = new GrugLightSync();
        sync.oweBox(0, 64, 0, 16, 64, 0);

        Map<Long, byte[]> simulated = new HashMap<>();
        Map<Long, byte[]> client = new HashMap<>();
        simulated.put(key(0, 4, 0), layer(14));
        simulated.put(key(1, 4, 0), layer(9));
        client.put(key(0, 4, 0), layer(14));

        assertFalse(sync.settled(lookup(simulated), lookup(client)));
        assertEquals(2, sync.pendingSections());

        client.put(key(1, 4, 0), layer(9));
        assertTrue(sync.settled(lookup(simulated), lookup(client)));
    }

    @Test
    void nothingOwedIsSettled() {
        GrugLightSync sync = new GrugLightSync();

        assertTrue(sync.settled((x, y, z) -> null, (x, y, z) -> null));
        assertEquals(0, sync.pendingSections());
    }

    /** A section position packed the way {@link GrugLightSync} packs one. */
    private static long key(int sectionX, int sectionY, int sectionZ) {
        return ((long) (sectionX & 0x3FFFFFF) << 38)
                | ((long) (sectionY & 0xFFF) << 26)
                | (sectionZ & 0x3FFFFFF);
    }

    /** A reader that answers from a map keyed by the packed section position. */
    private static GrugLightSync.SectionLight lookup(Map<Long, byte[]> sections) {
        return (x, y, z) -> sections.get(key(x, y, z));
    }

    @Test
    void theClientIsOwedOnlyTheChunksItHolds() {
        // Player in chunk 0 at its low edge and its high edge, then in a negative chunk.
        assertEquals(-5, GrugLightSync.clientHeldMin(0, -5));
        assertEquals(-32, GrugLightSync.clientHeldMin(0, -100));
        assertEquals(-32, GrugLightSync.clientHeldMin(15, -100));
        assertEquals(47, GrugLightSync.clientHeldMax(0, 100));
        assertEquals(47, GrugLightSync.clientHeldMax(15, 100));
        assertEquals(20, GrugLightSync.clientHeldMax(0, 20));
        assertEquals(-48, GrugLightSync.clientHeldMin(-1, -100));
        assertEquals(31, GrugLightSync.clientHeldMax(-1, 100));
    }
}
