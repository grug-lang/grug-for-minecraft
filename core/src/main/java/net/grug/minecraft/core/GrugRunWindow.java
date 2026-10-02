package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugScreenshots;

/**
 * The window and GL state a screenshot test run takes over, and who puts it back.
 *
 * <p>Every loader's client tick hook (the {@code GrugClientHooks} classes and 1.20.6's {@code
 * ClientForgeEvents}) owns the same three save/restore contracts: the window is held at the
 * screenshot resolution by whoever forced it, and only that one restores it; the run switched off
 * GL dithering, and only that run switches it back on; the run parked the cursor in a corner, and
 * only that run puts it back. Each of those is a small state machine that until now lived inside a
 * class a headless CI cannot drive. The loader hooks now keep only the loader-specific half of each
 * call (which LWJGL, GLFW or Forge window call does the resize, and how a key poll reads) and drive
 * this class, so the transitions are measured from core's JUnit tests.
 *
 * <p>{@link Display} is the seam: a loader implements it against its own window and GL API, a test
 * implements it with a recording fake. The run always happens at the resolution the references were
 * captured at, on every loader, so that resolution comes from {@link GrugScreenshots} rather than
 * each hook.
 */
public final class GrugRunWindow {

    /**
     * The windowing and GL side the state machine drives.
     *
     * <p>One call maps to the loader's own window and GL API, so the same transitions run on
     * LWJGL's Display, GLFW and Forge's window unchanged. A loader that does not manage one of
     * these (1.20.6 never touches GL dithering) implements the read as "off" and the write as a
     * no-op, which leaves the state machine's behaviour there bit for bit what the loader had.
     */
    public interface Display {
        /** The width, in pixels, the window is at now. */
        int width();

        /** The height, in pixels, the window is at now. */
        int height();

        /** Resizes the window to the given size. */
        void setSize(int width, int height);

        /** Whether GL dithering is on. */
        boolean ditherOn();

        /** Switches GL dithering on or off. */
        void setDither(boolean on);

        /** The cursor's x position, in window pixels. */
        int cursorX();

        /** The cursor's y position, in window pixels. */
        int cursorY();

        /** Moves the cursor to the given window-pixel position. */
        void setCursor(int x, int y);
    }

    /**
     * One poll of the hotkeys.
     *
     * <p>A call reports what is pressed and consumes that report. A press that a skipped tick never
     * observed stays pending in the key port, so the first tick that observes it is the one that
     * acts on it, and a press never fires twice. The loaders' polls already work this way (1.2.5
     * keeps the press pending until {@code isPressed()} consumes it, and 1.20.6's {@code
     * KeyMapping.consumeClick} is sticky by design), and this machine's only requirement is that
     * the poll it observes in a tick is the poll it acts on, in that same tick.
     */
    public interface Keys {
        /** Whether the run-tests key is pressed. Consumes the press it reports. */
        boolean runPressed();

        /** Whether the force-resolution key is pressed. Consumes the press it reports. */
        boolean forceResolutionPressed();
    }

    private final Display display;

    /** True exactly while the window is held at the test resolution, whoever forced it. */
    private boolean resolutionForced = false;

    /** The size to restore, behind {@link #resolutionForced}. */
    private int savedWidth = 0;

    private int savedHeight = 0;

    /** True only while R (not M) is the reason the window is forced, so only R restores it. */
    private boolean resolutionForcedByTestRun = false;

    /** True only while a run is in progress; beginRun() flips it, and a run starts exactly once. */
    private boolean runActive = false;

    /** Whether GL dithering was on before a run turned it off, so it can be put back. */
    private boolean ditherWasEnabled = false;

    /** True only while a run has the cursor parked, so only that run restores it. */
    private boolean cursorParked = false;

    private int savedCursorX = 0;

    private int savedCursorY = 0;

    public GrugRunWindow(Display display) {
        this.display = display;
    }

    /**
     * One tick of hotkey input. Returns true exactly when this tick's input starts a test run, in
     * which case the caller owns creating the runner; the window side of that start is already done
     * by the time this returns.
     *
     * <p>Both polls are always consumed, whether or not they trigger anything: that is what keeps a
     * skipped tick from losing a press, and a poll from acting on an old one.
     */
    public boolean tick(Keys keys) {
        boolean started = keys.runPressed() && beginRun();
        if (keys.forceResolutionPressed()) {
            toggleResolution();
        }
        return started;
    }

    /**
     * Sets up the window for a run and reports whether a run can start.
     *
     * <p>While a run is in progress the press is absorbed, which is what keeps a second R from
     * restarting one. If the window is already held at the test resolution because M forced it,
     * this run did not set that up, so it does not restore it either: the window stays put, and a
     * second M press is still the way back.
     */
    public boolean beginRun() {
        if (runActive) {
            return false;
        }

        // Screenshot tests only compare equal at the resolution their reference was captured at,
        // so a run always happens at that resolution.
        if (!resolutionForced) {
            savedWidth = display.width();
            savedHeight = display.height();
            resolutionForced = true;
            resolutionForcedByTestRun = true;
            display.setSize(GrugScreenshots.WIDTH, GrugScreenshots.HEIGHT);
        }

        // OpenGL dithers by default, which puts +/-1 noise on a GUI-sized crop and moves it around
        // between runs. That is the difference between a pixel-exact comparison and one that can
        // never pass, so the run turns it off and remembers to put it back.
        ditherWasEnabled = display.ditherOn();
        display.setDither(false);

        runActive = true;
        return true;
    }

    /**
     * Puts the window and the GL state back the way a run found them, and only if that run changed
     * them.
     */
    public void endRun() {
        runActive = false;

        if (resolutionForcedByTestRun) {
            resolutionForced = false;
            resolutionForcedByTestRun = false;
            display.setSize(savedWidth, savedHeight);
        }

        if (ditherWasEnabled) {
            display.setDither(true);
        }
        ditherWasEnabled = false;

        if (cursorParked) {
            cursorParked = false;
            display.setCursor(savedCursorX, savedCursorY);
        }
    }

    /**
     * M: toggles the window between its normal size and the test resolution, whoever forced it.
     *
     * <p>Leaving {@code resolutionForcedByTestRun} false on both branches is what keeps a following
     * run from restoring a window M owns: a run only ever puts back what it forced itself.
     */
    public void toggleResolution() {
        if (!resolutionForced) {
            savedWidth = display.width();
            savedHeight = display.height();
            resolutionForced = true;
            resolutionForcedByTestRun = false;
            display.setSize(GrugScreenshots.WIDTH, GrugScreenshots.HEIGHT);
        } else {
            resolutionForced = false;
            resolutionForcedByTestRun = false;
            display.setSize(savedWidth, savedHeight);
        }
    }

    /**
     * True while the window is held at the test resolution, whoever forced it. The cursor readout
     * the loaders print while sitting in that window keys off this.
     */
    public boolean resolutionForced() {
        return resolutionForced;
    }

    /**
     * Parks the cursor in the window's corner for the duration of a run, remembering where it was.
     *
     * <p>A captured frame must not depend on where the invisible cursor happens to be: the loaders
     * paint a highlight over the slot under the mouse and a tooltip follows it, so a screenshot
     * would record whichever slot the user last hovered. The window corner is outside every centred
     * GUI, and the cursor itself never lands in the framebuffer.
     *
     * <p>The saved position is captured on the first call, so a re-entry, or a window manager
     * moving the cursor back, does not overwrite the position to restore. The call still re-parks
     * while the cursor has left the corner, because the game re-reads the position for every frame.
     */
    public void parkCursor() {
        if (!cursorParked) {
            savedCursorX = display.cursorX();
            savedCursorY = display.cursorY();
            cursorParked = true;
        }
        if (display.cursorX() != 0 || display.cursorY() != 0) {
            display.setCursor(0, 0);
        }
    }
}
