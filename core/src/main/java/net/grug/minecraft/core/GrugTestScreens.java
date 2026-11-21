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

    /**
     * Whether a forwarded capture closes {@code screen} before it frames the world: there is one
     * and it is not an instance of a class in {@code kept}, the screens a server opened for the
     * test's player, which are the capture's subject. The player's own inventory is one of them,
     * because {@code open_inventory} on a dedicated server opens it on the joined client and a
     * capture that closed it drew the world behind it (the item list's icons were missing).
     *
     * @param screen the game's current screen, or null when it has none
     * @param kept the classes of the screens a test opened and a capture must leave open
     */
    public static boolean closesBeforeCapture(Object screen, Class<?>... kept) {
        if (screen == null) {
            return false;
        }
        for (Class<?> keep : kept) {
            if (keep.isInstance(screen)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The identity number of {@code screen}, which must not be null: the unsigned 32 bits of its
     * identity hash, doubled, with the low bit set. A screen object keeps its number for as long as
     * it exists, and a screen that replaced another one while the panel stayed open answers another
     * number, so a caller that remembered a screen's measurements can tell it is looking at a
     * different screen (#1289). {@link #isOpen} is what says whether the screen counts, for a
     * loader with an empty screen of its own.
     */
    public static long identityOf(Object screen) {
        return identity(System.identityHashCode(screen));
    }

    /**
     * The number {@link #identityOf} gives a screen whose identity hash is {@code hash}. The hash
     * is masked to its unsigned 32 bits first, because {@link System#identityHashCode} is negative
     * for about half of all objects and a negative answer would fail the server's check; the result
     * is therefore always in {@code [1, 2^33 - 1]}, which a double holds exactly and which is never
     * 0, the number that says no screen is open.
     */
    static long identity(int hash) {
        return (((long) hash) & 0xFFFFFFFFL) << 1 | 1L;
    }
}
