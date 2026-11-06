package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugClientClicks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes that the client is about to send a container click packet, so a single-player run's runner
 * can hold the tick that reads the click until the integrated server has handled it (#1094).
 *
 * <p>Only a press that actually sends the packet counts: the game ignores one whose container does
 * not match its own menu, and an overlay or a screen that takes the press sends nothing at all. The
 * wait is only asked of an integrated server's own test run; a joined client's clicks are ordered
 * by the control answer.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"))
    private void grug$noteClickSent(
            int containerId,
            int slotId,
            int button,
            ClickType clickType,
            Player player,
            CallbackInfo callback) {
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            return;
        }
        if (containerId != player.containerMenu.containerId) {
            return;
        }
        GrugClientClicks.expect();
    }
}
