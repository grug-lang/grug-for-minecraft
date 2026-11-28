package net.grug.minecraft.forge125.client;

import net.minecraft.src.GuiScreen;

/**
 * A screen that draws nothing and does not pause the world.
 *
 * <p>A headless client is never the active window, and 1.2.5's {@code EntityRenderer} opens the
 * in-game menu half a second after {@code Display.isActive()} turns false. That makes {@code null}
 * the wrong "no screen" state here: the moment a test closes the last screen, the game puts its
 * menu up over the world, pauses the world behind it, and the screenshot a test takes records the
 * menu rather than the geometry it placed. This is the state instead: a screen is still open, so
 * the focus check has nothing to do, and {@link #doesGuiPauseGame()} is false, so the world keeps
 * ticking.
 *
 * <p>It is what {@code TestTickContext.close_screen()} displays on this loader, which is why it
 * lives next to the client hooks rather than in core: the game it works around is 1.2.5's, and no
 * other loader needs it.
 */
public class GrugEmptyScreen extends GuiScreen {

    /**
     * Whether the game handles the player's own mouse and key bindings behind this screen.
     *
     * <p>It stands in for no screen, so it takes the input the game handles when none is open: the
     * version's tick only polls its bindings, which is how the inventory binding opens its screen,
     * when no screen is open or the open one allows user input. With the default the game would
     * skip that section for as long as a test runs, and no key could ever open a screen from the
     * world (#1752). A real screen a test opens answers for itself.
     */
    public GrugEmptyScreen() {
        this.allowUserInput = true;
    }

    @Override
    public boolean doesGuiPauseGame() {
        // The world must keep ticking: the tests that placed blocks and machines rely on the game
        // ticking them, and a screenshot of a paused world can never be the frame the test built.
        return false;
    }
}
