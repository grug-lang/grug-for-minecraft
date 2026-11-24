package net.grug.minecraft.forge;

/**
 * The history of the press before this one, which a container screen keeps to recognise a double
 * click and which a test's press has to start without.
 *
 * <p>{@code AbstractContainerScreen.mouseClicked} reads a press as the second half of a double
 * click when it lands on the same slot with the same button within 250 milliseconds, and {@code
 * mouseReleased} then sends {@code ClickType.PICKUP_ALL} instead of the click the press scheduled.
 * A test's presses are independent: two of them in one tick are microseconds apart, so that window
 * is open by construction, and a PICKUP_ALL gathers every matching stack in the container onto the
 * cursor, which no test asked for. The mixin next to this interface is the implementation. See
 * #1666.
 */
public interface GrugScreenClickHistory {
    /** Forgets the previous press, as a screen that has never been pressed answers. */
    void grug$forgetPreviousPress();
}
