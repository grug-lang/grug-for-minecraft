package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Covers the client's copy of a test's block edits in {@link GrugBlockSync}. */
class GrugBlockSyncTest {

    @Test
    void everyCombinationOfArrivedAndMissingIsAnswered() {
        for (int size = 0; size <= 3; size++) {
            int combinations = 1 << size;
            for (int mask = 0; mask < combinations; mask++) {
                String[] owed = new String[size];
                String[] client = new String[size];
                for (int i = 0; i < size; i++) {
                    owed[i] = "minecraft:block_" + i;
                    client[i] = ((mask >> i) & 1) == 1 ? owed[i] : "minecraft:other_" + i;
                }

                assertEquals(
                        mask == combinations - 1,
                        GrugBlockSync.everyEditArrived(owed, client),
                        "edits " + mask + " of " + size + " arrived");
            }
        }
    }

    @Test
    void aClientThatCannotAnswerHasNotReceivedTheEdit() {
        assertFalse(
                GrugBlockSync.everyEditArrived(
                        new String[] {"minecraft:stone"}, new String[] {null}));
        assertTrue(GrugBlockSync.everyEditArrived(new String[] {null}, new String[] {null}));
    }

    @Test
    void anEditWithoutAClientAnswerIsAnInvariant() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GrugBlockSync.everyEditArrived(
                                new String[] {"minecraft:stone", "minecraft:dirt"},
                                new String[] {"minecraft:stone"}));
    }

    @Test
    void aFrameWithNothingOwedHasNoWorkAndStaysSettled() {
        GrugBlockSync sync = new GrugBlockSync();

        assertTrue(sync.settled());
        assertFalse(
                sync.onFrame(
                        (x, y, z) -> {
                            throw new AssertionError("Nothing is owed, so nothing is read.");
                        }));
        assertTrue(sync.settled());
    }

    @Test
    void theFrameThatSawTheEditIsBusyAndTheNextOneIsNot() {
        GrugBlockSync sync = new GrugBlockSync();
        sync.owe(1, 2, 3, "minecraft:stone");

        assertFalse(sync.settled());
        assertTrue(sync.onFrame((x, y, z) -> "minecraft:stone"));
        assertTrue(sync.settled());
        assertFalse(sync.onFrame((x, y, z) -> "minecraft:stone"));
    }

    @Test
    void anEditTheClientMissedStaysOwedUntilAFrameSeesIt() {
        GrugBlockSync sync = new GrugBlockSync();
        sync.owe(1, 2, 3, "minecraft:stone");

        assertTrue(sync.onFrame((x, y, z) -> "minecraft:air"));
        assertFalse(sync.settled());
        assertTrue(sync.onFrame((x, y, z) -> "minecraft:stone"));
        assertTrue(sync.settled());
    }

    @Test
    void oneMissingEditKeepsTheWholeFrameBusy() {
        GrugBlockSync sync = new GrugBlockSync();
        sync.owe(1, 2, 3, "minecraft:stone");
        sync.owe(4, 5, 6, "minecraft:dirt");

        assertTrue(sync.onFrame((x, y, z) -> x == 1 ? "minecraft:stone" : "minecraft:air"));
        assertFalse(sync.settled());
        assertTrue(sync.onFrame((x, y, z) -> x == 1 ? "minecraft:stone" : "minecraft:dirt"));
        assertTrue(sync.settled());
    }

    @Test
    void anEditMadeTwiceWaitsForTheBlockTheTestLeft() {
        GrugBlockSync sync = new GrugBlockSync();
        sync.owe(1, 2, 3, "minecraft:stone");
        sync.owe(1, 2, 3, "minecraft:dirt");

        assertTrue(sync.onFrame((x, y, z) -> "minecraft:stone"));
        assertFalse(sync.settled());
        assertTrue(sync.onFrame((x, y, z) -> "minecraft:dirt"));
        assertTrue(sync.settled());
    }

    @Test
    void aRefreshWaitsForWhatTheSimulatedWorldHoldsNow() {
        GrugBlockSync sync = new GrugBlockSync();
        sync.owe(1, 2, 3, "minecraft:air");

        // A clear can be refilled by water, so the client is owed the world's current block rather
        // than the one the test placed.
        sync.refresh((x, y, z) -> "minecraft:water");

        assertTrue(sync.onFrame((x, y, z) -> "minecraft:air"));
        assertFalse(sync.settled());
        assertTrue(sync.onFrame((x, y, z) -> "minecraft:water"));
        assertTrue(sync.settled());
    }

    @Test
    void aCaptureAfterTheWorldReplacedTheBlockRefreshesBeforeItAnswers() {
        GrugBlockSync sync = new GrugBlockSync();
        sync.owe(1, 2, 3, "minecraft:stone");

        // A capture's wait has no settle call in front of it, so its own answer refreshes from the
        // world first: the world refilled the position with water after the test placed the stone,
        // and an answer that kept the stone would compare it against the client's water for ever.
        // The refresh alone is not the client's answer, so the edit stays owed until a frame has
        // seen it.
        assertFalse(
                sync.settled((x, y, z) -> "minecraft:water"),
                "The client has not shown the replacement yet.");
        assertTrue(sync.onFrame((x, y, z) -> "minecraft:water"));
        assertTrue(sync.settled((x, y, z) -> "minecraft:water"));
    }

    @Test
    void everyOwedEditIsReadAtItsOwnPosition() {
        GrugBlockSync sync = new GrugBlockSync();
        sync.owe(-5, -64, 7, "minecraft:stone");
        sync.owe(30000000, 320, -30000000, "minecraft:dirt");
        List<String> reads = new ArrayList<>();

        sync.onFrame(
                (x, y, z) -> {
                    reads.add(x + "," + y + "," + z);
                    return "minecraft:air";
                });

        assertEquals(List.of("-5,-64,7", "30000000,320,-30000000"), reads);
    }
}
