package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugForcedNormalization;
import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestScreens;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.forge125.block.GrugBlocks;
import net.grug.minecraft.forge125.block.GrugBoxRenderer;
import net.grug.minecraft.forge125.client.GlBridge;
import net.grug.minecraft.forge125.client.GrugClientHooks;
import net.grug.minecraft.forge125.client.GrugEmptyScreen;
import net.grug.minecraft.forge125.client.GrugOverlayHost;
import net.grug.minecraft.forge125.client.GrugScreen;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.src.Block;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.GuiContainer;
import net.minecraft.src.GuiInventory;
import net.minecraft.src.GuiScreen;
import net.minecraft.src.IInventory;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.ModLoader;
import net.minecraft.src.MovingObjectPosition;
import net.minecraft.src.RenderGlobal;
import net.minecraft.src.Timer;
import net.minecraft.src.Vec3D;
import net.minecraft.src.World;
import net.minecraft.src.WorldRenderer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

/**
 * The Forge 1.2.5 adapter of a running client: everything that names a client-only class.
 *
 * <p>The rest of the adapter lives in {@link Grug125Adapter}, which a dedicated server loads too;
 * the server jar has no {@code net.minecraft.client} package and none of the renderer or screen
 * classes, so nothing in that class may name one (#166).
 */
public class Grug125ClientAdapter extends Grug125Adapter {

    @Override
    public GrugSide getSide() {
        return GrugSide.CLIENT;
    }

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        // The player and builder types cannot be wrong because of a script, so cast directly.
        EntityPlayer player = (EntityPlayer) playerObj;
        IInventory inventory = (IInventory) blockEntityObj;
        GrugGuiBuilder builder = (GrugGuiBuilder) guiBuilderObj;
        GrugScreen.open(player, inventory, builder);
    }

    @Override
    @GrugGenerated("the no-player arm: a run reads the cursor of a client with a player")
    public Object carriedItem() {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        if (minecraft == null || minecraft.thePlayer == null) return null;
        ItemStack stack = minecraft.thePlayer.inventory.getItemStack();
        return stack == null ? null : stack.getItem();
    }

    @Override
    @GrugGenerated("the no-player arm: a run sets the cursor of a client with a player")
    public void setCarriedItem(Object itemObj) {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        if (minecraft == null || minecraft.thePlayer == null) return;
        minecraft.thePlayer.inventory.setItemStack(
                itemObj == null ? null : new ItemStack((Item) itemObj));
    }

    @Override
    @GrugGenerated("the no-player arm: a run clears the cursor of a client with a player")
    public void deleteCarriedItem() {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        if (minecraft == null || minecraft.thePlayer == null) return;
        minecraft.thePlayer.inventory.setItemStack(null);
    }

    @Override
    @GrugGenerated(
            "In a CI run 1.2.5 downloads no sound resources, so its pool is empty and the true"
                    + " side of the null check was the only part the coverage report showed missed")
    public boolean hasSound(String path) {
        return ModLoader.getMinecraftInstance()
                        .sndManager
                        .getSoundsPool()
                        .getRandomSoundFromSoundPool(path)
                != null;
    }

    @Override
    @GrugGenerated(
            "In a CI run 1.2.5 downloads no sound resources, so its pool is empty, hasSound is"
                    + " never true and nothing calls this")
    public void playUiSound(String path, double volume, double pitch) {
        ModLoader.getMinecraftInstance()
                .sndManager
                .playSoundFX(path, (float) volume, (float) pitch);
    }

    @Override
    public Object getLevel() {
        return ModLoader.getMinecraftInstance().theWorld;
    }

    @Override
    public Object testPlayer() {
        return ModLoader.getMinecraftInstance().thePlayer;
    }

    /**
     * Test only: proves the block-id search spills into the ids above 255 and that an id it hands
     * out survives the game's own chunk save and load.
     *
     * <p>The pack's other mods have used up the low range by the time a grug block overruns it
     * (#509), which is the case this test makes happen on purpose: every low id it leaves free is
     * taken with a stand-in, and the id the test block gets then comes from the same pool the
     * registration takes its own from. Without a search that continues at {@link
     * GrugBlocks#FIRST_HIGH_BLOCK_ID} that pool is empty, which is the failure. The block takes the
     * id for real: it is registered in the tables the way registration registers it, placed in the
     * room the tests run in, saved with the game's own chunk loader, and the id is read back out of
     * the chunk the loader loaded, so it came back through the save's 12-bit id rather than out of
     * a field. See #518 and the measurement in #513.
     */
    @Override
    public long highBlockRoundTrip() {
        return highBlockRoundTrip(ModLoader.getMinecraftInstance().theWorld);
    }

    @GrugGenerated("Minecraft has exactly one Timer field, so the empty answer cannot be forced")
    private static Field findTimerField() {
        for (Field field : Minecraft.class.getDeclaredFields()) {
            if (field.getType() == Timer.class) {
                return field;
            }
        }
        throw Grug.fatal("Minecraft has no Timer field");
    }

    /**
     * Scales the timer, which 1.2.5's single-process game ticks the world from: there is no
     * integrated server before 1.3, so the world, the tile entities and the test runner all run
     * from the client's tick and speed up together. The timer hands out at most 10 ticks for a
     * frame, so what a speed delivers is bounded by about ten times the frame rate.
     */
    @Override
    public boolean setGameSpeed(double speed) {
        setTimerSpeed(ModLoader.getMinecraftInstance(), (float) speed);
        return true;
    }

    /**
     * Test only: the capabilities {@link #forceNormalizationForTest()} found, so {@link
     * #restoreNormalizationAfterTest()} puts back exactly that rather than turning off a state the
     * game itself left on.
     */
    private boolean normalizeWasOn;

    private boolean rescaleNormalWasOn;

    /**
     * Test only: whether this loader's GUI item draw uses the game's fixed-function lighting, so
     * the normalizing state an earlier draw left on decides how its icons shade. Every version
     * before 1.20.6 does; 1.20.6 draws items through its own shader path and keeps the default, so
     * the runner tells this hook from a hook that stopped working by asking.
     */
    @Override
    public boolean canForceNormalizationForTest() {
        return true;
    }

    /**
     * Test only: turns on the two capabilities that normalize normals, the way the game's slime
     * renderer leaves one of them, so a capture taken after it says whether the overlay's item draw
     * pins them instead of inheriting the state. The runner calls this on a client only, before the
     * marked test's first tick, which {@link GrugForcedNormalization} decides.
     *
     * <p>What each capability had goes into the two fields above before either is turned on,
     * because the force can find one already on: the slime renderer's own leak is the case the
     * marked test models, and a restore that turned it off would drop a state the game itself left
     * on (#1144).
     */
    @Override
    public boolean forceNormalizationForTest() {
        normalizeWasOn = GL11.glIsEnabled(GL11.GL_NORMALIZE);
        rescaleNormalWasOn = GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_NORMALIZE);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        // Both are read, not short-circuited, so the answer is the state the force really set and
        // no branch is left that only a failed enable would take.
        return GL11.glIsEnabled(GL11.GL_NORMALIZE) & GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
    }

    /**
     * Test only: puts back what {@link #forceNormalizationForTest()} found in the tick the marked
     * test ends, so only a capability the force turned on is turned off again. Which capabilities
     * those are is {@link GrugForcedNormalization#capabilitiesToTurnOff}, so this loader turns off
     * what it names and carries no branch that only a capability the game leaked would take.
     */
    @Override
    public boolean restoreNormalizationAfterTest() {
        for (int capability :
                GrugForcedNormalization.capabilitiesToTurnOff(
                        normalizeWasOn,
                        rescaleNormalWasOn,
                        GL11.GL_NORMALIZE,
                        GL12.GL_RESCALE_NORMAL)) {
            GL11.glDisable(capability);
        }
        // Read back, not assumed: the answer is the state the disables really left, so a call
        // that did not take fails the marked test instead of shading a later capture.
        return GrugForcedNormalization.matchesFound(
                GL11.glIsEnabled(GL11.GL_NORMALIZE),
                GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL),
                normalizeWasOn,
                rescaleNormalWasOn);
    }

    /**
     * Test only: whether the two capabilities are on right now, which the runner reads right after
     * the force, so an adapter that reports applying a state it did not turn on fails the marked
     * test (#1144).
     */
    @Override
    public boolean normalizationEnabledForTest() {
        return GL11.glIsEnabled(GL11.GL_NORMALIZE) & GL11.glIsEnabled(GL12.GL_RESCALE_NORMAL);
    }

    /**
     * Writes the timer's speed through the reflected field.
     *
     * <p>The field was made accessible when it was looked up, so reading it cannot fail and the
     * catch only reports a field that stopped being readable.
     */
    private static void setTimerSpeed(Minecraft minecraft, float speed) {
        ((Timer) readTimer(minecraft)).timerSpeed = speed;
    }

    @GrugGenerated("reflection: the field is accessible, so its catch cannot fire")
    private static Object readTimer(Minecraft minecraft) {
        try {
            return TIMER.get(minecraft);
        } catch (IllegalAccessException e) {
            throw Grug.fatal("Minecraft's Timer field cannot be read", e);
        }
    }

    @Override
    public boolean isWorldSettled() {
        // What the client owes is the simulated world's current block, not the one the test placed.
        Object simulated = getLevel();
        // Asked now as well as per frame: the runner runs up to 10 ticks for a frame, so a change a
        // test just made has not been in a frame yet, and only the renderer's list knows it.
        // The client and the server share this loader's world, so a placed block is there, but the
        // settlement still waits for a frame that shows it. Both answers are combined inside
        // GrugRenderSettle, whose unit test covers every combination, so no branch here depends on
        // whether an edit happened to be owed. The block answer refreshes from the world it is
        // given first, so it compares the client against what the world holds now. See #804.
        return renderSettle.settledNow(
                frameHadPendingWork(ModLoader.getMinecraftInstance()),
                blockSync.settled((x, y, z) -> getBlock(simulated, x, y, z)));
    }

    @Override
    public void onFrameRendered() {
        Minecraft minecraft = ModLoader.getMinecraftInstance();
        // The reader only runs while an edit is owed, and only a placement in a level records one,
        // so there is a world whenever it is asked. A frame drawn before a world exists owes
        // nothing and answers without reading.
        boolean blocksPending =
                blockSync.onFrame((x, y, z) -> getBlock(minecraft.theWorld, x, y, z));
        // A frame drawn from a view the mouse moved is not one a capture may read, and the view is
        // levelled again for the next frame. See GrugTestCamera and #1128. A joined client has no
        // test runner of its own, so its control run owns the view too (#1161). The two flags are
        // combined with a non-short-circuit | so a client run and a joined client both evaluate
        // the control-run answer.
        renderSettle.onFrame(
                GrugTestCamera.frameBusy(
                        frameHadPendingWork(minecraft),
                        testPlayerSnapped | GrugClientHooks.isControlRunActive(),
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
                    return ((EntityPlayer) player).rotationYaw;
                }

                @Override
                public float pitch(Object player) {
                    return ((EntityPlayer) player).rotationPitch;
                }

                @Override
                public float previousYaw(Object player) {
                    return ((EntityPlayer) player).prevRotationYaw;
                }

                @Override
                public float previousPitch(Object player) {
                    return ((EntityPlayer) player).prevRotationPitch;
                }

                @Override
                public void level(Object player) {
                    EntityPlayer target = (EntityPlayer) player;
                    target.rotationYaw = 0.0F;
                    target.rotationPitch = 0.0F;
                    target.prevRotationYaw = 0.0F;
                    target.prevRotationPitch = 0.0F;
                }
            };

    /**
     * Whether the frame that just finished still had work in it: a rebuild the renderer did not get
     * to, a window that was not yet at the size a capture reads, or a first-person hand that was
     * still moving.
     *
     * <p>This runs from the render tick, after the frame was drawn, so the renderer's list is what
     * that frame left behind: a chunk still in it and in view was not drawn, and the frame a
     * capture would read is therefore not the settled scene. The window check is the other half: a
     * run forces the window to the screenshot resolution, the game's display size only follows at
     * the end of the frame, and a frame drawn before that is at the old size and lays its GUI out
     * elsewhere. Both are sampled per frame rather than per game tick because the game draws fewer
     * frames than it runs ticks, so a tick-based answer can pass while the frame a capture reads is
     * still the older one. See #257.
     */
    private boolean frameHadPendingWork(Minecraft minecraft) {
        return GrugRenderSettle.frameHadPendingWork(
                        minecraft.displayWidth,
                        minecraft.displayHeight,
                        minecraft.renderGlobal,
                        minecraft.thePlayer,
                        chunkPending(minecraft))
                | handMoved(minecraft);
    }

    /**
     * Whether the client's first-person hand is still easing into place, so the frame it just drew
     * is not one a capture may read.
     *
     * <p>The hand is drawn in the world pass, before the screen is drawn over it, so an open screen
     * shows it behind its panel. Its height eases from 0 to 1 after the held item changes, which
     * the runner does before every test, and the number of frames between that change and a capture
     * varies with how the run's ticks fall, so two runs captured the hand at different points of
     * its rise (#961). A frame whose pair is equal draws the hand at rest, which is the pose every
     * golden was accepted with. See {@link GrugRenderSettle#handMoved}.
     */
    private boolean handMoved(Minecraft minecraft) {
        EntityPlayer player = minecraft.thePlayer;
        if (player == null
                || minecraft.entityRenderer == null
                || minecraft.entityRenderer.itemRenderer == null) {
            return false;
        }
        return GrugRenderSettle.handMoved(
                GrugHandAccess.equippedProgress(minecraft.entityRenderer.itemRenderer),
                GrugHandAccess.prevEquippedProgress(minecraft.entityRenderer.itemRenderer),
                0.0F,
                0.0F,
                player.getSwingProgress(1.0F),
                player.getSwingProgress(0.0F));
    }

    /**
     * Whether a chunk the renderer is going to rebuild is still waiting.
     *
     * <p>Only the chunks it can see within the distance {@code updateRenderers} itself compiles
     * within count: a chunk outside the frustum, or further away than that distance, is kept in the
     * list until the camera turns or moves, so a stationary run would otherwise never settle.
     */
    private boolean chunkPending(Minecraft minecraft) {
        EntityPlayer player = minecraft.thePlayer;
        for (Object entry : pendingRenderers(minecraft)) {
            WorldRenderer chunk = (WorldRenderer) entry;
            if (chunk.isInFrustum
                    && chunk.distanceToEntitySquared(player) <= COMPILE_DISTANCE_SQUARED) {
                return true;
            }
        }
        return false;
    }

    /**
     * The renderer's pending chunk rebuilds, or an empty list when there is no renderer or player
     * yet, which the guard above answers for anyway.
     */
    @GrugGenerated(
            "reflection: the field is looked up once and made accessible, so its catch cannot fire;"
                    + " the missing renderer or player arm is the frame before the world exists")
    private static List<?> pendingRenderers(Minecraft minecraft) {
        RenderGlobal renderGlobal = minecraft.renderGlobal;
        if (renderGlobal == null || minecraft.thePlayer == null) {
            return Collections.emptyList();
        }
        return renderersToUpdate(renderGlobal);
    }

    private static List<?> renderersToUpdate(RenderGlobal renderGlobal) {
        return (List<?>) readRenderers(renderGlobal);
    }

    @GrugGenerated("reflection: the field is accessible, so its catch cannot fire")
    private static Object readRenderers(RenderGlobal renderGlobal) {
        try {
            return RENDERERS_TO_UPDATE.get(renderGlobal);
        } catch (IllegalAccessException e) {
            throw Grug.fatal("Could not read RenderGlobal.worldRenderersToUpdate", e);
        }
    }

    @GrugGenerated(
            "the lookup of a field the reobfuscated game always carries, so its fatal cannot fire")
    private static Field findRenderersField() {
        try {
            return RenderGlobal.class.getDeclaredField("k");
        } catch (NoSuchFieldException e) {
            throw Grug.fatal("RenderGlobal has no worldRenderersToUpdate field", e);
        }
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void snapPlayerForTestOnClient() {
        Minecraft mc = ModLoader.getMinecraftInstance();
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            throw Grug.fatal(
                    "The test runner: there is no local player to snap to their block centre.");
        }
        snapPlayer(player);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void restorePlayerAfterTest() {
        // This is the client half; a dedicated server restores its own copy of the joined player
        // in its server adapter. The runner calls this for every test that ends, so a client whose
        // snap never ran must not report an error for those.
        if (!testPlayerSnapped) return;
        testPlayerSnapped = false;
        restorePlayerIfPresent(ModLoader.getMinecraftInstance().thePlayer);
    }

    @Override
    public void closeOpenScreenForTest() {
        // A joined client's controller leaves the open container in place when a screen closes
        // (PlayerControllerMP.func_20086_a is empty), so the player's craftingInventory would keep
        // pointing at the server-owned container whose window it just left. A later press from the
        // player's own inventory would then carry the wrong window id, and its prediction would
        // run on the wrong container (#1094).
        resetOpenContainer();
        // Not null: on a headless client the window is never active, so 1.2.5's focus check would
        // put its in-game menu up over the next frame and pause the world behind it. The empty
        // screen keeps the focus check quiet and the world ticking. See GrugEmptyScreen.
        ModLoader.getMinecraftInstance().displayGuiScreen(new GrugEmptyScreen());
    }

    /** Puts the player's open container back to their own inventory, which is window 0. */
    @GrugGenerated("the no-player arm: a test's own screen work has a player to do it with")
    private static void resetOpenContainer() {
        EntityPlayer player = ModLoader.getMinecraftInstance().thePlayer;
        if (player != null) {
            player.craftingInventory = player.inventorySlots;
        }
    }

    @Override
    public boolean isScreenOpenForTest() {
        // The empty screen close_screen displays is not an open screen as far as a test can tell.
        return GrugTestScreens.isOpen(
                ModLoader.getMinecraftInstance().currentScreen, GrugEmptyScreen.class);
    }

    @Override
    public long screenIdentityForTest() {
        // The empty screen close_screen displays is not a screen a test can see, so it has no
        // identity either, and the rule about that is the one above this asks.
        return isScreenOpenForTest()
                ? GrugTestScreens.identityOf(ModLoader.getMinecraftInstance().currentScreen)
                : 0;
    }

    @Override
    @GrugGenerated("the no-player arm: a run opens the screen of the player it was given")
    public void openInventory(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        if (player == null) return;
        // The player's own inventory is window 0, so the container a click on it belongs to is the
        // player's own, not whatever this client had open before (#1094).
        resetOpenContainer();
        ModLoader.getMinecraftInstance().displayGuiScreen(new GuiInventory(player));
    }

    @Override
    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        if (screen == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.press_screen_key: There is no screen open to press.");
            return false;
        }
        return GrugOverlayHost.pressKey(screen, key, ctrl, release);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run scrolls while the screen it just opened is up")
    public boolean scrollScreen(double x, double y, double delta) {
        GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        if (screen == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.scroll_screen: There is no screen open to scroll.");
            return false;
        }
        return GrugOverlayHost.scrollThroughScreen(screen, (int) x, (int) y, delta);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run presses while the screen it just opened is up")
    public boolean clickScreen(double x, double y, double button) {
        GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        if (screen == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.click_screen: There is no screen open to click.");
            return false;
        }
        return GrugOverlayHost.clickThroughScreen(screen, (int) x, (int) y, (int) button);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double screenWidthForTest() {
        GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        return screen == null ? 0.0 : screen.width;
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiLeftForTest() {
        GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        if (!(screen instanceof GuiContainer)) return 0.0;
        return GrugGuiAccess.guiLeft((GuiContainer) screen);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiWidthForTest() {
        GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        if (!(screen instanceof GuiContainer)) return 0.0;
        return GrugGuiAccess.xSize((GuiContainer) screen);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiTopForTest() {
        GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        if (!(screen instanceof GuiContainer)) return 0.0;
        return GrugGuiAccess.guiTop((GuiContainer) screen);
    }

    @Override
    @GrugGenerated("the no-screen arm: a run measures the screen it just opened")
    public double guiHeightForTest() {
        GuiScreen screen = ModLoader.getMinecraftInstance().currentScreen;
        if (!(screen instanceof GuiContainer)) return 0.0;
        return GrugGuiAccess.ySize((GuiContainer) screen);
    }

    @Override
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.use_block")) return;
        World world = (World) levelObj;
        EntityPlayer player = ModLoader.getMinecraftInstance().thePlayer;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no local player to right-click with.");
            return;
        }

        GrugBlocks.init();

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // 1.2.5 hands back a block id rather than a Block.
        int blockId = blockIdAt(world, x, y, z);
        Block block = blockAt(blockId);

        if (block != null) {
            rightClickBlock(
                    player,
                    world,
                    blockX,
                    blockY,
                    blockZ,
                    GrugUseBlockFace.face(testPlayerEye(player), blockX, blockY, blockZ));
        } else {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.use_block: There is no block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
        }
    }

    /**
     * Goes through the client's own right-click, which asks the block's activation first and the
     * held item after, so the test drives the same path a real click does and a block that declines
     * leaves the click to the item. This is not restricted to grug blocks: real mods open their GUI
     * from a server-side activation (BuildCraft's BlockAutoWorkbench does exactly this), and the
     * test only has to trigger it, not reproduce it.
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The server path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106).
     */
    private static void rightClickBlock(
            EntityPlayer player, World world, int blockX, int blockY, int blockZ, int side) {
        ModLoader.getMinecraftInstance()
                .playerController
                .onPlayerRightClick(
                        player,
                        world,
                        player.inventory.getCurrentItem(),
                        blockX,
                        blockY,
                        blockZ,
                        side);
    }

    @Override
    @GrugGenerated(
            "failure paths a healthy run cannot enter: no local player or client level, no block at"
                    + " the position, or a shape the overlay failed to reset")
    public boolean miningCrackForTest(Object levelObj, double x, double y, double z) {
        if (refuseWithoutAClient("Test.mining_crack")) return false;
        Minecraft mc = ModLoader.getMinecraftInstance();
        EntityPlayer player = mc.thePlayer;
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.mining_crack: There is no local player to mine with.");
            return false;
        }

        World world = (World) levelObj;
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        int blockId = world.getBlockId(blockX, blockY, blockZ);
        Block block =
                (blockId > 0 && blockId < Block.blocksList.length)
                        ? Block.blocksList[blockId]
                        : null;
        if (block == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.mining_crack: There is no block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
            return false;
        }

        boolean drew = drawMiningOverlay(player, blockX, blockY, blockZ);

        // The shape has to be a full cube again, or every later block of this type in the same
        // chunk compile is reshaped by the last box the overlay drew.
        if (!fullCube(block)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.mining_crack: The overlay left the block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + " shaped from ("
                            + block.minX
                            + ", "
                            + block.minY
                            + ", "
                            + block.minZ
                            + ") to ("
                            + block.maxX
                            + ", "
                            + block.maxY
                            + ", "
                            + block.maxZ
                            + ") instead of a full cube.");
            return false;
        }

        return drew;
    }

    /**
     * One overlay pass for the block, the way a frame draws it while the player holds the attack
     * button, at the middle of the break. Drawing between frames goes into the buffer the next
     * frame clears, and the state it leaves is the game's own.
     */
    private static boolean drawMiningOverlay(
            EntityPlayer player, int blockX, int blockY, int blockZ) {
        Minecraft mc = ModLoader.getMinecraftInstance();
        MovingObjectPosition hit =
                new MovingObjectPosition(
                        blockX,
                        blockY,
                        blockZ,
                        1,
                        Vec3D.createVectorHelper(blockX + 0.5, blockY + 0.5, blockZ + 0.5));
        GrugBoxRenderer.resetOverlayProbe();
        float previousDamage = mc.renderGlobal.damagePartialTime;
        mc.renderGlobal.damagePartialTime = 0.5F;
        mc.renderGlobal.drawBlockBreaking(player, hit, 0, player.inventory.getCurrentItem(), 0.0F);
        mc.renderGlobal.damagePartialTime = previousDamage;
        return GrugBoxRenderer.overlayDrew();
    }

    /**
     * Checks the rectangle and reads it, reporting {@code operation} as the thing that wanted it.
     * Both screenshot entry points share this so a bad coordinate is reported the same way either
     * way; only the name in front of the message differs.
     */
    private BufferedImage captureChecked(
            String operation, double x1, double y1, double x2, double y2) {
        // Validate the arguments before touching GL, so a typo'd coordinate is reported as a typo
        // rather than as a mysterious capture failure. The window check joins the chain last, so
        // the failures a test can cause are reported the same way they were before it.
        Minecraft mc = ModLoader.getMinecraftInstance();
        String bad = checkCoordinate("x1", x1, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y1", y1, GrugScreenshots.HEIGHT);
        if (bad == null) bad = checkCoordinate("x2", x2, GrugScreenshots.WIDTH);
        if (bad == null) bad = checkCoordinate("y2", y2, GrugScreenshots.HEIGHT);
        if (bad == null) bad = windowSizeError(mc);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, operation + ": " + bad);
            return null;
        }
        // The truncated values rather than the doubles, because they are what the width below is
        // computed from and what the message prints. Comparing the doubles let a rectangle through
        // that truncates to nothing, such as (590, 310, 590.5, 410), and the width then threw from
        // inside a zero-width image rather than being reported as the typo it was.
        int left = (int) x1;
        int top = (int) y1;
        int right = (int) x2;
        int bottom = (int) y2;
        if (right <= left || bottom <= top) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation
                            + ": the rectangle is empty, since ("
                            + right
                            + ","
                            + bottom
                            + ") is not below and right of ("
                            + left
                            + ","
                            + top
                            + ")");
            return null;
        }

        return captureRectangle(operation, left, top, right, bottom, mc.displayHeight);
    }

    /**
     * A message naming the window when it is not the size a capture reads, or null.
     *
     * <p>A defensive safety net rather than the primary sizing mechanism: R forces 1280x720 around
     * a test run. If that ever stops happening, a mismatch here is much easier to diagnose than a
     * screen of subtly wrong pixels.
     */
    @GrugGenerated("the window arm: a run sizes the window to the capture size before a test")
    private static String windowSizeError(Minecraft mc) {
        if (mc.displayWidth != GrugScreenshots.WIDTH
                || mc.displayHeight != GrugScreenshots.HEIGHT) {
            return "the window is "
                    + mc.displayWidth
                    + "x"
                    + mc.displayHeight
                    + ", but screenshot tests are captured at "
                    + GrugScreenshots.WIDTH
                    + "x"
                    + GrugScreenshots.HEIGHT;
        }
        return null;
    }

    /**
     * Reads the rectangle back into the buffer, answering a report when the read failed or GL
     * reported an error, or null when the pixels are the frame's.
     */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private static String readPixels(
            String operation, int x1, int glY, int width, int height, ByteBuffer pixels) {
        try {
            GlBridge.readPixels(x1, glY, width, height, pixels);
        } catch (RuntimeException e) {
            return operation + ": the pixel readback failed unexpectedly: " + e;
        }
        int glError = GlBridge.getError();
        if (glError != GlBridge.noError()) {
            return operation
                    + ": glReadPixels failed with GL error 0x"
                    + Integer.toHexString(glError)
                    + ", so the capture cannot be trusted. A multisampled or otherwise "
                    + "unreadable framebuffer is the usual cause.";
        }
        return null;
    }

    /**
     * The client's tick timer, which decides how many ticks {@code Minecraft.run} takes for a
     * frame. The field is private and the class is the game's own, so reflection is the only way
     * in, and it is found by its type and not by the notch name {@code X}, so a name that moves
     * with a snapshot cannot silently miss it: {@code Minecraft} holds exactly one {@code Timer}.
     */
    private static final Field TIMER = timerField();

    /**
     * Finds the timer field by its type.
     *
     * <p>The class always holds one, so the loop never runs out of fields and the fatal after it
     * cannot fire; it is there so a different game build fails loudly instead of returning null.
     */
    private static Field timerField() {
        Field field = findTimerField();
        field.setAccessible(true);
        return field;
    }

    /**
     * {@code RenderGlobal}'s pending chunk rebuilds, which its own {@code updateRenderers} drains
     * inside the frame before the chunks are drawn.
     *
     * <p>The field is private and the class is the game's own, so reflection is the only way in
     * from here: 1.2.5 has no Mixin, and the class comes from the pinned MCP snapshot rather than
     * from a patch this repository keeps. The name looked up is the notch one the reobfuscated game
     * actually carries, which the snapshot's {@code conf/client.srg} names as {@code l/k} and maps
     * to {@code RenderGlobal/field_1446_m}, the name {@code conf/fields.csv} gives to {@code
     * worldRenderersToUpdate}; the MCP name exists only while this class is compiled, which is why
     * looking it up found nothing. The lookup happens once, and a missing field is a broken build
     * rather than a condition to carry on from, which is why it is fatal.
     */
    private static final Field RENDERERS_TO_UPDATE = renderersToUpdateField();

    private static Field renderersToUpdateField() {
        Field field = findRenderersField();
        field.setAccessible(true);
        return field;
    }

    @Override
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        return captureChecked("Screenshot.capture", x1, y1, x2, y2);
    }

    /**
     * The forwarded capture of the test-control channel (#1040), taken by a joined client on its
     * own tick with the window already checked against the screenshot size.
     */
    @GrugGenerated("a joined client's forwarded capture, which only the multiplayer job runs")
    public BufferedImage captureForControl(int x1, int y1, int x2, int y2, Minecraft mc) {
        BufferedImage image =
                captureRectangle("Screenshot.capture", x1, y1, x2, y2, mc.displayHeight);
        describeControlCapture(image, mc);
        return image;
    }

    private static int controlCaptureCount;

    /**
     * Under GRUG_CI, saves the frame a forwarded capture returned and logs what this client's world
     * looks like, to tell a wall that never arrived from a camera that looks elsewhere (#1040).
     */
    @GrugGenerated(
            "a diagnostic of the multiplayer job's forwarded capture, only read from its artifact")
    private static void describeControlCapture(BufferedImage image, Minecraft mc) {
        if (!"true".equals(System.getenv("GRUG_CI")) && System.getenv("GRUG_CI_CONNECT") == null) {
            return;
        }
        {
            int n = ++controlCaptureCount;
            java.io.File dir = new java.io.File(mc.mcDataDir, "control-captures");
            dir.mkdirs();
            if (image != null) {
                try {
                    if (!javax.imageio.ImageIO.write(
                            image, "png", new java.io.File(dir, "capture-" + n + ".png"))) {
                        throw new IllegalStateException("no PNG writer for the forwarded capture");
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            net.minecraft.src.EntityPlayer p = mc.thePlayer;
            net.minecraft.src.World w = mc.theWorld;
            double yawRad = Math.toRadians(p.rotationYaw);
            double pitchRad = Math.toRadians(p.rotationPitch);
            double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
            double dy = -Math.sin(pitchRad);
            double dz = Math.cos(yawRad) * Math.cos(pitchRad);
            int px = (int) Math.floor(p.posX);
            int py = (int) Math.floor(p.posY);
            int pz = (int) Math.floor(p.posZ);
            java.util.TreeMap<Integer, Integer> ids = new java.util.TreeMap<>();
            for (int x = px - 6; x <= px + 6; x++) {
                for (int y = py - 6; y <= py + 6; y++) {
                    for (int z = pz - 6; z <= pz + 6; z++) {
                        int id = w.getBlockId(x, y, z);
                        if (id != 0) ids.merge(id, 1, Integer::sum);
                    }
                }
            }
            String hit = "none within 12";
            for (double t = 0.5; t <= 12; t += 0.25) {
                int id =
                        w.getBlockId(
                                (int) Math.floor(p.posX + dx * t),
                                (int) Math.floor(p.posY + dy * t),
                                (int) Math.floor(p.posZ + dz * t));
                if (id != 0) {
                    hit = "block " + id + " at distance " + t;
                    break;
                }
            }
            System.out.println(
                    "[GRUG CONTROL] capture "
                            + n
                            + ": player "
                            + p.posX
                            + " "
                            + p.posY
                            + " "
                            + p.posZ
                            + " yaw "
                            + p.rotationYaw
                            + " pitch "
                            + p.rotationPitch
                            + ", screen "
                            + (mc.currentScreen == null
                                    ? "none"
                                    : mc.currentScreen.getClass().getName())
                            + ", look ray hits "
                            + hit
                            + ", non-air block ids within 6 of the player (id=count) "
                            + ids);
        }
    }

    /** Returns null if the coordinate is in range, or a message naming it if it is not. */
    private static String checkCoordinate(String name, double value, int limit) {
        if (value < 0 || value > limit) {
            return name
                    + " is "
                    + (int) value
                    + ", which is outside the "
                    + GrugScreenshots.WIDTH
                    + "x"
                    + GrugScreenshots.HEIGHT
                    + " frame (0 to "
                    + limit
                    + ")";
        }
        return null;
    }

    /** Builds the image from a readback, top row first. */
    private static BufferedImage buildImage(ByteBuffer pixels, int width, int height) {
        // Absolute gets, because glReadPixels leaves the buffer's position wherever it likes
        // (LWJGL rewinds it before the read), so its position and limit cannot be relied on
        // afterwards. glReadPixels also fills the buffer bottom row first, hence the reversed
        // row index: the image's row 0 has to be the crop's top row, which is the buffer's
        // last.
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int index = (row * width + column) * 4;
                int r = pixels.get(index) & 0xFF;
                int g = pixels.get(index + 1) & 0xFF;
                int b = pixels.get(index + 2) & 0xFF;
                // index + 3 is alpha, which the framebuffer may not even have; unused.
                image.setRGB(column, height - 1 - row, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    /**
     * Reads back a rectangle of the frame that is currently on screen.
     *
     * <p>This runs from Minecraft.tick(), i.e. after the previous frame was rendered but before
     * this one is drawn, so the frame of interest is whatever was drawn most recently. On LWJGL 2
     * that is the back buffer, which is also GL's default read buffer.
     *
     * <p>Coordinate convention: the rectangle (x1,y1,x2,y2) is in screen space with the origin at
     * the top left, but GL's origin is the bottom left. A screen row y is therefore GL row
     * height-1-y, which puts the bottom edge of the crop at GL row height-y2. glReadPixels fills
     * the buffer bottom row first, so rows are written into the image in reverse to land the right
     * way up.
     */
    @GrugGenerated("screenshot/GL integration: failure paths a healthy run cannot enter")
    private static BufferedImage captureRectangle(
            String operation, int x1, int y1, int x2, int y2, int windowHeight) {
        int width = x2 - x1;
        int height = y2 - y1;
        int glY = windowHeight - y2;

        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);

        String failure = readPixels(operation, x1, glY, width, height, pixels);
        if (failure != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, failure);
            return null;
        }

        try {
            return buildImage(pixels, width, height);
        } catch (RuntimeException e) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    operation + ": could not build an image from the readback: " + e);
            return null;
        }
    }
}
