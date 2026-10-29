package net.grug.minecraft.forge.mixin;

import net.grug.minecraft.forge.GrugGameClock;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Holds each tick of the integrated server back until the client has made as many, while a test
 * runs the game's clock faster than real time, so the world stays on the runner's clock. See {@code
 * GrugTickLockstep} for why and how.
 *
 * <p>The server loop schedules its ticks at the rate its tick rate manager holds and warns that it
 * "can't keep up" when it falls two seconds behind that schedule, which a server held back by the
 * client does by design. The schedule is therefore moved up to now after the wait, so the wait is
 * not a lag.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickMixin {
    @Shadow protected long nextTickTimeNanos;

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void grug$followClient(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (GrugGameClock.speed <= 1.0F) return;
        GrugGameClock.LOCKSTEP.awaitClient();
        long now = Util.getNanos();
        if (this.nextTickTimeNanos < now) {
            this.nextTickTimeNanos = now;
        }
    }
}
