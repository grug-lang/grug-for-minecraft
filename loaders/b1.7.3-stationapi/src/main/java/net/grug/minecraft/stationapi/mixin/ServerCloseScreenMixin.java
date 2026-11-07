package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ignores a close of a screen handler the player no longer has open, so a server-opened GUI
 * survives the client's own close of the one before it (#1098).
 *
 * <p>{@code GUI.open} closes the player's previous handler first, which sends that client a {@code
 * CloseScreenS2CPacket}. The client applies it by calling {@code
 * MultiplayerClientPlayerEntity.closeHandledScreen()}, which sends the same packet back with the id
 * of the handler it is closing. When a test closes a screen and opens the next in one tick, that
 * echo reaches this server after the new handler is installed, and this version's handler calls
 * {@code onHandledScreenClosed()} without comparing any id: the new handler is gone before its
 * screen arrives, and every later click names a window this server no longer has, so {@code
 * onClickSlot}'s own id check drops it. Comparing the id here drops only the stale echo, and a
 * close of the handler that is actually open still closes it: Escape on a grug screen, the player's
 * own inventory, and the close the server's own {@code closeHandledScreen} sends all name the id
 * the handler has.
 *
 * <p>Later versions of the game send no such echo: their client closes a server-closed screen with
 * {@code clientSideCloseContainer}, which sends nothing, so only this generation has the hole.
 */
@GrugGenerated("a mixin into the game's server handler: the game runs it")
@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerCloseScreenMixin {

    @Shadow private ServerPlayerEntity player;

    @Inject(method = "onCloseScreen", at = @At("HEAD"), cancellable = true)
    private void grug$ignoreStaleClose(CloseScreenS2CPacket packet, CallbackInfo ci) {
        if (player.currentScreenHandler != null
                && player.currentScreenHandler.syncId != packet.syncId) {
            ci.cancel();
        }
    }
}
