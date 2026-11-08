package net.grug.minecraft.forge125;

import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugClientSavedNumbers;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugLightSync;
import net.grug.minecraft.core.GrugOverlayCalls;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugScreenCalls;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestOrigin;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.forge125.client.GrugContainer;
import net.grug.minecraft.forge125.control.GrugControlServer125;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.minecraft.src.AxisAlignedBB;
import net.minecraft.src.Block;
import net.minecraft.src.EntityPlayer;
import net.minecraft.src.EntityPlayerMP;
import net.minecraft.src.EnumSkyBlock;
import net.minecraft.src.IInventory;
import net.minecraft.src.Item;
import net.minecraft.src.ItemStack;
import net.minecraft.src.TileEntity;
import net.minecraft.src.World;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

/**
 * The adapter for a 1.2.5 dedicated server: everything that is not about which process this is
 * lives in {@link Grug125Adapter}, and what a server answers differently (the level and the player
 * handles, no frames, no sound, no capture) is here. The server has no client, so the client-only
 * functions refuse themselves through {@code ModLoaderAdapter.refuseWithoutAClient}; {@code
 * useBlockForTest} is not one of them and is overridden, so the server right-clicks in its own
 * world, the world the test reads (#1090). {@code GUI.open} is not one either: the server owns the
 * container and the client shows it (#1098). The screen calls and the clicks on them are forwarded
 * to that client (#1094), and the cursor calls are answered from this server's own container, which
 * is the one a click is applied to.
 *
 * <p>Excluded from coverage because only the dispatch-only multiplayer job launches a server
 * (#166).
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class Grug125ServerAdapter extends Grug125Adapter {

    /** A player's eye is this far above their feet. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * The screen calls this server makes, which its joined client performs: a server has no screen
     * of its own, so every one of them is a round trip to the client of the player who started the
     * run (#1094). A failure is a host function error, which fails the test.
     */
    private final GrugScreenCalls.Forwarded screens =
            new GrugScreenCalls.Forwarded(
                    GrugControlServer125::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The camera reads this server makes, which its joined client answers with its own camera: a
     * dedicated server has none, so a test that asserts where the client draws from asks there
     * (#1161). A failure is a host function error, which fails the test.
     */
    private final GrugTestCamera.Forwarded camera =
            new GrugTestCamera.Forwarded(
                    GrugControlServer125::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The saved-number reads this server makes, which its joined client answers from its own copy
     * of the block entity: a dedicated server holds the simulated copy, not the one the client's
     * frame pass reads (#1162).
     */
    private final GrugClientSavedNumbers.Forwarded savedNumbers =
            new GrugClientSavedNumbers.Forwarded(
                    GrugControlServer125::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The light comparisons this server makes, which its joined client answers from its own copy of
     * the section: a dedicated server holds the simulated copy, not the one the client's frame pass
     * bakes the room from (#1240).
     */
    private final GrugClientLight.Forwarded clientLight =
            new GrugClientLight.Forwarded(
                    GrugControlServer125::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The overlay reads this server makes, which its joined client answers about the frame it
     * draws: an overlay belongs to a client's frame pass, so a server never records one (#1099). A
     * failure is a host function error, which fails the test.
     */
    private final GrugOverlayCalls.Forwarded overlays =
            new GrugOverlayCalls.Forwarded(
                    GrugControlServer125::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * How thin the column is that asks which collision boxes cover the player's own point. It only
     * has to be non-zero so a box whose edge is exactly on the point still intersects it.
     */
    private static final double POINT_EDGE = 1e-9;

    @Override
    public GrugSide getSide() {
        return GrugSide.DEDICATED_SERVER;
    }

    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        EntityPlayerMP player = (EntityPlayerMP) playerObj;
        IInventory inventory = (IInventory) blockEntityObj;
        TileEntity blockEntity = (TileEntity) blockEntityObj;
        GrugGuiBuilder layout = (GrugGuiBuilder) guiBuilderObj;

        // Whatever container the player had open is closed first, as vanilla does before it
        // installs the next one, so nothing keeps sending that container's slots.
        player.closeCraftingGui();

        GrugContainer container = new GrugContainer(player, inventory, layout);
        int windowId = nextWindowId();
        container.windowId = windowId;
        player.craftingInventory = container;

        // The layout goes first: the client builds its own container from it, numbered the same
        // way, and the slot contents onCraftGuiOpened sends below then land in that container. A
        // client whose copy of the block entity has not arrived yet builds it on a placeholder
        // (#425) and is filled by the same packets.
        player.playerNetServerHandler.sendPacket(
                new GrugScreenPacket(
                        windowId,
                        blockEntity.xCoord,
                        blockEntity.yCoord,
                        blockEntity.zCoord,
                        layout));
        container.onCraftGuiOpened(player);
    }

    /**
     * The window id the next {@code GUI.open} installs its container under, one after another as
     * vanilla's own counter does. It must not be 0: the client treats window 0 as the player's own
     * inventory, so a container opened under it would never receive the slot contents.
     */
    private static int nextWindowId() {
        windowId = windowId % 99 + 1;
        return windowId;
    }

    private static int windowId = 0;

    // The screen functions a client adapter implements. The ones a test makes on a screen are
    // forwarded to the client of the player who started the run (#1094); GUI.open builds the block
    // entity's container here, so the client's screen is the one this server's slots belong to
    // (#1098). The pointer calls are the menu's, so this server answers them itself: a click is
    // applied to this server's own container, and a carried stack only the client knows would not
    // be the one the click uses.

    /**
     * Opened by the client of the player who started the run, whose own inventory it is (#1094).
     *
     * <p>The container this server has open is closed first, so both sides are on the player's own
     * inventory, window 0, by the time the client's screen is up: a press there carries window 0,
     * which this server only accepts as its own player's inventory container.
     */
    @Override
    public void openInventory(Object playerObj) {
        if (playerObj instanceof EntityPlayerMP) {
            closeOpenContainer((EntityPlayerMP) playerObj);
        }
        screens.openInventory();
    }

    @Override
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        return screens.pressScreenKey(key, ctrl, release);
    }

    @Override
    public boolean scrollScreen(double x, double y, double delta) {
        return screens.scrollScreen(x, y, delta);
    }

    /**
     * Pressed on the client's screen, by the client of the player who started the run (#1094).
     *
     * <p>The screen is the block entity's container, which this server built in {@code GUI.open}
     * under the window id it sent to that client, so the click packet the press sends names this
     * server's container and lands in this server's slots. It is applied by {@link
     * GrugControlServer#applyClientWork} on a later tick, never inside this call, because the
     * server thread is frozen here and cannot read it.
     */
    @Override
    public boolean clickScreen(double x, double y, double button) {
        return screens.clickScreen(x, y, (int) button);
    }

    /**
     * The carried stack of this server's player, which is the one a click uses (#1094).
     *
     * <p>1.2.5 keeps the cursor in the player's inventory, and the click applies to the player's
     * container, so this answer and a click agree. The inherited answer reads the cursor of a
     * client's screen, which a dedicated server has none of.
     */
    @Override
    public Object carriedItem() {
        EntityPlayerMP player = serverPlayer();
        if (player == null) return null;
        ItemStack stack = player.inventory.getItemStack();
        return stack == null ? null : stack.getItem();
    }

    /**
     * Sets the carried stack this server's next click uses, and tells the client, so a frame it
     * draws and a click it predicts both agree with it (#1094).
     */
    @Override
    public void setCarriedItem(Object itemObj) {
        EntityPlayerMP player = serverPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.set_carried_item: There is no player to set a cursor on.");
            return;
        }
        player.inventory.setItemStack(itemObj == null ? null : new ItemStack((Item) itemObj));
        // The cursor is not a slot this server's container sends, so the click's own slot update
        // would leave the client's copy stale: window -1 slot -1 is the cursor, which is what the
        // game's own updateHeldItem sends when a click changes it.
        player.updateHeldItem();
    }

    /** Clears the carried stack, and the client's copy of it (#1094). */
    @Override
    public void deleteCarriedItem() {
        EntityPlayerMP player = serverPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "OverlayClickContext.delete_carried_item: There is no player to clear a cursor"
                            + " on.");
            return;
        }
        player.inventory.setItemStack(null);
        player.updateHeldItem();
    }

    /** The player who started the run, or the first joined one, whose container a click uses. */
    @GrugGenerated("the no-player arm: a test's own cursor work happens while a player is joined")
    private static EntityPlayerMP serverPlayer() {
        EntityPlayerMP starter = GrugControlServer125.starterPlayer();
        return starter != null ? starter : GrugServerRun.firstPlayer();
    }

    /**
     * Closes the container this server has open, the way the game's own close packet does, so both
     * sides are back on the player's own inventory, window 0 (#1094).
     */
    @GrugGenerated("the no-container arm: a run's screen work happens with one container open")
    private static void closeOpenContainer(EntityPlayerMP player) {
        if (player.craftingInventory != player.inventorySlots) {
            player.closeCraftingGui();
        }
    }

    @Override
    public double screenWidthForTest() {
        return screens.read(GrugScreenCalls.SCREEN_WIDTH);
    }

    @Override
    public double guiLeftForTest() {
        return screens.read(GrugScreenCalls.GUI_LEFT);
    }

    @Override
    public double guiWidthForTest() {
        return screens.read(GrugScreenCalls.GUI_WIDTH);
    }

    @Override
    public double guiTopForTest() {
        return screens.read(GrugScreenCalls.GUI_TOP);
    }

    @Override
    public double guiHeightForTest() {
        return screens.read(GrugScreenCalls.GUI_HEIGHT);
    }

    @Override
    public Object getLevel() {
        return GrugServerRun.world();
    }

    /**
     * The first player who joined. A server has no local player, so a test that wants one to build
     * fixtures around is given a connected one: the tests share one world and build in a room
     * around the test player, so which one only decides whose position {@code Test.get_origin}
     * reports.
     */
    @Override
    public Object testPlayer() {
        // The player who started the run, when one has: forwarded calls use their screen (#1040).
        EntityPlayerMP starter = GrugControlServer125.starterPlayer();
        return starter != null ? starter : GrugServerRun.firstPlayer();
    }

    /**
     * The eye of the test player. A server player keeps the feet in {@code posY} where the client
     * keeps the eye, so the eye is rebuilt from the bottom of the bounding box, rounded to a
     * millionth to shed the arithmetic's last bits, which is what the client's {@code posY} is.
     */
    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        return new Vec3(
                player.posX,
                Math.rint(player.boundingBox.minY * 1e6) / 1e6 + EYE_HEIGHT,
                player.posZ);
    }

    /**
     * Ready once the world around the player is loaded, the player has landed and the player is not
     * inside a block. The test origin is read from the player's position, and a player still
     * falling when the first test starts gives an origin whose height differs from run to run
     * (#1052).
     *
     * <p>{@code onGround} alone is not enough: the server copies it from the client's movement
     * packets, and a client that fell through the terrain while its chunks were arriving reports a
     * position a fraction of a block inside the ground, which the server accepts because the box
     * its collision check tests is contracted by 0.0625. From there the client's own collision
     * never reports support, so the wait timed out with the client in the world and the run already
     * started (#1136, the rule #1095 added to b1.7.3-ornithe). {@link GrugPlayerFloor#mayStart} is
     * that rule, so the server's own ground is enough and a player inside a block is never
     * accepted.
     */
    @Override
    public boolean isWorldReady(Object playerObj) {
        EntityPlayer player = (EntityPlayer) playerObj;
        double feet = player.boundingBox.minY;
        return super.isWorldReady(playerObj)
                && GrugPlayerFloor.mayStart(
                        solidBlocks(getLevel()), player.posX, feet, player.posZ, player.onGround);
    }

    /**
     * The collision shapes of a level's blocks, as {@link GrugPlayerFloor} reads them: the top of
     * the highest box that covers the player's own point, which is what the game's own collision
     * meets there. A slab's floor is half a block below its block top, a stair's lower step is not
     * its upper one, and a block with no collision box at all, such as a torch or a plant, is not a
     * floor (#1102, #1136).
     *
     * <p>This generation asks a block for its boxes through {@code getCollidingBoundingBoxes}, with
     * the thin column through the player's own point as the area, so a box is filtered by where the
     * player stands rather than by its block position. The block's bounds follow its metadata, so
     * they are updated for this position first, the way the game updates them before it draws or
     * ray traces the block.
     */
    @SuppressWarnings("rawtypes")
    public static GrugPlayerFloor.BlockSource solidBlocks(Object levelObj) {
        World world = (World) levelObj;
        return (x, y, z, pointX, pointZ) -> {
            Block block = blockAt(world, x, y, z);
            if (block == null) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
            block.setBlockBoundsBasedOnState(world, x, y, z);
            AxisAlignedBB point =
                    AxisAlignedBB.getBoundingBox(
                            pointX - POINT_EDGE,
                            y,
                            pointZ - POINT_EDGE,
                            pointX + POINT_EDGE,
                            y + 1.0,
                            pointZ + POINT_EDGE);
            ArrayList boxes = new ArrayList();
            block.getCollidingBoundingBoxes(world, x, y, z, point, boxes);
            double top = GrugPlayerFloor.BlockSource.NO_COLLISION;
            for (Object boxObj : boxes) {
                top = Math.max(top, ((AxisAlignedBB) boxObj).maxY - y);
            }
            return top;
        };
    }

    /** Asked of the client of the player who started the run, with the world frozen (#1040). */
    @Override
    public boolean hasSound(String path) {
        try {
            return GrugControlServer125.server().hasSound(path);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "has_sound: " + e.getMessage());
            return false;
        }
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        try {
            GrugControlServer125.server().playUiSound(path, volume, pitch);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "play_ui_sound: " + e.getMessage());
        }
    }

    @Override
    public void onFrameRendered() {}

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlServer125.server().hasStarter()) {
            return true;
        }
        try {
            return GrugControlServer125.server().worldSettled();
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.world_settled: " + e.getMessage());
            return true;
        }
    }

    @Override
    public void forgetSettledFrames() {}

    /**
     * Closes the joined client's screen (#1094). This server has no screen of its own: 1.2.5's GUI
     * is a client-only object, so the call is the client's, and the client of the player who
     * started the run performs it.
     *
     * <p>The container this server opened is closed too, so both sides are on the player's own
     * inventory, window 0, when the call returns. Nothing else does it: the client's controller
     * sends no close packet for the screen it leaves, so a container would otherwise stay open on
     * this server and accept no press from any screen that follows.
     */
    @Override
    public void closeOpenScreenForTest() {
        EntityPlayerMP player = serverPlayer();
        if (player != null) {
            closeOpenContainer(player);
        }
        screens.closeScreen();
    }

    /** Whether the joined client of the player who started the run has a screen open (#1094). */
    @Override
    public boolean isScreenOpenForTest() {
        return screens.screenOpen();
    }

    @Override
    public boolean setGameSpeed(double speed) {
        GrugServerRun.setGameSpeed(GrugGameSpeed.wholeSteps(speed));
        return true;
    }

    @Override
    public void snapPlayerForTestOnClient() {}

    @Override
    public void restorePlayerAfterTest() {}

    /**
     * The tests listed in {@code forge125-server-unsupported-tests.txt}, which says why. The name
     * carries the loader because this layer is at the universal jar's root, and a root file of the
     * bare name would shadow the nested layers' own lists on the class path.
     */
    @Override
    public Set<String> serverUnsupportedTests() {
        try (InputStream in =
                Grug125ServerAdapter.class.getResourceAsStream(
                        "/forge125-server-unsupported-tests.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "forge125-server-unsupported-tests.txt is not in the jar.");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return GrugTestRunner.parseTestList(
                    new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not read forge125-server-unsupported-tests.txt.", e);
        }
    }

    @Override
    public boolean miningCrackForTest(Object levelObj, double x, double y, double z) {
        refuseWithoutAClient("Test.mining_crack");
        return false;
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's {@code PlayerControllerMP} sends a use
     * packet the server thread is blocked inside this call and so cannot process. So the click is
     * made here instead, through the server's own interaction manager, {@code ItemInWorldManager},
     * which is what {@code NetServerHandler.handlePlace} reaches for a client's use packet: the
     * block's activation first, and the held item's use against it when the block declines.
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The client path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106).
     */
    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        EntityPlayerMP player = (EntityPlayerMP) testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        if (blockAt(world, blockX, blockY, blockZ) == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.use_block: There is no block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
            return;
        }

        player.itemInWorldManager.activeBlockOrUseItem(
                player,
                world,
                player.inventory.getCurrentItem(),
                blockX,
                blockY,
                blockZ,
                GrugUseBlockFace.face(testPlayerEye(player), blockX, blockY, blockZ));
    }

    /**
     * Forwards the capture to the joined client of the player who started the run and waits for the
     * answer with the world frozen (#1040, option A). Arguments are checked first, as on a client.
     */
    @Override
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        String bad = GrugScreenshots.rectangleProblem(x1, y1, x2, y2);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.capture: " + bad);
            return null;
        }
        try {
            return GrugControlServer125.server()
                    .capture(captureEye(), (int) x1, (int) y1, (int) x2, (int) y2);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.capture: " + e.getMessage());
            return null;
        }
    }

    /**
     * The eye the capture is drawn from: the origin the running test's room was built above, less
     * {@link GrugTestOrigin#LIFT}, or null when this side has no player to measure one from.
     */
    private Vec3 captureEye() {
        Vec3 origin = GrugTestState.captureTestOrigin(this::getTestOrigin);
        return origin == null ? null : GrugTestOrigin.eyeOf(origin);
    }

    /** The joined client's own camera, which is where its captures are drawn from (#1161). */
    @Override
    public Vec3 testCamera() {
        return camera.camera();
    }

    /** The joined client's own copy of a saved number, which is the copy its frame pass reads. */
    @Override
    public double savedNumberOnClient(double x, double y, double z, String name) {
        return savedNumbers.savedNumber(x, y, z, name);
    }

    /**
     * Whether the joined client's copy holds the light this server's world has in the section the
     * position falls in (#1240), or true where this server cannot verify it.
     *
     * <p>A position in a section the last room relight swept answers true, which is the
     * cannot-verify answer: this version's incremental engine leaves the client's copy of those
     * sections at a field that depends on the changes it saw, while this server's {@link
     * #relightRoom} ends at the one stable field the blocks define, so the two copies are not
     * expected to agree there. The test's wait therefore stays a no-op for the room on this loader,
     * and #1207 records that deviation. A position in a section outside the box, such as one two
     * thousand blocks away that the joined client was never sent, is compared normally.
     *
     * <p>A server with no starter has no client frame to protect and answers true, as {@link
     * #isWorldSettled} does. No light-engine gate is needed: this version computes its light on the
     * server tick, so the tick that placed the test's blocks has already worked it out.
     */
    @Override
    public boolean lightApplied(double x, double y, double z) {
        Object level = getLevel();
        if (level == null
                || !GrugControlServer125.server().hasStarter()
                || relitSections.covers(x, y, z)) {
            return true;
        }
        World world = (World) level;
        long simulated =
                GrugLightSync.lightFingerprint(
                        ((int) Math.floor(x)) >> 4,
                        ((int) Math.floor(y)) >> 4,
                        ((int) Math.floor(z)) >> 4,
                        (lightX, lightY, lightZ) ->
                                world.getSavedLightValue(
                                        EnumSkyBlock.Block, lightX, lightY, lightZ));
        return clientLight.lightApplied(x, y, z, simulated);
    }

    /**
     * Whether the joined client draws overlays at all, which a server's own answer would deny
     * (#1099): the loader marks support where it registers the screen hooks, and a server has none.
     */
    @Override
    public boolean overlaySupportedForTest() {
        return overlays.supported();
    }

    /**
     * Whether a frame of the joined client drew the overlay of the running test's mod on the screen
     * it has open (#1099): the mark belongs to that client's frame pass, not to this server's.
     */
    @Override
    public boolean overlayDrawnForTest(String mod) {
        return overlays.drawn(mod);
    }

    /** This server forwards a test's client-only calls to the player who started the run. */
    @Override
    public boolean forwardsClientCalls() {
        return true;
    }
}
