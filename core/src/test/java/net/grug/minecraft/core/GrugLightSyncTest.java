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
    void aBatchThatPublishesAroundTheSettledCheckIsNotTheRoomsLight() {
        // The section the room's light is read for: its light before the light thread's batch, its
        // light once the batch has published, and the copy the client holds while the packet has
        // not reached it. Only the batch changes the section, so the two fingerprints differ.
        long beforeBatch = GrugLightSync.lightFingerprint(layer(4));
        long afterBatch = GrugLightSync.lightFingerprint(layer(12));
        long clientCopy = beforeBatch;

        // The trigger: the batch publishes between the sample and the settled check that follows
        // it, so the check answers that the engine is done while the sample holds the light from
        // before the batch, and the client holds it too. A comparison built on that one sample
        // answered true for a room the next frame draws a light step brighter (#1248).
        long sampledBeforeTheCheck = beforeBatch;
        assertTrue(
                GrugLightSync.lightApplied(
                        true, sampledBeforeTheCheck, sampledBeforeTheCheck, clientCopy),
                "The pre-batch light on both sides is what the old order compared.");

        // The wait's order takes the sample after the check, and a second one after that, so a
        // batch that publishes anywhere around the check leaves the two samples different and the
        // answer stays false.
        assertFalse(
                GrugLightSync.lightApplied(true, beforeBatch, afterBatch, clientCopy),
                "A batch that published between the samples is not a settled room.");
        // The samples agree once the batch is in both of them, and the client has not been sent it.
        assertFalse(
                GrugLightSync.lightApplied(true, afterBatch, afterBatch, clientCopy),
                "A client that was not sent the batch's light does not hold the room's.");
        // Both samples and the client holding the batch's light is the room's settled light.
        assertTrue(GrugLightSync.lightApplied(true, afterBatch, afterBatch, afterBatch));
        // The engine taking on work after the samples leaves the answer false however they agree,
        // because a second batch can still change the section before a frame is drawn.
        assertFalse(GrugLightSync.lightApplied(false, afterBatch, afterBatch, afterBatch));
        // A client that cannot verify its copy answers -1, which is read as applied so a loader
        // whose client cannot reproduce the world's light does not wait until its deadline (#1240).
        assertTrue(GrugLightSync.lightApplied(true, afterBatch, afterBatch, -1));
        // A column the client was never sent answers -2, which is not applied even when both
        // samples agree: two empty sections are not a room the client can draw (#1242).
        assertFalse(
                GrugLightSync.lightApplied(
                        true, afterBatch, afterBatch, GrugClientLight.NOT_RECEIVED));
        assertFalse(
                GrugLightSync.lightApplied(true, 0, 0, GrugClientLight.NOT_RECEIVED),
                "An unlit world section the client was never sent still has no client copy.");
        // A world with no level to sample holds no section, so -1 there is still false.
        assertFalse(GrugLightSync.lightApplied(true, -1, -1, -1));
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
    void aFingerprintIsTheSameForTheSameLightAndZeroForNoLight() {
        byte[] lit = layer(13);
        byte[] dim = layer(4);
        byte[] dark = layer(0);
        byte[] other = new byte[2048];
        other[777] = 12;
        other[2047] = 3;

        // No layer and a layer that holds no light are the same answer, as lightsMatch says.
        assertEquals(0, GrugLightSync.lightFingerprint(null));
        assertEquals(0, GrugLightSync.lightFingerprint(dark));
        // The same light gives the same fingerprint, including for two copies of one layer.
        assertEquals(
                GrugLightSync.lightFingerprint(lit), GrugLightSync.lightFingerprint(lit.clone()));
        assertEquals(
                GrugLightSync.lightFingerprint(other),
                GrugLightSync.lightFingerprint(other.clone()));
        // A different light gives a different one, so a stale copy does not match by accident.
        assertFalse(GrugLightSync.lightFingerprint(lit) == GrugLightSync.lightFingerprint(dim));
        assertFalse(GrugLightSync.lightFingerprint(other) == GrugLightSync.lightFingerprint(lit));
        assertFalse(GrugLightSync.lightFingerprint(other) == 0);
        // It fits a double exactly, which is how a control message carries it.
        assertTrue(GrugLightSync.lightFingerprint(other) >>> 53 == 0);
        assertEquals(
                GrugLightSync.lightFingerprint(other),
                (long) (double) GrugLightSync.lightFingerprint(other));
    }

    @Test
    void aLevelFingerprintIsTheSameForTheSameLightAndZeroForNoLight() {
        // One section read level by level, as the four older loaders answer it: a section whose
        // levels are all the same, one that is dark, and one with two lit cells.
        GrugLightSync.BlockLight lit = (x, y, z) -> 13;
        GrugLightSync.BlockLight dim = (x, y, z) -> 4;
        GrugLightSync.BlockLight dark = (x, y, z) -> 0;
        GrugLightSync.BlockLight other =
                (x, y, z) ->
                        x == 3 && y == 4 && z == 5 ? 12 : (x == 15 && y == 0 && z == 1 ? 3 : 0);

        // No light at all is 0, as the byte fingerprint says of a missing and of a zero layer.
        assertEquals(0, GrugLightSync.lightFingerprint(0, 0, 0, dark));
        // The same light gives the same fingerprint, whatever the section is.
        assertEquals(
                GrugLightSync.lightFingerprint(0, 0, 0, lit),
                GrugLightSync.lightFingerprint(7, -3, 11, lit));
        assertEquals(
                GrugLightSync.lightFingerprint(0, 0, 0, other),
                GrugLightSync.lightFingerprint(0, 0, 0, other));
        // A different light gives a different one.
        assertFalse(
                GrugLightSync.lightFingerprint(0, 0, 0, lit)
                        == GrugLightSync.lightFingerprint(0, 0, 0, dim));
        assertFalse(
                GrugLightSync.lightFingerprint(0, 0, 0, other)
                        == GrugLightSync.lightFingerprint(0, 0, 0, lit));
        assertFalse(GrugLightSync.lightFingerprint(0, 0, 0, other) == 0);
        // The same section read one block over is a different section, so its origin is used.
        assertFalse(
                GrugLightSync.lightFingerprint(0, 0, 0, other)
                        == GrugLightSync.lightFingerprint(0, 0, 1, other));
        // It fits a double exactly, which is how a control message carries it.
        assertTrue(GrugLightSync.lightFingerprint(0, 0, 0, other) >>> 53 == 0);
        assertEquals(
                GrugLightSync.lightFingerprint(0, 0, 0, other),
                (long) (double) GrugLightSync.lightFingerprint(0, 0, 0, other));
    }

    @Test
    void theHighestLevelIsLitInBothFormsAndALevelAboveItMasksToNothing() {
        GrugLightSync.BlockLight highest = (x, y, z) -> 15;
        long fifteen = GrugLightSync.lightFingerprint(0, 0, 0, highest);

        // 15 is the highest level a nibble holds, so the level form calls the section lit and keeps
        // it apart from the level below, as the byte form does for the layer that holds it. Both
        // forms answer the same question about the same section even though they hash different
        // numbers: 4096 levels here, 2048 packed bytes there.
        assertFalse(fifteen == 0);
        assertEquals(fifteen, GrugLightSync.lightFingerprint(0, 0, 0, highest));
        assertFalse(fifteen == GrugLightSync.lightFingerprint(0, 0, 0, (x, y, z) -> 14));
        assertFalse(GrugLightSync.lightFingerprint(layer(15)) == 0);
        assertFalse(
                GrugLightSync.lightFingerprint(layer(15))
                        == GrugLightSync.lightFingerprint(layer(14)));
        assertTrue(fifteen >>> 53 == 0);

        // 16 is one above what a nibble holds, and the fold keeps a level's low four bits, so a
        // section of it is dark rather than lit with a sixteenth light.
        assertEquals(0, GrugLightSync.lightFingerprint(0, 0, 0, (x, y, z) -> 16));
        // The same mask on a lit section drops the stray bit instead of adding a different light.
        assertEquals(
                GrugLightSync.lightFingerprint(
                        0, 0, 0, (x, y, z) -> x == 3 && y == 4 && z == 5 ? 19 : 0),
                GrugLightSync.lightFingerprint(
                        0, 0, 0, (x, y, z) -> x == 3 && y == 4 && z == 5 ? 3 : 0));
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
}
