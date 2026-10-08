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
 * <p>It is what {@code Test.close_screen()} displays on this loader, which is why it lives next to
 * the client hooks rather than in core: the game it works around is 1.2.5's, and no other loader
 * needs it.
 */
public class GrugEmptyScreen extends GuiScreen {

    @Override
    public boolean doesGuiPauseGame() {
        // The world must keep ticking: the tests that placed blocks and machines rely on the game
        // ticking them, and a screenshot of a paused world can never be the frame the test built.
        return false;
    }
}
