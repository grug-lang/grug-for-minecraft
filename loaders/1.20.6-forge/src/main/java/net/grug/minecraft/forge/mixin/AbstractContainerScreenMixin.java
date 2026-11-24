package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugScreenClickHistory;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets a test's press forget the one before it, so the screen cannot read it as the second half of
 * a double click.
 *
 * <p>The three fields are what {@code mouseClicked} answers {@code doubleclick} from, and the
 * answer is computed at every press from the one before it, so clearing them before the press is
 * what a screen that had never been pressed answers. Only a test's press clears them: a real
 * player's double click keeps the gather it has always sent. See #1666.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin implements GrugScreenClickHistory {

    @Shadow private Slot lastClickSlot;

    @Shadow private long lastClickTime;

    @Shadow private int lastClickButton;

    @Override
    public void grug$forgetPreviousPress() {
        this.lastClickSlot = null;
        this.lastClickTime = 0L;
        this.lastClickButton = 0;
    }
}
