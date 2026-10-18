package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.GrugClientAccess;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements GrugClientAccess {

    @Shadow public ClientPlayerEntity player;

    @Shadow
    public abstract void shutdown();

    // Minecraft.resize() is private, so it can't be called directly. Java forbids "private
    // abstract", and a shadow only has to be at least as visible as its target, so this is
    // declared protected.
    @Shadow
    protected abstract void resize(int width, int height);

    // The per-tick logic lives in GrugClientHooks so JaCoCo can measure it; see that class.
    @Unique
    private final GrugClientHooks grug$hooks =
            new GrugClientHooks(
                    (Minecraft) (Object) this, this, "Minecraft Beta 1.7.3 - Ornithe with grug");

    static {
        GrugClientHooks.registerKeybinds();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$onClientTick(CallbackInfo ci) {
        grug$hooks.tick();
    }

    /**
     * Declines the focus-loss pause while a screenshot test run owns the client.
     *
     * <p>When the window is not the active one, this version's frame update opens the in-game menu
     * half a second later and pauses the world behind it. That costs more than the menu over a
     * capture: a paused world stops ticking the block entities the tests placed, so every test
     * after the one that closed its screen meets a frozen world. The pause cannot be undone from a
     * tick either, because the tick that would close the menu does not run while the game is
     * paused. A run owning the client is what makes refusing the pause correct rather than
     * intrusive; the R hotkey leaves the game running and does not.
     *
     * <p>CI's xvfb window reports itself active, so the pause is not reached there and the closes
     * alone pass. This is the guard for a machine whose window is unfocused, and it is what
     * b1.7.3-stationapi already does. See #195.
     */
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void grug$declinePauseDuringRun(CallbackInfo ci) {
        if (GrugClientHooks.isTestRunActive()) {
            ci.cancel();
        }
    }

    @Override
    public void grug$sendChat(String line) {
        this.player.addMessage(line);
    }

    @Override
    public void grug$resize(int width, int height) {
        this.resize(width, height);
    }

    @Override
    public void grug$shutdown() {
        this.shutdown();
    }
}
