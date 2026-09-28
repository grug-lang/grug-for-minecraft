package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.ornithe.GrugClientAccess;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.gui.GameGui;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements GrugClientAccess {

    @Shadow public ClientPlayerEntity player;

    @Shadow public GameGui gui;

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
                    (Minecraft) (Object) this,
                    this,
                    "Minecraft Alpha 1.1.2_01 - Ornithe with grug");

    static {
        GrugClientHooks.registerKeybinds();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$onClientTick(CallbackInfo ci) {
        grug$hooks.tick();
    }

    @Override
    public void grug$sendChat(String line) {
        if (this.gui != null) {
            this.gui.addChatMessage(line);
        }
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
