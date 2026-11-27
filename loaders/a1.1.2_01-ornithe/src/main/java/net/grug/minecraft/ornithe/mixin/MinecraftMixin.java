package net.grug.minecraft.ornithe.mixin;

import net.grug.minecraft.core.GrugConnectAddress;
import net.grug.minecraft.core.GrugMissingClientMod;
import net.grug.minecraft.grug.GrugOwnedScreen;
import net.grug.minecraft.grug.GrugOwnedScreens;
import net.grug.minecraft.ornithe.GrugClientAccess;
import net.grug.minecraft.ornithe.GrugClientHooks;
import net.grug.minecraft.ornithe.OrnitheAdapter;
import net.grug.minecraft.ornithe.block.entity.GrugSavedDataPacket;
import net.grug.minecraft.ornithe.client.ClientGrugScreens;
import net.grug.minecraft.ornithe.client.GrugOverlayHost;
import net.grug.minecraft.ornithe.client.GrugOwnedScreenHost;
import net.grug.minecraft.ornithe.control.GrugControlAlphaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TickTimer;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.client.gui.GameGui;
import net.minecraft.world.World;

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

    @Inject(method = "tick", at = @At("RETURN"))
    private void grug$afterClientTick(CallbackInfo ci) {
        net.grug.minecraft.ornithe.control.GrugControlAlphaClient.afterClientTick(
                (Minecraft) (Object) this);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$onClientTick(CallbackInfo ci) {
        GrugOwnedScreens.runGameRemovals();
        net.grug.minecraft.ornithe.OrnitheAdapter.holdPlayerForTest(
                ((Minecraft) (Object) this).player);
        grug$hooks.tick();
        // A saved-data packet arrives on the network thread, whose world lookup answers a tile the
        // frame pass does not draw, so it is applied here, on the thread that draws. See #1093.
        GrugSavedDataPacket.applyPending(((Minecraft) (Object) this).world);
        // A GUI packet a server sent arrives on the network side too, so the screen it opens is
        // installed here, on the thread that draws (#1098).
        ClientGrugScreens.applyPending((Minecraft) (Object) this);
        GrugControlAlphaClient.onClientTick((Minecraft) (Object) this, grug$hooks);
        GrugOverlayHost.pollWorldKeys((Minecraft) (Object) this);
    }

    /**
     * Ends an open mod screen before the world it draws over is replaced, so its {@code
     * screen_closed} runs while the old world is still readable, and takes the host away with it so
     * a closed screen neither draws over the new world nor takes input meant for it.
     *
     * <p>The hook is on the two-argument overload, which the one-argument one calls, so one world
     * change ends a screen once rather than twice. Alpha's world change is a two-argument method
     * and not b1.7.3's three-argument one.
     */
    @Inject(method = "setWorld(Lnet/minecraft/world/World;Ljava/lang/String;)V", at = @At("HEAD"))
    private void grug$worldChanged(World world, String message, CallbackInfo ci) {
        GrugOwnedScreen open = GrugOwnedScreens.openScreen();
        GrugOwnedScreens.worldUnloaded();
        // This version's setWorld leaves the open screen in place, so the host whose model just
        // closed is taken away here as well. A world change the game itself ends with openScreen
        // has already removed it, which leaves nothing for this check to take.
        if (open == null) return;
        Minecraft minecraft = (Minecraft) (Object) this;
        // This version's openScreen reads the player when a world is still loaded, so a host is
        // only taken away once there is a player to read.
        if (minecraft.player == null) return;
        if (minecraft.screen instanceof GrugOwnedScreenHost
                && ((GrugOwnedScreenHost) minecraft.screen).model() == open) {
            minecraft.openScreen(null);
        }
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
