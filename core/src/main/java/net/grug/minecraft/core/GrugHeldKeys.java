package net.grug.minecraft.core;

import java.util.HashSet;
import java.util.Set;

/**
 * The keys a screen reports as held, so a second press of one is delivered as a key repeat.
 *
 * <p>The game reports a repeat as another press event for a key that is still down, and a {@code
 * ScreenEvent.KeyPressed.Pre} carries no repeat bit of its own. The loader therefore has to
 * remember which keys it has seen pressed: one integer was enough only while one key could be held,
 * and a press of a second key overwrote the first, so a still-held key then reported {@code repeat}
 * false where the documented contract says true (#1850).
 *
 * <p>A release removes only the key it names, because another key can still be held, and an
 * eligible screen that opens starts with no key held. Pure, so its unit test covers two keys at
 * once without a game.
 */
public final class GrugHeldKeys {
    private final Set<Integer> held = new HashSet<>();

    /**
     * Records a press of {@code key} and answers whether it was already held, which is the repeat
     * the game reports for a key that is still down.
     */
    public boolean press(int key) {
        return !held.add(key);
    }

    /** Records the release of {@code key}; every other held key stays held. */
    public void release(int key) {
        held.remove(key);
    }

    /** Forgets every held key, which is what a screen that opens starts with. */
    public void clear() {
        held.clear();
    }
}
