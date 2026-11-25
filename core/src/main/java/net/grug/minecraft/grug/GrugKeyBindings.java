package net.grug.minecraft.grug;

import java.util.HashSet;
import java.util.Set;

/**
 * Turns the held state of a mod's declared key bindings into presses, the way {@link GrugWorldKeys}
 * turns the held state of a raw letter or digit key into one.
 *
 * <p>A binding is the game's own key, so its press is polled from whatever the loader reads for it:
 * the game's own key binding object where the Controls screen lists it, and the declared default
 * key where it does not. Either way a press is reported once, on the first poll that sees the
 * binding down, and a binding held across polls is not a press again, so a toggle flips once per
 * press and not once per tick. The state is kept on every poll, a poll made while a screen was open
 * included, so a key still held when the screen closes is not reported as a press the player never
 * made.
 */
public final class GrugKeyBindings {
    /** The bindings whose state the current polls have seen, by {@code mod/binding}. */
    private final Set<String> down = new HashSet<>();

    /**
     * Records the binding's state for this poll and answers whether the export is to be asked: the
     * binding just went down and no screen is open.
     *
     * @param mod the mod that declared the binding
     * @param id the binding's id within that mod
     * @param isDown whether the binding is held now, with the key the game has it bound to
     * @param screenClosed whether no screen is open and a world is shown
     */
    public boolean deliver(String mod, String id, boolean isDown, boolean screenClosed) {
        String name = mod + '/' + id;
        boolean was = down.contains(name);
        if (isDown) {
            down.add(name);
        } else {
            down.remove(name);
        }
        return isDown && !was && screenClosed;
    }
}
