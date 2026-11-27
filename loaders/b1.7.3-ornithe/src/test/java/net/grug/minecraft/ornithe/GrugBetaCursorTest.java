package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The cursor write of {@code TestTickContext.set_carried_item} on Beta (#1850).
 *
 * <p>{@code ModLoaderAdapter.setCarriedItem} documents a null item as clearing the cursor, but a
 * grug call cannot pass one: the API takes an {@code Item}, not an option. The arm is therefore
 * latent, and a grug test in {@code mods/coverage/} cannot reach it. Beta's {@code ItemStack(Item)}
 * reads {@code item.id}, so a null item used to throw rather than clear.
 *
 * <p>The stack for a real item is not built here: Beta's own {@code Item} initialization needs the
 * game's statistics bootstrap, which a unit test has no game for. The clear arm touches no item at
 * all, which is exactly what it proves.
 */
class GrugBetaCursorTest {

    @Test
    void aNullItemIsAClearedCursorRatherThanAStack() {
        assertNull(
                ServerOrnitheAdapter.cursorStack(null),
                "a null item has to clear the cursor rather than build a stack");
    }
}
