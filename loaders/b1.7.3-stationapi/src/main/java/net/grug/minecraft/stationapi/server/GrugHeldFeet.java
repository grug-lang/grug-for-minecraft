package net.grug.minecraft.stationapi.server;

import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugServerRunArms;

/**
 * The feet height a b1.7.3 dedicated run holds its test player at, and the height a snap saved for
 * the player to be put back at when the test ends.
 *
 * <p>Whether the run holds a height yet is its own flag rather than a height of 0: a test may move
 * the player to feet 0, and 0 is then a height the run holds like any other. Reading a zero as "no
 * height held yet" dropped that move and every later one, and skipped the height arm of the hold
 * (#1847).
 *
 * <p>{@link #moveTo} is what a test's {@code set_player_position} does. It changes the held height
 * so the hold follows the test, and {@link #saveForTest} keeps the height the run held before the
 * test: the next test's room is built around that height, so a test that moved the player from feet
 * 70 to 72 would otherwise have its successor dragged back to 72 before its room was built (#1847).
 * {@link #restoreAfterTest} puts the saved height back.
 */
public final class GrugHeldFeet {
    private boolean held;
    private double feetY;
    private boolean restorePending;
    private double savedFeetY;

    /** Starts holding the run at {@code feetY}, rounded to a millionth. */
    public void hold(double feetY) {
        held = true;
        this.feetY = GrugServerRunArms.roundedHeight(feetY);
    }

    /** Whether the run holds a height, which it does even when that height is 0. */
    public boolean isHeld() {
        return held;
    }

    /** The held feet height, 0 before the run holds one; a held 0 is a real height. */
    public double feetY() {
        return feetY;
    }

    /** Tracks a test's move to {@code feetY}, and changes nothing before the run holds a height. */
    public void moveTo(double feetY) {
        if (held) {
            this.feetY = GrugServerRunArms.roundedHeight(feetY);
        }
    }

    /**
     * Saves the held height for the player a snap is about to save, so the test's move can be
     * undone.
     */
    public void saveForTest() {
        restorePending = true;
        savedFeetY = feetY;
    }

    /** Puts the height {@link #saveForTest} saved back, and changes nothing when none was saved. */
    public void restoreAfterTest() {
        if (restorePending) {
            feetY = savedFeetY;
            restorePending = false;
        }
    }

    /**
     * The feet a room is built around: never below the held height, and {@code feetY} itself before
     * the run holds one.
     */
    public double buildAnchor(double feetY) {
        return held ? GrugPlayerFloor.buildAnchor(feetY, this.feetY) : feetY;
    }
}
