package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugFrameRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Covers the three passes that run while no screen is open, and the VM-free arms of the runners.
 */
class GrugWorldOverlayTest {

    @BeforeEach
    @AfterEach
    void closeAnyPass() {
        if (GrugWorldOverlay.isHud()) {
            GrugWorldOverlay.closeHud();
        } else if (GrugWorldOverlay.isWorld()) {
            GrugWorldOverlay.closeWorld();
        } else if (GrugWorldOverlay.isKey()) {
            GrugWorldOverlay.closeKey();
        } else if (GrugWorldOverlay.isBinding()) {
            GrugWorldOverlay.closeBinding();
        }
    }

    @Test
    void aBindingPassCarriesTheModAndItsIdAndForgetsThemWhenItCloses() {
        GrugWorldOverlay.openBinding("coverage", "toggle");
        assertTrue(GrugWorldOverlay.isBinding());
        assertEquals("coverage", GrugWorldOverlay.mod());
        assertEquals("toggle", GrugWorldOverlay.bindingId());
        GrugWorldOverlay.closeBinding();
        assertFalse(GrugWorldOverlay.isBinding());
        assertNull(GrugWorldOverlay.mod());
        assertNull(GrugWorldOverlay.bindingId());
    }

    @Test
    void aHudPassRecordsItsTextsInOrder() {
        GrugWorldOverlay.openHud(426, 240);
        assertTrue(GrugWorldOverlay.isOpen());
        assertNotNull(GrugWorldOverlay.context());
        assertEquals(426, GrugWorldOverlay.screenWidth());
        assertEquals(240, GrugWorldOverlay.screenHeight());

        assertTrue(GrugWorldOverlay.recordText("one", 1, 2, 0xff0000));
        assertTrue(GrugWorldOverlay.recordText("two", 3, 4, 0x00ff00));
        assertFalse(GrugWorldOverlay.recordLine(0, 0, 0, 1, 1, 1, 0), "A HUD pass takes no line.");

        List<GrugWorldOverlay.Text> texts = GrugWorldOverlay.closeHud();
        assertEquals(2, texts.size());
        assertEquals("one", texts.get(0).text());
        assertEquals(1, texts.get(0).x());
        assertEquals(2, texts.get(0).y());
        assertEquals(0xff0000, texts.get(0).rgb());
        assertEquals("two", texts.get(1).text());
        assertFalse(GrugWorldOverlay.isOpen());
        assertNull(GrugWorldOverlay.context());
    }

    @Test
    void aWorldPassRecordsItsLinesAndTheCameraItWasGiven() {
        GrugWorldOverlay.openWorld(0.5, 10, 20, 30, 10, 18, 30, "coverage");
        assertTrue(GrugWorldOverlay.isWorld());
        assertEquals(0.5, GrugWorldOverlay.partialTick());
        assertEquals(10, GrugWorldOverlay.cameraX());
        assertEquals(20, GrugWorldOverlay.cameraY());
        assertEquals(30, GrugWorldOverlay.cameraZ());

        assertTrue(GrugWorldOverlay.recordLine(1, 2, 3, 4, 5, 6, 0x123456));
        assertFalse(GrugWorldOverlay.recordText("x", 0, 0, 0), "A world pass takes no text.");

        List<GrugWorldOverlay.Line> lines = GrugWorldOverlay.closeWorld();
        assertEquals(1, lines.size());
        GrugWorldOverlay.Line line = lines.get(0);
        assertEquals(1, line.x1());
        assertEquals(2, line.y1());
        assertEquals(3, line.z1());
        assertEquals(4, line.x2());
        assertEquals(5, line.y2());
        assertEquals(6, line.z2());
        assertEquals(0x123456, line.rgb());
    }

    @Test
    void aKeyPassCarriesTheKeyAndForgetsItWhenItCloses() {
        GrugWorldOverlay.openKey('L', true);
        assertTrue(GrugWorldOverlay.isKey());
        assertEquals('L', GrugWorldOverlay.keyCode());
        assertTrue(GrugWorldOverlay.ctrl());
        GrugWorldOverlay.closeKey();
        assertEquals(0.0, GrugWorldOverlay.keyCode());
        assertFalse(GrugWorldOverlay.ctrl());
    }

    @Test
    void aSecondPassWhileOneIsOpenIsABrokenInvariant() {
        GrugWorldOverlay.openHud(1, 1);
        assertThrows(Throwable.class, () -> GrugWorldOverlay.openWorld(0, 0, 0, 0, 0, 0, 0, null));
    }

    @Test
    void closingAsTheWrongKindIsABrokenInvariant() {
        GrugWorldOverlay.openHud(1, 1);
        assertThrows(Throwable.class, GrugWorldOverlay::closeWorld);
    }

    @Test
    void aMissingScriptOrExportStillOpensAndClosesThePass() {
        assertTrue(GrugWorldOverlay.hud(0, Grug.INVALID_GRUG_EXPORT_FN_ID, 10, 20).isEmpty());
        assertTrue(GrugWorldOverlay.world(0, 5, 0, 1, 2, 3, 1, 1, 3, "coverage").isEmpty());
        GrugWorldOverlay.key(0, Grug.INVALID_GRUG_EXPORT_FN_ID, 'L', false);
        // A live entity whose file has no such export is skipped too, without a native call.
        assertTrue(GrugWorldOverlay.hud(1, Grug.INVALID_GRUG_EXPORT_FN_ID, 10, 20).isEmpty());
        assertFalse(GrugWorldOverlay.isOpen());
    }

    @Test
    void supportAndTheDrawnMarkStartFalseAndStayTrueOnceNoted() {
        GrugFrameRequest.endTest();
        GrugWorldOverlay.clearDrawn();
        assertFalse(GrugWorldOverlay.drawnForTest());
        GrugWorldOverlay.noteDrawn();
        assertTrue(GrugWorldOverlay.drawnForTest());
        GrugWorldOverlay.clearDrawn();
        assertFalse(GrugWorldOverlay.drawnForTest());
        GrugWorldOverlay.noteSupported();
        assertTrue(GrugWorldOverlay.supported());
    }
}
