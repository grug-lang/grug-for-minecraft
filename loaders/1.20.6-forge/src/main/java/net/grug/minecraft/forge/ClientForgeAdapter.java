package net.grug.minecraft.forge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;

import net.grug.minecraft.core.GrugCaptureWait;
import net.grug.minecraft.core.GrugLightSync;
import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestBox;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestPlayerSnap;
import net.grug.minecraft.core.GrugTestScreens;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.forge.block.GrugBlock;
import net.grug.minecraft.forge.block.entity.GrugBlockEntity;
import net.grug.minecraft.forge.mixin.GuiSavingIndicatorAccessor;
import net.grug.minecraft.forge.mixin.ItemInHandRendererAccessor;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugOverlay;
import net.grug.minecraft.grug.GrugScreenshots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ForgeEventFactoryClient;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public class ClientForgeAdapter extends ForgeAdapter {

    /**
     * Names the give to the server this client is joined to, which applies it to this player if
     * they are an operator (#882). Singleplayer has an integrated server and gives locally, and a
     * server without the grug channel cannot be asked, so both answer false and keep the local
     * path.
     */
    @Override
    @GrugGenerated(
            "a joined client: no test the coverage gate counts launches one, only the multiplayer"
                    + " job does")
    public boolean askServerToGive(String itemName, int count) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null || mc.getConnection() == null) {
            return false;
        }
        return GrugControlForge.sendGiveToServer(
                mc.getConnection().getConnection(),
                net.grug.minecraft.core.GrugControlWire.giveRequest(itemName, count));
    }

    @Override
    public GrugSide getSide() {
        return GrugSide.CLIENT;
    }

    /**
     * The client's own copy of the world, which its frame pass draws.
     *
     * <p>In singleplayer this is the client's mirror of the integrated server's world, a different
     * object from the world {@link #getLevel()} answers, and a saved number reaches it only when
     * the loader sends the block entity's data to it (#1162). A dedicated server never asks: its
     * adapter forwards the read to the joined client.
     */
    @Override
    public Object clientLevel() {
        return Minecraft.getInstance().level;
    }

    @Override
    public Object testPlayer() {
        return Minecraft.getInstance().player;
    }

    /**
     * Runs the world and the client's tick faster than real time, together.
     *
     * <p>Two clocks keep time here, and both are moved. The integrated server ticks the world, the
     * block entities and the runner itself on its own thread, and the client ticks from its own
     * timer, which never goes faster than 20 a second on the server's word, so {@code
     * MinecraftTimerMixin} scales it from {@link GrugGameClock}. The runner is queued from the
     * client's tick, so with only the server sped up it would run 20 times a second against a world
     * that ran many times as fast.
     *
     * <p>The client runs at most 10 ticks for a frame, so what a speed delivers is bounded by about
     * ten times the frame rate, and a server left to its tick rate would run ahead of what the
     * client can consume (1.64 times at 10x, 15 times at 100x). The server therefore gets a tick
     * rate that is never the limit, and {@code MinecraftServerTickMixin} holds each of its ticks
     * back until the client has made as many, so the world stays on the runner's clock.
     */
    @GrugGenerated(
            "the game's private tick timer: a dedicated server and a client without a world are"
                    + " never reached, and the rest is the scale, which CI sees only as how long a"
                    + " test takes")
    @Override
    public boolean setGameSpeed(double speed) {
        // A dedicated server has no client timer, and its tick rate is the server's own to set.
        if (getSide() != GrugSide.CLIENT) return false;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return false;
        if (speed > 1) {
            GrugGameClock.LOCKSTEP.start();
            GrugGameClock.speed = (float) speed;
            server.tickRateManager().setTickRate(FAST_TICK_RATE);
        } else {
            GrugGameClock.speed = 1.0F;
            GrugGameClock.LOCKSTEP.stop();
            server.tickRateManager().setTickRate(REAL_TIME_TICK_RATE);
        }
        return true;
    }

    /**
     * Saves the world once, at the tick a test whose file name starts with {@code autosave_} asks
     * for, the way the game's own autosave does, so the save indicator is drawn on a tick the test
     * knows. See {@link GrugForcedAutosave}.
     */
    @Override
    public void requestAutosaveForTest() {
        Objects.requireNonNull(
                        Minecraft.getInstance().getSingleplayerServer(),
                        "a test-only autosave needs the integrated server")
                .saveEverything(true, false, false);
    }

    /**
     * Whether the client holds the chunk a position is in.
     *
     * <p>A placement in a chunk the client has not received is written to the server's world, which
     * is the world a test reads here, and the client's copy of it stays void air: the chunk is only
     * sent once the player comes near it. Waiting for that copy would wait until the test's budget
     * ran out. The read is on the server thread, against the client's own chunk storage, whose
     * slots are atomic. See #804.
     */
    protected boolean clientHasChunk(int x, int z) {
        // Unguarded like the rest of the adapter, which only runs with a level: a grug Level is
        // taken from one. See #804.
        return Minecraft.getInstance().level.getChunkSource().hasChunk(x >> 4, z >> 4);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run reads the screen it just opened")
    public Object carriedItem() {
        // The menu the click is applied to is the answer, which on a single-player run is the
        // integrated server's, as it is on a dedicated server (#1094): the runner holds its next
        // tick until the click's handler ran, so this copy is settled by then. The screen's own
        // copy is told about the click by a packet of its own, and the hold does not order that
        // packet, so a click that ran can leave it a tick behind and an assertion reading it would
        // fail on a loaded machine (#1196). The screen's menu stays the answer on the client
        // thread, where an overlay reads back the cursor it just changed, and that copy is the one
        // delete_carried_item clears at once.
        ServerPlayer serverPlayer = integratedServerPlayer();
        if (serverPlayer != null) {
            ItemStack carried = serverPlayer.containerMenu.getCarried();
            return carried.isEmpty() ? null : carried.getItem();
        }
        AbstractContainerScreen<?> container = openContainerScreen();
        if (container == null) return null;
        ItemStack carried = container.getMenu().getCarried();
        return carried.isEmpty() ? null : carried.getItem();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run sets the cursor on the screen it just opened")
    public void setCarriedItem(Object itemObj) {
        onClientThread(() -> setCarriedItemOnClient(itemObj));
        // The press a test sends next is applied to the server's own menu, and the press packet
        // carries only the client's copy of the cursor, which never sets the server's (#1094). A
        // test ticks on the integrated server's own thread in single player, so the item is put
        // there before the press rather than queued behind the tick that needs it.
        ServerPlayer serverPlayer = integratedServerPlayer();
        if (serverPlayer != null) {
            serverPlayer.containerMenu.setCarried(
                    itemObj == null ? ItemStack.EMPTY : new ItemStack((Item) itemObj, 1));
        }
    }

    /**
     * The player whose menu a press from this client is applied to, when this runs on an integrated
     * server's own thread: the menu that client's click reaches. Null on the client thread, whose
     * presses that server has not applied yet, and on a client of a remote server, which has no
     * integrated server at all.
     */
    @GrugGenerated("a client of a remote server has no integrated server, which no CI leg is")
    private static ServerPlayer integratedServerPlayer() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return null;
        Player local = Minecraft.getInstance().player;
        return local == null ? null : server.getPlayerList().getPlayer(local.getUUID());
    }

    @GrugGenerated("the no-screen arm: a run sets the cursor on the screen it just opened")
    private void setCarriedItemOnClient(Object itemObj) {
        AbstractContainerScreen<?> container = openContainerScreen();
        if (container == null) return;
        container
                .getMenu()
                .setCarried(itemObj == null ? ItemStack.EMPTY : new ItemStack((Item) itemObj, 1));
    }

    @Override
    public void playUiSound(String path, double volume, double pitch) {
        // The sound engine belongs to the client thread, and a test calls this from the server's.
        onClientThread(
                () -> {
                    SoundEvent event =
                            BuiltInRegistries.SOUND_EVENT.get(
                                    new ResourceLocation("minecraft", path));
                    Minecraft.getInstance()
                            .getSoundManager()
                            .play(SimpleSoundInstance.forUI(event, (float) pitch, (float) volume));
                });
    }

    @Override
    public void deleteCarriedItem() {
        // The click pass calls this on the client thread, but a test's own clear runs on the server
        // thread, so the client's menu is touched where it belongs either way.
        onClientThread(this::deleteCarriedItemOnClient);
        // The menu the next runner tick reads this cursor from is the integrated server's (see
        // carriedItem), so the clear has to reach it before this call returns rather than through
        // the queue deleteCarriedItemOnClient leaves behind. Writing it from the client thread is
        // safe where an overlay's delete comes from, the runner's forwarded click: the runner holds
        // the server blocked on that click until it answers, so nothing else is in the menu, and
        // that answer orders this write before the server reads it. The same hold is what settles
        // the tick after a press (#1094), and a dedicated server gets the same ordering from the
        // delete packet its client sends before answering (#1094, #1196).
        clearIntegratedServerCarried();
    }

    /**
     * Clears the cursor of the integrated server's own menu, from whichever thread this runs on.
     * The queue {@link #deleteCarriedItemOnClient} leaves covers the same menu a tick later, which
     * is too late for a test that reads it on the next tick; see {@link #deleteCarriedItem}.
     */
    @GrugGenerated("the no-server arm: a run deletes a cursor on an integrated server")
    private static void clearIntegratedServerCarried() {
        Player local = Minecraft.getInstance().player;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (local == null || server == null) return;
        clearServerCarried(server, local);
    }

    @GrugGenerated(
            "the no-screen and no-server arms: a run deletes from the screen it just opened"
                    + " on an integrated server")
    private void deleteCarriedItemOnClient() {
        AbstractContainerScreen<?> container = openContainerScreen();
        if (container == null) return;
        container.getMenu().setCarried(ItemStack.EMPTY);

        // The screen's menu is the client's copy, so the server keeps its own carried stack until
        // it is cleared too, which is the half JEI's delete packet does. A client of a remote
        // server has no server to reach from here, which about.json records with the give.
        Player local = Minecraft.getInstance().player;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (local == null || server == null) return;
        server.execute(() -> clearServerCarried(server, local));
    }

    @GrugGenerated(
            "a missing server player: a run deletes from the screen it just opened on an"
                    + " integrated server")
    private static void clearServerCarried(MinecraftServer server, Player local) {
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(local.getUUID());
        if (serverPlayer != null) {
            serverPlayer.containerMenu.setCarried(ItemStack.EMPTY);
        }
    }

    @GrugGenerated("the no-screen arm: a run reads the screen it just opened")
    private static AbstractContainerScreen<?> openContainerScreen() {
        Screen screen = Minecraft.getInstance().screen;
        return screen instanceof AbstractContainerScreen<?> container ? container : null;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double screenWidthForTest() {
        if (refuseWithoutAClient("Test.screen_width")) return 0.0;
        Screen screen = Minecraft.getInstance().screen;
        return screen == null ? 0.0 : screen.width;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiLeftForTest() {
        if (refuseWithoutAClient("Test.gui_left")) return 0.0;
        AbstractContainerScreen<?> container = openContainerScreen();
        return container == null ? 0.0 : container.getGuiLeft();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiWidthForTest() {
        if (refuseWithoutAClient("Test.gui_width")) return 0.0;
        AbstractContainerScreen<?> container = openContainerScreen();
        return container == null ? 0.0 : container.getXSize();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiTopForTest() {
        if (refuseWithoutAClient("Test.gui_top")) return 0.0;
        AbstractContainerScreen<?> container = openContainerScreen();
        return container == null ? 0.0 : container.getGuiTop();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiHeightForTest() {
        if (refuseWithoutAClient("Test.gui_height")) return 0.0;
        AbstractContainerScreen<?> container = openContainerScreen();
        return container == null ? 0.0 : container.getYSize();
    }

    @Override
    public boolean isWorldSettled() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        // What the client owes is the simulated world's current block, not the one the test placed:
        // a clear can be refilled by water. This runs on the server thread, which owns that world.
        Object simulated = getLevel();
        // The server's half comes first: it is the half that can be about to change, and a light
        // change the light thread applies between two reads below can then only make the light
        // comparison fail, never pass early. The client's half is read last, because the frame it
        // counts is the one a change dirties. See #686.
        if (!serverSettled(minecraft, level)) {
            return false;
        }
        if (!GrugLightSync.roomSettled(
                (x, y, z) -> blockLightLayer(simulated, x, y, z),
                (x, y, z) -> blockLightLayer(level, x, y, z))) {
            return false;
        }
        // A hand that is moving now is work no frame sampled so far has seen: the sample runs at
        // the end of a frame, and the runner runs up to 10 ticks of a test for one frame, so the
        // item change that starts a rise can be several ticks old with no frame drawn since it.
        // Counting it now keeps the two idle-frame wait from passing on frames drawn before the
        // change. The gate is here rather than in clientSettled because that method takes the
        // frames' answer as a value, and this is the one part of the frame read at the question.
        // The value is the one the client thread published at its last tick; see
        // noteClientTickHand for why the ask cannot read the renderer itself.
        if (handMovedInLastClientTick) {
            RENDER_SETTLE.noteBusy();
            return false;
        }
        return clientSettled(minecraft, simulated);
    }

    /**
     * One completed frame, called from the render event handler: the level has been uploaded and
     * drawn, so whether it still had work in it is the answer a capture reading this frame needs.
     *
     * <p>The light engine is not sampled here: a frame is drawn after the client drained it, and
     * the test's own wait and the capture's re-check ask it at the moment they run, which is where
     * light work that arrives between two frames is caught.
     */
    @Override
    public void onFrameRendered() {
        Minecraft minecraft = Minecraft.getInstance();
        // The reader only runs while an edit is owed, and only a placement in a level records one,
        // so there is a client level whenever it is asked. This event fires after a level was
        // drawn, and a frame drawn before a world exists owes nothing. The client's copy is only
        // safe to read on this thread, where the packets that change it are applied, which is why
        // the frame hook is the one place it is sampled. See #804.
        boolean blocksPending =
                BLOCK_SYNC.onFrame((x, y, z) -> blockNameAt(minecraft.level, x, y, z));
        // A frame drawn from a view the mouse moved is not one a capture may read, and the view is
        // levelled again for the next frame. See GrugTestCamera and #1128. A joined client has no
        // test runner of its own, so its control run owns the view too (#1161). The two flags are
        // combined with a non-short-circuit | so a client run and a joined client both evaluate
        // the control-run answer.
        RENDER_SETTLE.onFrame(
                GrugTestCamera.frameBusy(
                        !rendererIdle(minecraft) | handMoved(minecraft),
                        testPlayerSnapped | GrugModLoader.isControlRunActive(),
                        testPlayer(),
                        CAMERA_VIEW),
                blocksPending);
    }

    /**
     * This generation's view of the local player: its yaw and pitch, and the pair a frame
     * interpolates from.
     */
    private static final GrugTestCamera.View CAMERA_VIEW =
            new GrugTestCamera.View() {
                @Override
                public float yaw(Object player) {
                    return ((Player) player).getYRot();
                }

                @Override
                public float pitch(Object player) {
                    return ((Player) player).getXRot();
                }

                @Override
                public float previousYaw(Object player) {
                    return ((Player) player).yRotO;
                }

                @Override
                public float previousPitch(Object player) {
                    return ((Player) player).xRotO;
                }

                @Override
                public void level(Object player) {
                    Player target = (Player) player;
                    target.setYRot(0.0F);
                    target.setXRot(0.0F);
                    target.yRotO = 0.0F;
                    target.xRotO = 0.0F;
                }
            };

    private static final int SCAN_IDLE = 0;
    private static final int SCAN_PENDING = 1;
    private static final int SCAN_DONE = 2;

    /** Where the scan for {@link #hasStaleTestBlockEntities} is, and what it found when done. */
    private volatile int staleScan = SCAN_IDLE;

    private volatile boolean staleScanFound = false;

    /**
     * Whether the client's chunks still hold a grug block entity inside the test area that the room
     * build has cleared on the server. The server's packets about the clearing reach the client a
     * tick or more after the build, and until it has processed them a rebuild of one of its
     * sections draws the old block entity on a chunk worker thread. That draw enters the VM while
     * the test's first call waits on the client thread with the state lock released, and an err it
     * reports there stops the test's own call. See #583.
     *
     * <p>The chunks' block entity maps are the client thread's to change, and iterating one from
     * another thread can fail halfway, so the scan runs on the client thread. The runner asks from
     * the server thread, and a wait for the client thread there would hold the server up for as
     * long as the client is busy, which after a resource reload is seconds, and the server then
     * ticks in a burst to catch up and runs a test's waits out before the client has done anything.
     * So the scan is posted and not waited for: this answers true while it has not come back, and
     * asks again after a scan that found one.
     */
    @Override
    @GrugGenerated(
            "client plumbing: the answer depends on how far the client is behind the server, which"
                    + " a run cannot choose")
    public boolean hasStaleTestBlockEntities() {
        if (FMLEnvironment.dist != Dist.CLIENT) return false;
        Player player = (Player) testPlayer();
        if (player == null) return false;
        if (staleScan == SCAN_PENDING) return true;
        if (staleScan == SCAN_DONE) {
            staleScan = SCAN_IDLE;
            if (!staleScanFound) return false;
        }
        double feetX = player.getX();
        double feetY = player.getY();
        double feetZ = player.getZ();
        staleScan = SCAN_PENDING;
        Minecraft.getInstance()
                .execute(
                        () -> {
                            staleScanFound = clientHoldsBlockEntityInTestArea(feetX, feetY, feetZ);
                            staleScan = SCAN_DONE;
                        });
        return true;
    }

    @GrugGenerated("client plumbing: see hasStaleTestBlockEntities")
    private static boolean clientHoldsBlockEntityInTestArea(
            double feetX, double feetY, double feetZ) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return false;
        int radius = GrugTestBox.areaRadius();
        int minChunkX = ((int) Math.floor(feetX) - radius) >> 4;
        int maxChunkX = ((int) Math.floor(feetX) + radius) >> 4;
        int minChunkZ = ((int) Math.floor(feetZ) - radius) >> 4;
        int maxChunkZ = ((int) Math.floor(feetZ) + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk =
                        level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    net.minecraft.core.BlockPos at = blockEntity.getBlockPos();
                    if (blockEntity instanceof GrugBlockEntity
                            && !blockEntity.isRemoved()
                            && GrugTestBox.inClearedArea(
                                    feetX, feetY, feetZ, at.getX(), at.getY(), at.getZ())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether the client's own rendering state has nothing left that a frame's bake depends on: its
     * light engine is idle, and the last frame it drew had neither queued nor in-flight compiles.
     *
     * <p>This is the half of the settle answer a screenshot wait can hold for on its own, because
     * it advances on the client tick while the wait runs on the server thread and blocks it: a wait
     * that included the server half would freeze the server light work it was waiting for, since
     * that engine only drains on the server tick. The server half is the test's own wait in {@link
     * #isWorldSettled}, one call per test tick, where the server is free to tick. See #259.
     *
     * <p>The frame rule is what the renderer's queues cannot say by themselves. A compile that
     * finishes on a worker thread leaves the queues empty between two frames, and the frame that
     * uploads and draws its output has not been drawn yet, so a capture taken then would read the
     * frame from before the compile. See #257.
     *
     * <p>A client resource reload that is waiting to start or has not finished keeps this false:
     * the render thread spends it stitching atlases instead of processing what the server sent, so
     * frames drawn meanwhile end idle before the client has seen a test's own setup, and a settled
     * answer built from them describes a world that is not there yet. See #328.
     */
    private static boolean clientSettled(Minecraft minecraft, Object simulated) {
        // All four reads happen every time and the rule is GrugRenderSettle.clientSettled, a pure
        // function that its unit test covers in every combination, so this method has no branch of
        // its own that only a poll landing while light packets are queued, or a frame landing while
        // a test's block edit is owed, could meet. See #424 and #804.
        return !GrugModLoader.isReloading()
                && GrugRenderSettle.clientSettled(
                        minecraft.level.getChunkSource().getLightEngine().hasLightWork(),
                        minecraft.level.isLightUpdateQueueEmpty(),
                        RENDER_SETTLE.settled(),
                        // The client can be lit and idle while a test's own block is still a packet
                        // away: only its copy of the block says whether the frame a capture reads
                        // shows the edit. The answer refreshes from the simulated world first, so a
                        // capture whose wait has no settle call in front of it compares the client
                        // against what the world holds now rather than against a block the world
                        // replaced. See #804.
                        BLOCK_SYNC.settled((x, y, z) -> blockNameAt((Level) simulated, x, y, z)));
    }

    /**
     * Whether the client's chunk renderer has nothing left that a capture would have to be taken
     * after: no queued or finished compile, and none in flight.
     *
     * <p>{@code LevelRenderer.hasRenderedAllSections} is {@code
     * SectionRenderDispatcher.isQueueEmpty}, and that counts queued and finished compiles only: a
     * compile a worker is running has been taken off the queues and holds one of the section buffer
     * pool's buffers, so the pool's free count is what says one is in flight. A capture taken while
     * it runs reads the frame from before that compile's light, which is the one-step band in the
     * crop's corner. The pool's size is recorded when it is built, by the mixin next to this
     * class's loader. See #259.
     */
    @GrugGenerated(
            "settle: the in-flight phase is a compile running while the queues are empty, which a"
                    + " run reaches at the tail of a batch but cannot force; the idle answer as a"
                    + " whole stays measured through the caller's outcome")
    private static boolean rendererIdle(Minecraft minecraft) {
        if (!minecraft.levelRenderer.hasRenderedAllSections()) {
            return false;
        }
        return minecraft.levelRenderer.getSectionRenderDispatcher().getFreeBufferCount()
                == GrugModLoader.getSectionBufferPoolSize();
    }

    /**
     * Whether the client's first-person hand is still moving, so the frame it just drew is not one
     * a capture may read.
     *
     * <p>The hand is drawn in the world pass, before the screen is drawn over it, so an open screen
     * shows it behind its panel. Its equip height eases from 0 to 1 after the held item changes,
     * which the runner does before every test, and the number of frames between that change and a
     * capture varies with how the run's ticks fall, so two runs captured the hand at different
     * points of its rise (#961). A frame whose pairs are equal draws the hand at rest, which is the
     * pose every golden was accepted with. See {@link GrugRenderSettle#handMoved}.
     *
     * <p>The pair is written by the client thread's tick, so the settle ask, which runs on the
     * server thread of a singleplayer run, does not read it off the renderer: {@link
     * #noteClientTickHand} publishes the answer through a volatile field at the end of each client
     * tick, after {@code ItemInHandRenderer.tick} wrote the pair.
     */
    private static boolean handMoved(Minecraft minecraft) {
        Player player = minecraft.player;
        if (player == null) {
            return false;
        }
        ItemInHandRendererAccessor hand =
                (ItemInHandRendererAccessor) minecraft.gameRenderer.itemInHandRenderer;
        return GrugRenderSettle.handMoved(
                hand.grug$mainHandHeight(),
                hand.grug$lastMainHandHeight(),
                hand.grug$offHandHeight(),
                hand.grug$lastOffHandHeight(),
                player.attackAnim,
                player.oAttackAnim);
    }

    /**
     * Whether the client's hand moved in the tick that just ended, last published for {@link
     * #isWorldSettled} to read.
     *
     * <p>The hand's pair is written by the client thread, and the settle ask of a singleplayer run
     * is made on the server thread, so the answer crosses over as one volatile boolean rather than
     * as six floats read from a thread that did not tick them. The entry is the one {@link
     * #handMoved} computes after {@code ItemInHandRenderer.tick}, which runs inside the client's
     * own tick, so the ask sees the rise from the tick that started it and not only from the next
     * frame.
     */
    private static volatile boolean handMovedInLastClientTick;

    /** Publishes whether the hand moved during the client tick that ended, for the settle ask. */
    static void noteClientTickHand() {
        handMovedInLastClientTick = handMoved(Minecraft.getInstance());
    }

    /**
     * Whether the integrated server's light engine is done, or there is no server to ask.
     *
     * <p>A singleplayer client's light comes from the integrated server, so the server has to be
     * done too: its updates reach the client as packets, and the client can look idle while the
     * server is still computing them. The lookup is loader plumbing: a singleplayer client run has
     * a server and the client's own dimension. The no-server arm is the client of a dedicated
     * server, which the multiplayer legs launch and take on every forwarded settle ask (#1086), and
     * the no-level arm is a dimension the server does not have.
     *
     * <p>The public light-engine answer counts only that engine's propagation queues, so the checks
     * a batch has not reached yet and the batch itself are read from the engine's own state, which
     * the mixin next to this loader's mixins exposes. See {@link
     * GrugRenderSettle#serverLightSettled} and #686.
     */
    @GrugGenerated(
            "settle: the integrated server lookup is loader plumbing a CI client run cannot"
                    + " observe, and so is the arm where the engine is not the threaded one")
    private boolean serverSettled(Minecraft minecraft, ClientLevel level) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return true;
        }
        ServerLevel serverLevel = server.getLevel(level.dimension());
        if (serverLevel == null) {
            return true;
        }
        ThreadedLevelLightEngine engine = serverLevel.getChunkSource().getLightEngine();
        boolean tasksQueued = false;
        boolean updateScheduled = false;
        if (engine instanceof GrugLightEngineState state) {
            tasksQueued = state.grug$hasQueuedLightTasks();
            updateScheduled = state.grug$isUpdateScheduled();
        }
        return GrugRenderSettle.serverLightSettled(
                engine.hasLightWork(), tasksQueued, updateScheduled);
    }

    private boolean testPlayerSnapped = false;
    private double savedX, savedY, savedZ;
    private float savedYaw, savedPitch;

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void snapPlayerForTestOnClient() {
        // The test runner is driven from the integrated server thread, but the camera is the client
        // player's, so client-side work is marshalled onto the client (render) thread.
        onClientThread(this::snapPlayerOnClient);
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void snapPlayerOnClient() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            throw Grug.fatal(
                    "The test runner: there is no local player to snap to their block centre.");
        }

        savedX = player.getX();
        savedY = player.getY();
        savedZ = player.getZ();
        savedYaw = player.getYRot();
        savedPitch = player.getXRot();
        testPlayerSnapped = true;

        // Snap the player to the centre of their own block and level the view, so every loader
        // frames a fixture identically and the frame does not depend on which way the player was
        // looking. The snap stays inside the player's own block, and therefore inside the chunk
        // already loaded and lit around them, so unlike a far teleport it cannot drag the camera
        // into an unlit chunk. The runner restores this position when the test ends.
        player.setYRot(0.0F);
        player.setXRot(0.0F);
        player.setPos(
                GrugTestPlayerSnap.blockCentre(savedX),
                savedY,
                GrugTestPlayerSnap.blockCentre(savedZ));
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restorePlayerAfterTest() {
        // This is the client half; a dedicated server restores its own copy of the joined player
        // in its server adapter. The runner calls this for every test that ends, so a client whose
        // snap never ran must not report an error for those.
        if (!testPlayerSnapped) return;
        testPlayerSnapped = false;

        onClientThread(this::restorePlayerOnClient);
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void restorePlayerOnClient() {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            player.setPos(savedX, savedY, savedZ);
            player.setYRot(savedYaw);
            player.setXRot(savedPitch);
        }
    }

    @Override
    public void closeOpenScreenForTest() {
        // Marshalled like the other graphics calls, because the runner advances on the server
        // thread while the screen belongs to the client. onClientThread waits for the task, so the
        // screen is gone by the time this returns and not merely by the next client tick.
        onClientThread(
                () -> {
                    Minecraft.getInstance().setScreen(null);
                    // A click still expected for the screen that just closed will never be handled,
                    // so dropping it keeps a later click from waiting for this one's timeout
                    // (#1094).
                    GrugClientClicks.reset();
                });
    }

    @Override
    public boolean isScreenOpenForTest() {
        return Minecraft.getInstance().screen != null;
    }

    @Override
    public long screenIdentityForTest() {
        // The screen this loader calls open is the one it names, so a loader's own rule about
        // which screens a test can see is asked here rather than repeated (#1289).
        return isScreenOpenForTest()
                ? GrugTestScreens.identityOf(Minecraft.getInstance().screen)
                : 0;
    }

    @Override
    @GrugGenerated("the refusal arm needs a dedicated server, whose coverage no CI run merges")
    public void openInventory(Object playerObj) {
        if (refuseWithoutAClient("Test.open_inventory")) return;
        Player player = (Player) playerObj;
        // Marshalled like closeOpenScreenForTest, because a test ticks on the server thread and the
        // screen belongs to the client. The screen is up by the next frame, which a test waits for
        // with Test.screen_open.
        onClientThread(() -> Minecraft.getInstance().setScreen(new InventoryScreen(player)));
    }

    @Override
    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    public boolean clickScreen(double x, double y, double button) {
        if (refuseWithoutAClient("Test.click_screen")) return false;
        // The same path a real press takes, fired on the client thread, so the overlay's handler
        // and the screen's own handler see exactly what a mouse would send, including a
        // cancellation. The arms live in clickOnClient so that this method has no branch for a
        // unit test to be unable to reach.
        boolean[] outcome = new boolean[2];
        onClientThread(() -> clickOnClient(x, y, (int) button, outcome));
        // Reported here rather than on the client thread: the grug call runs on the server thread,
        // and a host function error raised from another thread would not reach it.
        if (outcome[0]) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.click_screen: There is no screen open to click.");
        }
        return outcome[1];
    }

    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    private void clickOnClient(double x, double y, int button, boolean[] outcome) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            outcome[0] = true;
            return;
        }
        // The answer is the overlay's own decision, not the screen's: a container screen handles a
        // press on a vanilla slot too, so asking the screen would answer true with no overlay in
        // the way. The record is cleared before the press and read after the overlay had its say.
        GrugOverlay.beginTestPress();
        boolean[] state = new boolean[] {false};
        ForgeEventFactoryClient.onScreenMouseClicked(state, screen, x, y, button);
        outcome[1] = GrugOverlay.testPressTaken();
        if (outcome[1]) {
            // The overlay took the press, so the screen never saw it and there is nothing to
            // release: releasing would click the vanilla slot under the icon, the click the
            // cancellation exists to prevent.
            return;
        }
        // A click is a press and a release. A container screen sends the click packet from the
        // release when the cursor carries a stack, and from the press when the cursor is empty
        // (that press marks the release as already handled), so a carried click reaches the
        // server only when both halves are delivered (#1094). The release follows the screen the
        // client shows now, as a real mouse release does: the press may have closed or replaced
        // the screen it was made on, so releasing on that one would reach a dead container and
        // leave the current screen's own release unhandled (#1217).
        Screen releasedScreen = Minecraft.getInstance().screen;
        if (releasedScreen == null) {
            return;
        }
        boolean[] released = new boolean[] {false};
        ForgeEventFactoryClient.onScreenMouseReleased(released, releasedScreen, x, y, button);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run scrolls while the screen it just opened is up")
    public boolean scrollScreen(double x, double y, double delta) {
        if (refuseWithoutAClient("Test.scroll_screen")) return false;
        // The same path a real wheel takes: the pre event the overlay listens to, fired on the
        // client thread, and the screen's own handler only when nothing cancelled it.
        boolean[] outcome = new boolean[2];
        onClientThread(() -> scrollOnClient(x, y, delta, outcome));
        if (outcome[0]) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.scroll_screen: There is no screen open to scroll.");
        }
        return outcome[1];
    }

    @GrugGenerated("the no-screen arm: a run scrolls while the screen it just opened is up")
    private void scrollOnClient(double x, double y, double delta, boolean[] outcome) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            outcome[0] = true;
            return;
        }
        GrugOverlay.beginTestPress();
        if (!ForgeEventFactoryClient.onScreenMouseScrollPre(screen, x, y, 0.0, delta)) {
            screen.mouseScrolled(x, y, 0.0, delta);
        }
        outcome[1] = GrugOverlay.testPressTaken();
    }

    @Override
    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        if (refuseWithoutAClient("Test.press_screen_key")) return false;
        // The same path a real key takes: the pre event the overlay listens to, fired on the
        // client thread, and the screen's own handler only when nothing cancelled it.
        boolean[] outcome = new boolean[2];
        onClientThread(() -> pressKeyOnClient(key, ctrl, release, outcome));
        if (outcome[0]) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.press_screen_key: There is no screen open to press.");
        }
        return outcome[1];
    }

    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    private void pressKeyOnClient(int key, boolean ctrl, boolean release, boolean[] outcome) {
        Screen screen = Minecraft.getInstance().screen;
        if (screen == null) {
            outcome[0] = true;
            return;
        }
        GrugOverlay.beginTestPress();
        int modifiers = ctrl ? GrugOverlayHost.controlModifier() : 0;
        if (!ForgeEventFactoryClient.onScreenKeyPressedPre(screen, key, 0, modifiers)) {
            screen.keyPressed(key, 0, modifiers);
        }
        outcome[1] = GrugOverlay.testPressTaken();
        if (release) {
            ForgeEventFactoryClient.onScreenKeyReleasedPre(screen, key, 0, modifiers);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.use_block")) return;
        onClientThread(() -> useBlockOnClient(x, y, z));
    }

    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private void useBlockOnClient(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        // Drive a real right-click through the interaction manager, so the block's own use code
        // runs rather than a copy of its GUI layout. The face is the one a ray from the test
        // player's eye to the block's centre enters, which is the face the player sees of it and
        // the face the server path picks from the same eye (#1106).
        Direction face =
                Direction.values()[
                        GrugUseBlockFace.face(
                                testPlayerEye(mc.player), pos.getX(), pos.getY(), pos.getZ())];
        BlockHitResult hit =
                new BlockHitResult(
                        new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5),
                        face,
                        pos,
                        false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    @Override
    @GrugGenerated(
            "failure paths a healthy run cannot enter: no local player or client level, no block at"
                    + " the position, or a shape the overlay failed to reset")
    public boolean miningCrackForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.mining_crack")) return false;
        final net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(
                        (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        final boolean[] result = new boolean[1];
        // The baked model and its model data are client objects, and the render thread reads the
        // manager this asks for, so the whole question is asked on that thread.
        onClientThread(() -> result[0] = miningCrackOnClient(pos));
        return result[0];
    }

    @GrugGenerated(
            "failure paths a healthy run cannot enter: no local player or client level, no block at"
                    + " the position, or a shape the overlay failed to reset")
    private static boolean miningCrackOnClient(net.minecraft.core.BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.mining_crack: There is no client level to draw in.");
            return false;
        }

        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof GrugBlock)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.mining_crack: There is no grug block at "
                            + pos.getX()
                            + ", "
                            + pos.getY()
                            + ", "
                            + pos.getZ()
                            + ".");
            return false;
        }

        // The overlay draws the block's model with the model data the model data manager holds, so
        // asking for its quads the same way is the same question the overlay asks. The block
        // entity answers with its position and level, and without them the model has no place to
        // find it and draws nothing, which is what a mined block showed before this. See #201.
        ModelData data = level.getModelDataManager().getAt(pos);
        List<BakedQuad> quads =
                mc.getBlockRenderer()
                        .getBlockModel(state)
                        .getQuads(
                                state,
                                null,
                                RandomSource.create(0L),
                                data == null ? ModelData.EMPTY : data,
                                null);
        return !quads.isEmpty();
    }

    @Override
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        // No settle wait for the frame itself: the frame this reads is one drawn after the test
        // asked, and Screenshot.equals compares it against a committed PNG. The world-save
        // indicator is the exception, because it outlives the save that put it up and is drawn into
        // the frames after it: the read is served at the end of the first frame drawn after this
        // call whose own sample says the text is not in it. See #1132.
        return captureChecked("Screenshot.capture", x1, y1, x2, y2);
    }

    /** The capture a test's thread is waiting for, served by {@link #onFrameEnded}. */
    private static final class PendingCapture {
        final int x1;
        final int y1;
        final int x2;
        final int y2;

        /**
         * {@link GrugRenderSettle#framesDrawn()} when the test asked, so that the frame read is one
         * drawn after the ask rather than the one already on the buffer.
         */
        final long framesWhenAsked;

        /**
         * The rectangle the frame's end read, which the asking thread returns once {@link #done}.
         */
        BufferedImage image;

        /**
         * Why the wait ended without a frame, or null when one served the capture.
         *
         * <p>Written by the asking thread inside the wait and read by the same thread after it, so
         * it needs no other synchronization. A null image with a message here is a wait that failed
         * and reported, rather than one that has to be guessed at from an empty image.
         */
        String waitProblem;

        /** Counted down by the frame that read the rectangle, which is what the ask waits on. */
        final CountDownLatch done = new CountDownLatch(1);

        PendingCapture(int x1, int y1, int x2, int y2, long framesWhenAsked) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.framesWhenAsked = framesWhenAsked;
        }
    }

    /**
     * The capture a test's thread is waiting for, or null.
     *
     * <p>Written by the test's thread and read by the client thread at the end of every frame, so
     * it is volatile. Only one capture runs at a time: the runner makes one grug call at a time,
     * and a capture does not return until its frame has served it.
     */
    private static volatile PendingCapture pendingCapture;

    /**
     * Serves the capture a test is waiting for, at the end of the frame just drawn, once that frame
     * is one a capture may read.
     *
     * <p>Called from the render tick's end on the client thread, after the frame is complete, so
     * the check and the read describe the same frame and no frame can be drawn in between. That is
     * what the previous wait could not promise: it ran on the test's thread and the read followed a
     * frame later, so a frame whose client tick raised the indicator was never checked and could be
     * the one read (#1132). Waiting here rather than in one test is what makes every capture on
     * this loader unable to read the text.
     *
     * <p>The frame may serve only if it was drawn after the test asked and its own sample says the
     * text is not in it; see {@link GrugRenderSettle#captureable}. A frame that is not served yet
     * leaves the request in place, and the next frame's end asks again.
     */
    static void onFrameEnded() {
        PendingCapture capture = pendingCapture;
        if (capture == null) {
            return;
        }
        // Sampled after the frame was drawn and before the next client tick, so the values are the
        // ones this frame's GUI pass read. See #1132.
        if (!RENDER_SETTLE.captureable(capture.framesWhenAsked, savingIndicatorShown())) {
            return;
        }
        capture.image = capture(capture.x1, capture.y1, capture.x2, capture.y2);
        // Cleared before the countdown, so that a frame which ends before the asking thread has
        // taken its image cannot serve the same request twice and replace what it returns.
        pendingCapture = null;
        capture.done.countDown();
    }

    /**
     * Asks for a capture and waits for the frame's end to serve it, for at most {@link
     * GrugCaptureWait#TIMEOUT_MILLIS}.
     *
     * <p>Runs on the thread the test captured from, releasing the grug state lock while it waits,
     * since the client has to keep drawing for a frame to serve the request. Only the client thread
     * is admitted into the state for the duration: it is the thread that draws those frames, and a
     * chunk worker that entered meanwhile and drew a block entity would fold its err into the
     * suspended call (#583, #836). See {@link #releaseStateForFrameEndWait}.
     *
     * <p>A wait that ends without a frame reports why, on this thread, so the test fails with the
     * wait's own message rather than reading a null image as a pass, and a client that never draws
     * a frame a capture may read fails the test instead of hanging the job. See #1154.
     */
    private static BufferedImage captureOnFrameEnd(int x1, int y1, int x2, int y2) {
        PendingCapture capture = new PendingCapture(x1, y1, x2, y2, RENDER_SETTLE.framesDrawn());
        pendingCapture = capture;
        try {
            Runnable wait = () -> capture.waitProblem = GrugCaptureWait.await(capture.done);
            releaseStateForFrameEndWait(wait);
        } finally {
            pendingCapture = null;
        }
        reportMissingCapture(capture.waitProblem);
        return capture.image;
    }

    /**
     * Reports a wait that ended without a frame, so the capture's call fails with the wait's own
     * message.
     *
     * <p>A healthy run always serves the frame, and a CI run is not interrupted, so both the
     * timeout arm and the interrupt arm are paths no run takes. The null arm is the one every
     * capture takes.
     */
    @GrugGenerated("frame-end capture wait: a healthy run serves the frame and is not interrupted")
    private static void reportMissingCapture(String waitProblem) {
        if (waitProblem != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, waitProblem);
        }
    }

    /**
     * Picks the release form for the wait: the one that admits the client thread, or the plain one
     * for a capture before the client has ticked once.
     *
     * <p>Only that null arm is unreachable in a run, since a capture runs after the client's first
     * tick, which is what sets the field. The admitted arm is {@link #releaseWithAClientThread},
     * which is measured like any other method and runs on every capture.
     */
    @GrugGenerated(
            "frame-end capture wait: a capture runs after the client's first tick, so clientThread"
                    + " is set and this null arm cannot occur")
    private static void releaseStateForFrameEndWait(Runnable wait) {
        Thread admitted = clientThread;
        if (admitted == null) {
            releaseWithoutAClientThread(wait);
            return;
        }
        releaseWithAClientThread(wait, admitted);
    }

    /**
     * Releases the grug state lock for the wait, admitting only the client thread: it is the thread
     * that draws the frame the capture is served by, and a chunk worker that entered meanwhile and
     * drew a block entity would fold its err into the suspended call (#583, #836).
     */
    private static void releaseWithAClientThread(Runnable wait, Thread admitted) {
        Grug.runWithStateLockReleased(wait, admitted);
    }

    /**
     * The release form for a capture before the client has ticked once, which no run reaches; the
     * plain form lets every thread in, so the wait could not be kept out of the render thread.
     */
    @GrugGenerated("frame-end capture wait: the client has ticked before any capture runs")
    private static void releaseWithoutAClientThread(Runnable wait) {
        Grug.runWithStateLockReleased(wait);
    }

    /**
     * Whether the frame the client is drawing would have the save indicator in it, from the two
     * values {@code Gui} fades it with and the player's option.
     *
     * <p>Sampled from the render tick's end, on the client thread, after the frame was drawn and
     * before the next client tick, so the values are the ones this frame's GUI pass read. See
     * #1132.
     */
    private static boolean savingIndicatorShown() {
        Minecraft minecraft = Minecraft.getInstance();
        GuiSavingIndicatorAccessor indicator = (GuiSavingIndicatorAccessor) minecraft.gui;
        return GrugRenderSettle.savingIndicatorShown(
                minecraft.options.showAutosaveIndicator().get(),
                indicator.grug$savingIndicatorValue(),
                indicator.grug$lastSavingIndicatorValue());
    }

    /**
     * Checks the rectangle and reads it, reporting {@code operation} as the thing that wanted it.
     */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private BufferedImage captureChecked(
            String operation, double x1, double y1, double x2, double y2) {
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure.
        String bad = GrugScreenshots.rectangleProblem(x1, y1, x2, y2);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, operation + ": " + bad);
            return null;
        }
        int left = (int) x1;
        int top = (int) y1;
        int right = (int) x2;
        int bottom = (int) y2;

        // A defensive safety net rather than the primary sizing mechanism: R forces 1280x720 around
        // a test run. If that ever stops happening, a mismatch here is much easier to diagnose than
        // a screen of subtly wrong pixels.
        Minecraft mc = Minecraft.getInstance();
        if (mc.getWindow().getWidth() != GrugScreenshots.WIDTH
                || mc.getWindow().getHeight() != GrugScreenshots.HEIGHT) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation
                            + ": the window is "
                            + mc.getWindow().getWidth()
                            + "x"
                            + mc.getWindow().getHeight()
                            + ", but screenshot tests are captured at "
                            + GrugScreenshots.WIDTH
                            + "x"
                            + GrugScreenshots.HEIGHT);
            return null;
        }

        return captureOnFrameEnd(left, top, right, bottom);
    }

    /**
     * Reads back a rectangle of the last rendered frame. Runs on the client thread and downloads
     * the main render target's colour texture (the same path vanilla screenshots use), which is
     * Y-flipped so the image's origin is the top left, matching the crop convention.
     */
    @GrugGenerated(
            "a joined client's forwarded capture, which only the multiplayer job runs (#1040)")
    static BufferedImage captureRectangle(int x1, int y1, int x2, int y2) {
        return capture(x1, y1, x2, y2);
    }

    /**
     * Whether a joined client's renderer has nothing left to show of what the server sent, for the
     * forwarded capture of the test-control channel (#1040): no resource reload, no light work, and
     * frames drawn since that ended idle. The block half of {@link #clientSettled} is left out,
     * because it tracks edits made on a client's own side of an integrated server, and a joined
     * client makes none.
     */
    @GrugGenerated(
            "a joined client's forwarded capture, which only the multiplayer job runs (#1040)")
    static boolean clientIdleForControl(Minecraft minecraft) {
        return !GrugModLoader.isReloading()
                && GrugRenderSettle.clientSettled(
                        minecraft.level.getChunkSource().getLightEngine().hasLightWork(),
                        minecraft.level.isLightUpdateQueueEmpty(),
                        RENDER_SETTLE.settled(),
                        true);
    }

    private static BufferedImage capture(int x1, int y1, int x2, int y2) {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        NativeImage image = Screenshot.takeScreenshot(target);
        try {
            int width = x2 - x1;
            int height = y2 - y1;
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    // NativeImage stores pixels as ABGR (so they upload to GL as RGBA).
                    int pixel = image.getPixelRGBA(x1 + column, y1 + row);
                    int r = pixel & 0xFF;
                    int g = (pixel >> 8) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    out.setRGB(column, row, (r << 16) | (g << 8) | b);
                }
            }
            return out;
        } finally {
            image.close();
        }
    }

    /** The client (render) thread, noted by the client tick, which is what starts every test. */
    private static volatile Thread clientThread;

    /** Called on the client thread at the start of each client tick. */
    @GrugGenerated("client-thread marshalling: only a running client has a client thread")
    static void noteClientThread() {
        clientThread = Thread.currentThread();
    }

    /**
     * Runs a task on the client (render) thread, blocking until it has finished.
     *
     * <p>The waiting thread is inside a grug call, and the task can enter the state itself (a block
     * use, a runtime error report), so the wait releases the state lock: the blocked thread is not
     * running grug code, and the task is then the one active entry.
     *
     * <p>Only the client thread may enter the state meanwhile. A chunk worker that drew a block
     * entity there would report its err into the suspended call, which the engine then stops for an
     * err that is not its own: the render-error race of #583 and #836. The worker waits, holding
     * nothing, and draws once the call has its lock back.
     */
    @GrugGenerated("client-thread marshalling: the same-thread path cannot occur in a CI run")
    private static void onClientThread(Runnable task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            task.run();
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mc.execute(
                () -> {
                    try {
                        task.run();
                    } finally {
                        latch.countDown();
                    }
                });
        // One lambda for both arms: JaCoCo measures a lambda as its own method, and the method's
        // own @GrugGenerated does not carry over to it, so a second lambda in an arm no run takes
        // would be counted and miss the gate. See #1086.
        Runnable wait = () -> awaitLatch(latch);
        Thread admitted = clientThread;
        if (admitted == null) {
            Grug.runWithStateLockReleased(wait);
        } else {
            Grug.runWithStateLockReleased(wait, admitted);
        }
    }

    /**
     * Awaits the released client-thread task, which always runs. The interrupt path only runs when
     * the game shuts down mid-wait, which a CI run does not do.
     *
     * <p>A capture's wait is not this one: it is bounded and reports a frame that never comes, in
     * {@link GrugCaptureWait}. See #1154.
     */
    @GrugGenerated("client-thread marshalling: an interrupted wait cannot occur in a CI run")
    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
