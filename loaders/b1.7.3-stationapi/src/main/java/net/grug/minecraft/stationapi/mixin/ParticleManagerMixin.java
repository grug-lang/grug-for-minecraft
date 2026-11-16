package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.stationapi.GrugClientHooks;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Drops every particle a run does not want in its captures, as the Ornithe loaders' mixin of the
 * same name does.
 *
 * <p>Beta has no particle setting to put at minimal, so a run turns particles off at the one choke
 * point where the game adds every particle it draws. The switch belongs to the run (the window's
 * run state), so a client-only run and a joined client's control run drop them alike. Before this,
 * only a grug block's {@code add_particle} was dropped, and only by checking {@code GRUG_CI}, which
 * the joined client of the multiplayer job runs without: its capture of {@code
 * redstone_engine_green} then held the engine's redstone dust, 1104 of 34100 px (#1161).
 */
@GrugGenerated(
        "a mixin into the game's particle manager: no JUnit test can reach a drawn frame, and the"
                + " dedicated leg's redstone_engine capture is its proof")
@Mixin(ParticleManager.class)
public class ParticleManagerMixin {

    @Shadow private List<Particle>[] particles;

    /**
     * Drops the particles that were already alive when the run took the window, once: the switch
     * stops new ones only, and a particle outlives the 20-tick floor of a capture. This is the
     * manager's per-tick entry, which this version names {@code removeDeadParticles}.
     */
    @Inject(method = "removeDeadParticles", at = @At("HEAD"))
    private void grug$clearParticlesAliveAtRunStart(CallbackInfo ci) {
        if (GrugClientHooks.takeParticleClear()) {
            for (List<Particle> layer : particles) {
                layer.clear();
            }
        }
    }

    @Inject(method = "addParticle", at = @At("HEAD"), cancellable = true)
    private void grug$dropParticleDuringRun(Particle particle, CallbackInfo ci) {
        if (GrugClientHooks.isParticleMinimised()) {
            ci.cancel();
        }
    }
}
