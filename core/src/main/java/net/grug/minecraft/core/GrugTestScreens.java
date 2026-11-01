package net.grug.minecraft.core;

/**
 * What a test sees of the game's screens.
 *
 * <p>A run always has a screen: the runner displays its own empty screen before the first test and
 * {@code close_screen} puts that back, so the loader's "there is no screen at all" arm is not one a
 * test can reach. The comparison lives here, where a unit test covers both sides, and the loader
 * only passes the screen and the class of the empty one.
 */
public final class GrugTestScreens {
    private GrugTestScreens() {}

    /**
     * Whether a screen a test can see is up: there is one, and it is not the runner's own empty
     * screen.
     *
     * @param screen the game's current screen, or null when it has none
     * @param emptyScreen the runner's empty screen class, which does not count as open
     */
    public static boolean isOpen(Object screen, Class<?> emptyScreen) {
        return screen != null && !emptyScreen.isInstance(screen);
    }
}
