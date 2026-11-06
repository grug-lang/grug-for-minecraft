package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugClientClicks;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Notes that the integrated server has handled a click packet, which is the moment a single-player
 * client's runner may run the tick that asserts on the click (#1094). The packet's own handler runs
 * on the server thread, after the client's press returned, and {@link GrugClientClicks} holds the
 * runner's next tick until this ran; see that class for why.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {
    @Inject(method = "handleContainerClick", at = @At("RETURN"))
    private void grug$noteClickApplied(
            ServerboundContainerClickPacket packet, CallbackInfo callback) {
        GrugClientClicks.applied();
    }
}
