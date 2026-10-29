package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugGameClock;
import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the client tick faster than 20 times a second when a test asked for it.
 *
 * <p>The client's {@code Timer} takes the length of a tick, in milliseconds, from an operator that
 * is this method: the larger of the usual 50 and what the server's tick rate manager reports, so a
 * server that ticks faster, as {@code /tick rate} makes it, never makes the client tick faster. The
 * runner is driven from the client's tick, so without this the test runner would run 20 times a
 * second while the world ran many times faster, and a test would count fewer ticks than the game
 * ran. Dividing the answer keeps the two on one clock. The timer still hands out at most 10 ticks
 * for a frame.
 */
@Mixin(Minecraft.class)
public class MinecraftTimerMixin {
    @Inject(method = "getTickTargetMillis(F)F", at = @At("RETURN"), cancellable = true)
    private void grug$scaleTickLength(float millis, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(cir.getReturnValueF() / GrugGameClock.speed);
    }
}
