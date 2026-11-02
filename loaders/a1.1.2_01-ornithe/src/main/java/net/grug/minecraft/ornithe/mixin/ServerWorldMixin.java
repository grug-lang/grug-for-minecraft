package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ticks the dedicated server's test run from its world. Listed under {@code "server"} in its
 * config, so a client, which ticks its world from its own tick, never applies it. See {@link
 * GrugServerRun}.
 */
@GrugGenerated("a mixin into the game's world tick on a dedicated server: the game runs it")
@Mixin(World.class)
public abstract class ServerWorldMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void grug$onServerWorldTick(CallbackInfo ci) {
        GrugServerRun.onWorldTick((World) (Object) this);
    }
}
