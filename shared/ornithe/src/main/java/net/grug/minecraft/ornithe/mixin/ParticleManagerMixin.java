package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.minecraft.client.ParticleManager;
import net.minecraft.client.entity.particle.Particle;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops every particle a run does not want in its captures.
 *
 * <p>Alpha and Beta have no particle setting to put at minimal, so a run turns particles off at
 * this one choke point, where the game adds every particle it draws: the world's own spawn ends
 * here, and so does a grug block's {@code add_particle} (#581). The switch belongs to the run, so a
 * client-only and a joined run drop them alike, and ordinary play adds them as it did before
 * (#1213).
 */
@GrugGenerated(
        "a mixin into the game's particle manager: no JUnit test can reach a drawn frame, and the"
                + " a1 capture dispatch is its proof")
@Mixin(ParticleManager.class)
public class ParticleManagerMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void grug$dropParticleDuringRun(Particle particle, CallbackInfo ci) {
        if (GrugClientHooks.isParticleMinimised()) {
            ci.cancel();
        }
    }
}
