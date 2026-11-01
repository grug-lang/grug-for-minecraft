package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugCatchUpPings;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the client's answer to the test boundary's ping, which vanilla's {@code handlePong} drops
 * on the floor.
 *
 * <p>The separate client of 1.20.6 learns of the room build's clearing from packets, and the test
 * boundary proves it has by sending a ping after the clearing and waiting for the pong. The answer
 * has to be seen on the server, and vanilla's handler is empty because a ping's only vanilla use is
 * the client's own latency readout. See {@link GrugCatchUpPings} and #836.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerPongMixin {

    @Inject(method = "handlePong", at = @At("HEAD"))
    private void grug$noteCatchUpPong(ServerboundPongPacket packet, CallbackInfo ci) {
        GrugCatchUpPings.notePong(packet.getId());
    }
}
