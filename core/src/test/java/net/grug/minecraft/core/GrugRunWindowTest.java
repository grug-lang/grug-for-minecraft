package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugRunWindow.Display;
import net.grug.minecraft.core.GrugRunWindow.Keys;
import net.grug.minecraft.grug.GrugScreenshots;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Covers the run's window and GL state machine in {@link GrugRunWindow}. */
class GrugRunWindowTest {

    private static final String FORCE_SIZE =
            "setSize(" + GrugScreenshots.WIDTH + "," + GrugScreenshots.HEIGHT + ")";

    private static List<String> calls(String... names) {
        return new ArrayList<>(Arrays.asList(names));
    }

    /** Formats a coordinate the way a call string reads, without a trailing ".0". */
    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /**
     * A recording window. Reads come from its current state, and every action the state machine
     * takes lands in {@code calls}, in order, so a test asserts exactly what happened to the
     * window.
     */
    private static final class FakeDisplay implements Display {
        int width = 800;
        int height = 600;
        boolean ditherOn = true;
        double cursorX = 300;
        double cursorY = 200;
        final List<String> calls = new ArrayList<>();

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public void setSize(int width, int height) {
            calls.add("setSize(" + width + "," + height + ")");
            this.width = width;
            this.height = height;
        }

        @Override
        public boolean ditherOn() {
            return ditherOn;
        }

        @Override
        public void setDither(boolean on) {
            calls.add("setDither(" + on + ")");
            ditherOn = on;
        }

        @Override
        public double[] cursorPos() {
            return new double[] {cursorX, cursorY};
        }

        @Override
        public void setCursor(double x, double y) {
            calls.add("setCursor(" + number(x) + "," + number(y) + ")");
            cursorX = x;
            cursorY = y;
        }
    }

    /**
     * A key port with the loaders' press semantics: a press is pending until a poll observes it,
     * and the poll that observes it consumes it. A skipped tick is modelled by simply not calling
     * the poll, which leaves the press pending for the next one.
     */
    private static final class FakeKeys implements Keys {
        private boolean runPending = false;
        private boolean forcePending = false;

        void pressRun() {
            runPending = true;
        }

        void pressForce() {
            forcePending = true;
        }

        @Override
        public boolean runPressed() {
            boolean pending = runPending;
            runPending = false;
            return pending;
        }

        @Override
        public boolean forceResolutionPressed() {
            boolean pending = forcePending;
            forcePending = false;
            return pending;
        }
    }

    @Test
    void aRunWithNothingForcedSetsAndRestores() {
        FakeDisplay display = new FakeDisplay();
        GrugRunWindow window = new GrugRunWindow(display);

        assertTrue(window.beginRun());
        assertEquals(GrugScreenshots.WIDTH, display.width);
        assertEquals(GrugScreenshots.HEIGHT, display.height);
        assertFalse(display.ditherOn);
        assertEquals(calls(FORCE_SIZE, "setDither(false)"), display.calls);

        // Only what the run set up comes back: the window and the dither it turned off.
        window.endRun();
        assertEquals(
                calls(FORCE_SIZE, "setDither(false)", "setSize(800,600)", "setDither(true)"),
                display.calls);
        assertFalse(window.resolutionForced());
    }

    @Test
    void aRunWithMAlreadyForcedLeavesTheWindowAlone() {
        FakeDisplay display = new FakeDisplay();
        GrugRunWindow window = new GrugRunWindow(display);

        window.toggleResolution();
        assertEquals(calls(FORCE_SIZE), display.calls);
        assertTrue(window.resolutionForced());

        // The run sees the window already held: it must not re-save or re-force it, and the dither
        // turn-off is still its own doing.
        assertTrue(window.beginRun());
        assertEquals(calls(FORCE_SIZE, "setDither(false)"), display.calls);

        // And it must not restore it either: the window is still M's, at the test resolution.
        window.endRun();
        assertEquals(calls(FORCE_SIZE, "setDither(false)", "setDither(true)"), display.calls);
        assertTrue(window.resolutionForced());
        assertEquals(GrugScreenshots.WIDTH, display.width);
        assertEquals(GrugScreenshots.HEIGHT, display.height);
    }

    @Test
    void aSecondMPressIsStillTheWayBackAfterSuchARun() {
        FakeDisplay display = new FakeDisplay();
        GrugRunWindow window = new GrugRunWindow(display);

        window.toggleResolution();
        window.beginRun();
        window.endRun();

        window.toggleResolution();
        assertEquals("setSize(800,600)", display.calls.get(3));
        assertFalse(window.resolutionForced());
        assertEquals(800, display.width);
        assertEquals(600, display.height);
    }

    @Test
    void theDitherFlagRoundTrips() {
        FakeDisplay display = new FakeDisplay();
        GrugRunWindow window = new GrugRunWindow(display);

        assertTrue(display.ditherOn);
        assertTrue(window.beginRun());
        assertFalse(display.ditherOn);
        window.endRun();
        assertTrue(display.ditherOn);
    }

    @Test
    void aRunNeverTurnsDitherOnIfItWasOff() {
        FakeDisplay display = new FakeDisplay();
        display.ditherOn = false;
        GrugRunWindow window = new GrugRunWindow(display);

        assertTrue(window.beginRun());
        assertFalse(display.ditherOn);
        assertEquals("setDither(false)", display.calls.get(1));

        window.endRun();
        // It still restores the window it forced, but there is no setDither(true): the run found
        // dither off, so it leaves it off.
        assertEquals(calls(FORCE_SIZE, "setDither(false)", "setSize(800,600)"), display.calls);
        assertFalse(display.ditherOn);
    }

    @Test
    void aSecondRunStartIsAbsorbedWhileARunIsActive() {
        FakeDisplay display = new FakeDisplay();
        FakeKeys keys = new FakeKeys();
        GrugRunWindow window = new GrugRunWindow(display);

        keys.pressRun();
        assertTrue(window.tick(keys));

        // A held R keeps reporting on the next ticks; none of them may restart or touch anything.
        keys.pressRun();
        assertFalse(window.tick(keys));
        assertEquals(calls(FORCE_SIZE, "setDither(false)"), display.calls);

        window.endRun();
        assertEquals(4, display.calls.size());
    }

    @Test
    void aPressEventIsNotLostAcrossASkippedTick() {
        FakeDisplay display = new FakeDisplay();
        FakeKeys keys = new FakeKeys();
        GrugRunWindow window = new GrugRunWindow(display);

        // Tick one has nothing pending.
        assertFalse(window.tick(keys));

        // The press lands; the next tick is the one that gets skipped (the hook did not run), so
        // nobody consumes the press. It stays pending in the port.
        keys.pressRun();

        assertTrue(window.tick(keys));
        assertEquals(calls(FORCE_SIZE, "setDither(false)"), display.calls);
    }

    @Test
    void aConsumedPressDoesNotFireAgainOnALaterTick() {
        FakeDisplay display = new FakeDisplay();
        FakeKeys keys = new FakeKeys();
        GrugRunWindow window = new GrugRunWindow(display);

        keys.pressForce();
        // M only toggles the window; it never starts a run.
        assertFalse(window.tick(keys));
        assertEquals(calls(FORCE_SIZE), display.calls);

        // The press is consumed: an idle tick must not toggle again.
        assertFalse(window.tick(keys));
        assertEquals(1, display.calls.size());

        // And the next deliberate press is the way back.
        keys.pressForce();
        assertFalse(window.tick(keys));
        assertEquals(2, display.calls.size());
        assertEquals("setSize(800,600)", display.calls.get(1));
    }

    @Test
    void aMidRunMPressRestoresThePreRunWindowAndTheRunLeavesItAlone() {
        FakeDisplay display = new FakeDisplay();
        GrugRunWindow window = new GrugRunWindow(display);

        assertTrue(window.beginRun());
        window.toggleResolution();
        assertEquals(calls(FORCE_SIZE, "setDither(false)", "setSize(800,600)"), display.calls);

        // The run no longer owns the window, so finishing it must not touch it; it still puts the
        // dither back, which was the run's own doing.
        window.endRun();
        assertEquals(
                calls(FORCE_SIZE, "setDither(false)", "setSize(800,600)", "setDither(true)"),
                display.calls);
        assertFalse(window.resolutionForced());
        assertEquals(800, display.width);
        assertEquals(600, display.height);
    }

    @Test
    void aTickSeesBothKeysAndRActsBeforeM() {
        FakeDisplay display = new FakeDisplay();
        FakeKeys keys = new FakeKeys();
        GrugRunWindow window = new GrugRunWindow(display);

        keys.pressRun();
        keys.pressForce();
        assertTrue(window.tick(keys));

        // R forced the window, then M undid it: the net is the pre-run size, no longer held.
        assertEquals(calls(FORCE_SIZE, "setDither(false)", "setSize(800,600)"), display.calls);
        assertFalse(window.resolutionForced());
        assertFalse(display.ditherOn);
    }

    @Test
    void aCursorParkedByARunComesBack() {
        FakeDisplay display = new FakeDisplay();
        GrugRunWindow window = new GrugRunWindow(display);

        window.parkCursor();
        assertEquals(calls("setCursor(0,0)"), display.calls);

        // A re-entry, or a window manager, moves the cursor back; the park applies again without
        // overwriting the position to restore.
        display.cursorX = 40;
        window.parkCursor();
        assertEquals(0, display.cursorX);
        assertEquals(calls("setCursor(0,0)", "setCursor(0,0)"), display.calls);

        window.endRun();
        assertEquals(300, display.cursorX);
        assertEquals(200, display.cursorY);
        assertEquals("setCursor(300,200)", display.calls.get(2));
    }

    @Test
    void parkingACursorOnEitherAxisStillParksAndRestores() {
        FakeDisplay display = new FakeDisplay();
        display.cursorX = 0;
        display.cursorY = 77;
        GrugRunWindow window = new GrugRunWindow(display);

        window.parkCursor();
        assertEquals(calls("setCursor(0,0)"), display.calls);

        window.endRun();
        assertEquals(0, display.cursorX);
        assertEquals(77, display.cursorY);
    }

    @Test
    void parkingAnAlreadyParkedCursorDoesNotCallTheDisplayAgain() {
        FakeDisplay display = new FakeDisplay();
        display.cursorX = 0;
        display.cursorY = 0;
        GrugRunWindow window = new GrugRunWindow(display);

        window.parkCursor();
        assertTrue(display.calls.isEmpty());

        // Finishing still runs the restore the way every loader always did; with the cursor at
        // the corner that is a no-op move, but the call still lands.
        window.endRun();
        assertEquals(calls("setCursor(0,0)"), display.calls);
    }

    @Test
    void aFractionalCursorRestoresExactly() {
        FakeDisplay display = new FakeDisplay();
        display.cursorX = 300.5;
        display.cursorY = 200.25;
        GrugRunWindow window = new GrugRunWindow(display);

        window.parkCursor();
        assertEquals(calls("setCursor(0,0)"), display.calls);

        // GLFW reports fractional positions, and the position saved before the park comes back
        // exactly, not rounded to whole pixels.
        window.endRun();
        assertEquals("setCursor(300.5,200.25)", display.calls.get(1));
        assertEquals(300.5, display.cursorX);
        assertEquals(200.25, display.cursorY);
    }
}
