package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugConnectAddress;
import net.grug.minecraft.core.GrugMissingClientMod;
import net.grug.minecraft.ornithe.GrugClientAccess;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.grug.minecraft.ornithe.OrnitheAdapter;
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TickTimer;
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

    // The timer that decides how many ticks Minecraft.run takes for a frame, which is the game's
    // clock. Its tpsScale is public and multiplies the ticks per second.
    @Shadow private TickTimer timer;

    @Shadow public GameGui gui;

    @Shadow
    public abstract void shutdown();

    // The game's own way of joining a server on startup, which its launcher arguments reach.
    @Shadow
    public abstract void setStartupServer(String address, int port);

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
        // fabric.mod.json does not depend on osl-keybinds, because a dedicated server cannot load
        // it, so Fabric will not say that a client lacks it: this does (#1056).
        try {
            GrugClientHooks.registerKeybinds();
        } catch (NoClassDefFoundError e) {
            throw GrugMissingClientMod.fatal("osl-keybinds", OrnitheAdapter.CLIENT_NAME, e);
        }
    }

    /**
     * Joins the server named by GRUG_CI_CONNECT ("host:port") as the game starts, which is how the
     * dedicated-server job (#167) puts a real client on the server it drives the tests from. The
     * variable is unset in every other run, which then starts as it always did.
     */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void grug$connectOnStartup(CallbackInfo ci) {
        String connect = System.getenv("GRUG_CI_CONNECT");
        if (connect != null && !connect.isEmpty()) {
            GrugConnectAddress address = GrugConnectAddress.parse(connect);
            setStartupServer(address.host(), address.port());
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$onClientTick(CallbackInfo ci) {
        grug$hooks.tick();
        GrugControlAlpha.onClientTick((Minecraft) (Object) this, grug$hooks);
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
    public void grug$setGameSpeed(float speed) {
        this.timer.tpsScale = speed;
    }

    @Override
    public void grug$shutdown() {
        this.shutdown();
    }
}
