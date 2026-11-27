package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.core.GrugConnectAddress;
import net.grug.minecraft.core.GrugControlClient;
import net.grug.minecraft.grug.GrugOwnedScreen;
import net.grug.minecraft.grug.GrugOwnedScreens;
import net.grug.minecraft.stationapi.GrugClientAccess;
import net.grug.minecraft.stationapi.GrugClientHooks;
import net.grug.minecraft.stationapi.client.GrugOverlayHost;
import net.grug.minecraft.stationapi.client.GrugOwnedScreenHost;
import net.grug.minecraft.stationapi.control.GrugControlStationClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.Timer;
import net.minecraft.entity.player.ClientPlayerEntity;

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
    @Shadow private Timer timer;

    @Shadow
    public abstract void scheduleStop();

    // Minecraft.resize() is private, so it can't be called directly. Java forbids "private
    // abstract", and a shadow only has to be at least as visible as its target, so this is
    // declared protected.
    @Shadow
    protected abstract void resize(int width, int height);

    // The game's own way of joining a server on startup, which its launcher arguments reach.
    @Shadow
    public abstract void setStartupServer(String address, int port);

    // The per-tick logic lives in GrugClientHooks so JaCoCo can measure it; see that class.
    @Unique
    private final GrugClientHooks grug$hooks =
            new GrugClientHooks(
                    (Minecraft) (Object) this, this, "Minecraft Beta 1.7.3 - StationAPI with grug");

    /**
     * Joins the server named by GRUG_CI_CONNECT ("host:port") as the game starts, which is how the
     * dedicated-server job (#165) puts a real client on the server it drives the tests from. The
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

    @Inject(method = "tick", at = @At("RETURN"))
    private void grug$afterClientTick(CallbackInfo ci) {
        GrugControlStationClient.afterClientTick((Minecraft) (Object) this);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTick(CallbackInfo ci) {
        // A screen the game took away reports itself on the next tick, so a screen_closed that
        // opens another screen runs after the game finished replacing that one.
        GrugOwnedScreens.runGameRemovals();
        net.grug.minecraft.stationapi.StationApiAdapter.holdPlayerForTest(this.player);
        grug$hooks.tick();
        // Drain control messages on the game thread, because the packet reader thread is not the
        // thread that owns the GL context and the frame (#1465).
        GrugControlClient.drainPendingMessages();
        GrugControlStationClient.onClientTick((Minecraft) (Object) this, grug$hooks);
        GrugOverlayHost.pollWorldKeys((Minecraft) (Object) this);
    }

    /**
     * Ends an open mod screen before the world it draws over is replaced, so its {@code
     * screen_closed} runs while the old world is still readable, and takes the host away with it so
     * a closed screen neither draws over the new world nor takes input meant for it.
     *
     * <p>The hook is on the three-argument overload, which the two shorter ones call, so one world
     * change ends a screen once rather than three times.
     */
    @Inject(
            method =
                    "setWorld(Lnet/minecraft/world/World;Ljava/lang/String;"
                            + "Lnet/minecraft/entity/player/PlayerEntity;)V",
            at = @At("HEAD"))
    private void grug$worldChanged(CallbackInfo ci) {
        GrugOwnedScreen open = GrugOwnedScreens.openScreen();
        GrugOwnedScreens.worldUnloaded();
        // This version's setWorld leaves the open screen in place, so the host whose model just
        // closed is taken away here as well. A world change the game itself ends with setScreen
        // has already removed it, which leaves nothing for this check to take.
        if (open == null) return;
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.currentScreen instanceof GrugOwnedScreenHost
                && ((GrugOwnedScreenHost) minecraft.currentScreen).model() == open) {
            minecraft.setScreen(null);
        }
    }

    /**
     * Declines the focus-loss pause while a screenshot test run owns the client.
     *
     * <p>A headless display is never the active window, so this version's {@code
     * GameRenderer.onFrameUpdate} opens the in-game menu half a second into every run and pauses
     * the world behind it. That costs more than the menu over a capture: a paused world stops
     * ticking the block entities the tests placed, so every test after the one that closed its
     * screen meets a frozen world. The pause cannot be undone from a tick either, because the tick
     * that would close the menu does not run while the game is paused. The run owning the client is
     * what makes refusing the pause correct rather than intrusive; the R hotkey leaves the game
     * running and does not.
     *
     * <p>See #195.
     */
    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void grug$declinePauseDuringRun(CallbackInfo ci) {
        if (grug$hooks.isTestRunActive()) {
            ci.cancel();
        }
    }

    @Override
    public void grug$sendChat(String line) {
        this.player.sendMessage(line);
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
        this.scheduleStop();
    }
}
