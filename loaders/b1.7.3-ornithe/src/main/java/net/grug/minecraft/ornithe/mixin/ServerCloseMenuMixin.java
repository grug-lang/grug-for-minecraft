package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.network.packet.CloseInventoryMenuPacket;
import net.minecraft.server.entity.mob.player.ServerPlayerEntity;
import net.minecraft.server.network.handler.ServerPlayNetworkHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ignores a close of a menu the player no longer has open, so a server-opened GUI survives the
 * client's own close of the one before it (#1098).
 *
 * <p>{@code GUI.open} closes the player's previous menu first, as the comment there says, which
 * sends that client a {@code CloseInventoryMenuPacket}. The client applies it by calling {@code
 * LocalClientPlayerEntity.closeMenu()}, which sends the same packet back with the id of the menu it
 * is closing. When a test closes a screen and opens the next in one tick, that echo reaches this
 * server after the new menu is installed, and this version's handler calls {@code doCloseMenu()}
 * without comparing any id: the new menu is gone before its screen arrives, and every later click
 * names a window this server no longer has, so {@code handleInventoryMenuClickSlot}'s own id check
 * drops it. Comparing the id here drops only the stale echo, and a close of the menu that is
 * actually open still closes it: Escape on a grug screen, the player's own inventory, and the close
 * the server's own {@code closeMenu} sends all name the id the menu has.
 *
 * <p>Later versions of the game send no such echo: their client closes a server-closed screen with
 * {@code clientSideCloseContainer}, which sends nothing, so only this generation has the hole.
 */
@GrugGenerated("a mixin into the game's server handler: the game runs it")
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerCloseMenuMixin {

    @Shadow private ServerPlayerEntity player;

    @Inject(method = "handleCloseInventoryMenu", at = @At("HEAD"), cancellable = true)
    private void grug$ignoreStaleClose(CloseInventoryMenuPacket packet, CallbackInfo ci) {
        if (player.menu != null && player.menu.networkId != packet.id) {
            ci.cancel();
        }
    }
}
