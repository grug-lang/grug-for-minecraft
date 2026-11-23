package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.client.GrugDriftDiagClient;
import net.minecraft.client.entity.mob.player.LocalClientPlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DIAGNOSTIC ONLY (branch diag-a1-drift): reports each position the local player sends. */
@Mixin(LocalClientPlayerEntity.class)
public abstract class DriftDiagLocalPlayerMixin {
    @Shadow private double sentX;
    @Shadow private double sentMinY;
    @Shadow private double sentY;
    @Shadow private double sentZ;

    @Inject(method = "sendMovement", at = @At("RETURN"))
    private void grug$diagSent(CallbackInfo ci) {
        GrugDriftDiagClient.sent(sentX, sentMinY, sentY, sentZ, true);
    }
}
