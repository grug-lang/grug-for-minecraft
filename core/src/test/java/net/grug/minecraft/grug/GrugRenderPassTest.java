package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Covers the bookkeeping around a block entity's render pass: recording, order and nesting. */
class GrugRenderPassTest {

    @AfterEach
    void closeAnyPassLeftOpen() {
        // The nested-open test throws on purpose, which leaves the flag set. Leaving it set would
        // fail every later test in this class for a reason that has nothing to do with them.
        if (GrugRenderPass.isOpen()) {
            GrugRenderPass.close();
        }
    }

    @TempDir Path mods;

    private String writeModel(String json) throws Exception {
        Path file = mods.resolve("testmod/assets/grug/models/block/pass.json");
        Files.createDirectories(file.getParent());
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        GrugModels.clearCache();
        return "testmod/assets/grug/models/block/pass.json";
    }

    @Test
    void aModelOutsideAPassIsRefusedWithTheReasonTheCallerReports() throws Exception {
        String path = writeModel("{}");

        assertTrue(
                GrugRenderPass.recordModel(mods.toFile(), path)
                        .contains("while a block entity's render() is drawing"));
    }

    @Test
    void aModelsFacesAreRecordedBesideTheBoxes() throws Exception {
        String path =
                writeModel(
                        "{\"textures\": {\"t\": \"grug:block/t\"}, \"elements\": [{\"from\": [0,"
                                + " 0, 0], \"to\": [16, 16, 16], \"faces\": {\"up\": {\"texture\":"
                                + " \"#t\"}}}]}");
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);

        assertEquals(null, GrugRenderPass.recordModel(mods.toFile(), path));

        GrugGeometry geometry = GrugRenderPass.close();
        assertEquals(1, geometry.boxes().size());
        assertEquals(1, geometry.quads().size());
        assertFalse(geometry.isEmpty());
    }

    @Test
    void aPassWithOnlyAModelIsNotEmpty() throws Exception {
        String path =
                writeModel(
                        "{\"elements\": [{\"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\":"
                                + " {\"up\": {\"texture\": \"grug:block/t\"}}}]}");
        GrugRenderPass.open();
        GrugRenderPass.recordModel(mods.toFile(), path);

        assertFalse(GrugRenderPass.close().isEmpty());
    }

    @Test
    void aModelThatCannotBeDrawnRecordsNothingAndSaysWhy() throws Exception {
        String path = writeModel("{}");
        GrugRenderPass.open();

        assertTrue(GrugRenderPass.recordModel(mods.toFile(), path).contains("no elements"));
        assertTrue(GrugRenderPass.close().isEmpty());
    }

    /** A flat plate whose up face is at height 0.25 and carries a cullface, and nothing else. */
    private String writePlate() throws Exception {
        return writeModel(
                "{\"textures\": {\"t\": \"grug:block/t\"}, \"elements\": [{\"from\": [0, 0, 0],"
                        + " \"to\": [16, 4, 16], \"faces\": {\"up\": {\"texture\": \"#t\","
                        + " \"cullface\": \"up\"}, \"north\": {\"texture\": \"#t\"}}}]}");
    }

    private GrugQuad turnedQuad(double x, double y, double z, int index) throws Exception {
        GrugRenderPass.open();
        assertEquals(null, GrugRenderPass.recordModel(mods.toFile(), writePlate(), x, y, z));
        return GrugRenderPass.close().quads().get(index);
    }

    @Test
    void aTurnedModelsFacesFollowTheTurnWithTheirSideAndCullSide() throws Exception {
        // The up face is the first quad. Clockwise seen from the positive end of the axis, a turn
        // about z carries up to east and a turn about x carries up to north.
        GrugQuad east = turnedQuad(0, 0, 90, 0);
        assertEquals(5, east.side());
        assertEquals(5, east.cullSide());
        for (int corner = 0; corner < 4; corner++) {
            assertEquals(0.25, east.x(corner), 1e-9);
        }
        assertEquals(2, turnedQuad(90, 0, 0, 0).side());

        // The north face is the second quad. A turn about y carries it to east, and a face with no
        // cullface keeps having none.
        GrugQuad north = turnedQuad(0, 90, 0, 1);
        assertEquals(5, north.side());
        assertEquals(-1, north.cullSide());
    }

    @Test
    void theTurnsApplyAboutZFirstThenYThenXAndAnyMultipleOfNinetyWorks() throws Exception {
        // Up goes to east about z, and a turn of 90 about y then carries east to south.
        assertEquals(3, turnedQuad(0, 90, 90, 0).side());
        // A full turn about z changes nothing, so up still goes to north about x.
        assertEquals(2, turnedQuad(90, 0, 360, 0).side());
        // -90 is 270, and a full turn is no turn.
        assertEquals(turnedQuad(0, 0, 270, 0).side(), turnedQuad(0, 0, -90, 0).side());
        assertEquals(1, turnedQuad(0, 360, 0, 0).side());
    }

    @Test
    void anAngleThatIsNotAQuarterTurnRecordsNothingAndSaysWhy() throws Exception {
        String path = writePlate();
        GrugRenderPass.open();

        assertTrue(
                GrugRenderPass.recordModel(mods.toFile(), path, 0, 45, 0)
                        .contains("multiples of 90 degrees"));
        assertTrue(
                GrugRenderPass.recordModel(mods.toFile(), path, 1e9, 0, 0)
                        .contains("multiples of 90 degrees"));
        assertTrue(GrugRenderPass.close().isEmpty());
    }

    @Test
    void aTurnedModelOutsideAPassIsRefused() throws Exception {
        assertTrue(
                GrugRenderPass.recordModel(mods.toFile(), writePlate(), 0, 0, 90)
                        .contains("while a block entity's render() is drawing"));
    }

    @Test
    void aTurnedModelThatCannotBeDrawnRecordsNothingAndSaysWhy() throws Exception {
        String path = writeModel("{}");
        GrugRenderPass.open();

        assertTrue(
                GrugRenderPass.recordModel(mods.toFile(), path, 0, 0, 90).contains("no elements"));
        assertTrue(GrugRenderPass.close().isEmpty());
    }

    @Test
    void aPassIsNotOpenUntilOneIsOpened() {
        assertFalse(GrugRenderPass.isOpen());
    }

    @Test
    void recordingOutsideAPassIsRefused() {
        // Nothing is recorded, so the caller's report is all that happens: a draw call outside a
        // render pass has nowhere to go.
        assertFalse(GrugRenderPass.record(0, 0, 0, 1, 1, 1));
    }

    @Test
    void anOpenPassIsOpen() {
        GrugRenderPass.open();
        assertTrue(GrugRenderPass.isOpen());
    }

    @Test
    void recordingInsideAPassIsAccepted() {
        GrugRenderPass.open();

        assertTrue(GrugRenderPass.record(0.25, 0.25, 0.25, 0.75, 0.75, 0.75));
    }

    @Test
    void aBoxTheRightWayRoundIsOrdered() {
        assertTrue(GrugRenderPass.isOrdered(new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)));
    }

    @Test
    void aFlatBoxIsOrderedBecauseAPlateIsAShape() {
        // Zero thickness on one axis is a plate. The rule is about which corner comes first, not
        // about how far apart the two are, so a box whose two corners on an axis coincide is still
        // the right way round.
        assertTrue(GrugRenderPass.isOrdered(new GrugBox(0.0, 0.5, 0.5, 1.0, 0.5, 0.5)));
    }

    @Test
    void aBoxWithItsCornersTheWrongWayRoundIsNotOrdered() {
        assertFalse(GrugRenderPass.isOrdered(new GrugBox(0.75, 0.25, 0.25, 0.25, 0.75, 0.75)));
        assertFalse(GrugRenderPass.isOrdered(new GrugBox(0.25, 0.75, 0.25, 0.75, 0.25, 0.75)));
        assertFalse(GrugRenderPass.isOrdered(new GrugBox(0.25, 0.25, 0.75, 0.75, 0.75, 0.25)));
    }

    @Test
    void aBoxThatReachesPastTheBlockIsOrderedBecauseTheRangeIsNotTheRule() {
        // The game draws a box reaching past its block unclipped, so the author gets the arithmetic
        // they wrote rather than a shape grug chose. Refusing the range would be grug picking a
        // different box than the mod asked for, which is the one thing this API never does.
        assertTrue(GrugRenderPass.isOrdered(new GrugBox(-0.25, 0.0, 0.0, 0.5, 1.0, 1.0)));
        assertTrue(GrugRenderPass.isOrdered(new GrugBox(0.5, 0.0, 0.0, 1.25, 1.0, 1.0)));
    }

    @Test
    void aBoxTheWrongWayRoundIsRefusedAndNotRecorded() {
        // The refusal is reachable without a game: open() is public and touches nothing but Java
        // state, so a test arranges one and calls record directly. Nothing is recorded, which is
        // what leaves the pass empty for the caller to report.
        GrugRenderPass.open();

        assertFalse(GrugRenderPass.record(0.75, 0.25, 0.25, 0.25, 0.75, 0.75));
        assertEquals(List.of(), GrugRenderPass.recorded());
    }

    @Test
    void aRefusedBoxDoesNotStopTheOnesAfterIt() {
        GrugRenderPass.open();
        GrugRenderPass.record(0.75, 0.25, 0.25, 0.25, 0.75, 0.75);

        assertTrue(GrugRenderPass.record(0.25, 0.25, 0.25, 0.75, 0.75, 0.75));
        assertEquals(
                List.of(new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)),
                GrugRenderPass.recorded());
    }

    @Test
    void anOpenPassRecordsWhatWasDrawn() {
        GrugRenderPass.open();
        GrugRenderPass.record(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

        assertEquals(
                List.of(new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)),
                GrugRenderPass.recorded());
    }

    @Test
    void aClosedPassIsClosedAgain() {
        GrugRenderPass.open();
        GrugRenderPass.close();

        assertFalse(GrugRenderPass.isOpen());
    }

    @Test
    void recordingAfterClosingIsRefusedAgain() {
        GrugRenderPass.open();
        GrugRenderPass.close();

        assertFalse(GrugRenderPass.record(0, 0, 0, 1, 1, 1));
    }

    @Test
    void aPassReturnsItsBoxesInTheOrderTheyWereDrawn() {
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);
        GrugRenderPass.record(0.5, 0.5, 0.5, 0.75, 0.75, 0.75);

        assertEquals(
                List.of(
                        new GrugBox(0, 0, 0, 1, 1, 1),
                        new GrugBox(0.5, 0.5, 0.5, 0.75, 0.75, 0.75)),
                GrugRenderPass.close().boxes());
    }

    @Test
    void anEmptyPassReturnsNothing() {
        GrugRenderPass.open();

        assertEquals(List.of(), GrugRenderPass.close().boxes());
    }

    @Test
    void closingHandsTheBoxesOverRatherThanAliasingThePass() {
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);
        List<GrugBox> first = GrugRenderPass.close().boxes();

        GrugRenderPass.open();
        GrugRenderPass.record(0.5, 0, 0, 1, 1, 1);

        assertEquals(1, first.size(), "the next pass cannot change a closed one's boxes");
        assertEquals(
                List.of(new GrugBox(0.5, 0, 0, 1, 1, 1)),
                GrugRenderPass.recorded(),
                "the new pass records on its own");
    }

    @Test
    void aPassStartsEmptyRatherThanInheritingWhatALeftOneRecorded() {
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);
        GrugRenderPass.close();

        GrugRenderPass.open();
        assertEquals(List.of(), GrugRenderPass.recorded());
    }

    @Test
    void theRecordedBoxesCannotBeChangedThroughTheReturnedList() {
        GrugRenderPass.open();
        GrugRenderPass.record(0, 0, 0, 1, 1, 1);

        assertThrows(
                UnsupportedOperationException.class,
                () -> GrugRenderPass.recorded().add(new GrugBox(0, 0, 0, 1, 1, 1)));
    }

    @Test
    void openingWhileAPassIsOpenBreaksAnInvariant() {
        GrugRenderPass.open();

        // Only the engine can reach this: a script cannot start a loader's render pass, and a
        // loader opens and closes one within a single call. A second open means the engine is
        // broken, and one crash beats geometry quietly drawing into the wrong buffer.
        RuntimeException error = assertThrows(RuntimeException.class, GrugRenderPass::open);
        assertTrue(error.getMessage().contains("already open"), error.getMessage());
    }

    @Test
    void aBoxCarriesTheCornersItWasDrawnWith() {
        GrugBox box = new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);

        assertEquals(0.1, box.x1());
        assertEquals(0.2, box.y1());
        assertEquals(0.3, box.z1());
        assertEquals(0.4, box.x2());
        assertEquals(0.5, box.y2());
        assertEquals(0.6, box.z2());
    }

    @Test
    void aBoxNamesItsCornersInItsTextForm() {
        // Read when a loader or a log line says what was drawn, so the corners have to be legible
        // rather than an opaque handle.
        assertEquals(
                "GrugBox[0.1, 0.2, 0.3 -> 0.4, 0.5, 0.6]",
                new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6).toString());
    }

    @Test
    void twoBoxesAreEqualWhenTheirCornersAre() {
        GrugBox box = new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);

        assertEquals(box, new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6));
        assertEquals(box.hashCode(), new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6).hashCode());
    }

    @Test
    void aBoxIsNotEqualToADifferentOne() {
        GrugBox box = new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.6);

        assertNotEquals(box, new GrugBox(0.1, 0.2, 0.3, 0.4, 0.5, 0.7));
        assertNotEquals(box, new GrugBox(0.2, 0.2, 0.3, 0.4, 0.5, 0.6));
        assertNotEquals(box, new GrugBox(0.1, 0.3, 0.3, 0.4, 0.5, 0.6));
        assertNotEquals(box, new GrugBox(0.1, 0.2, 0.4, 0.4, 0.5, 0.6));
        assertNotEquals(box, new GrugBox(0.1, 0.2, 0.3, 0.5, 0.5, 0.6));
        assertNotEquals(box, new GrugBox(0.1, 0.2, 0.3, 0.4, 0.6, 0.6));
    }

    @Test
    void aBoxIsNotEqualToSomethingElse() {
        assertNotEquals(new GrugBox(0, 0, 0, 1, 1, 1), "not a box");
        assertNotEquals(new GrugBox(0, 0, 0, 1, 1, 1), null);
        assertEquals(new GrugBox(0, 0, 0, 1, 1, 1), new GrugBox(0, 0, 0, 1, 1, 1));
    }

    @Test
    void aBoxIsEqualToItself() {
        // The identity shortcut in equals is the first thing it tries, so comparing a box with the
        // box it already is has to come out equal before any corner is read.
        GrugBox box = new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

        assertEquals(box, box);
        assertEquals(box.hashCode(), box.hashCode());
    }
}
