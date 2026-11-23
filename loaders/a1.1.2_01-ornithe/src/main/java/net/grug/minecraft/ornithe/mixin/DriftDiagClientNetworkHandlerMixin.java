package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.client.GrugDriftDiagClient;
import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.packet.PlayerMovePacket;
import net.ornithemc.osl.lifecycle.api.client.MinecraftInstance;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DIAGNOSTIC ONLY (branch diag-a1-drift). */
@Mixin(ClientNetworkHandler.class)
public abstract class DriftDiagClientNetworkHandlerMixin {
    @Inject(method = "handlePlayerMove", at = @At("HEAD"))
    private void grug$diagBefore(PlayerMovePacket packet, CallbackInfo ci) {
        GrugDriftDiagClient.beforeHandle(MinecraftInstance.get(), packet);
    }

    @Inject(method = "handlePlayerMove", at = @At("RETURN"))
    private void grug$diagAfter(PlayerMovePacket packet, CallbackInfo ci) {
        GrugDriftDiagClient.afterHandle(MinecraftInstance.get());
    }
}
