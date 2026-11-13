package net.grug.minecraft.stationapi;

import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugClientSavedNumbers;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugLightSync;
import net.grug.minecraft.core.GrugOverlayCalls;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugScreenCalls;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestOrigin;
import net.grug.minecraft.core.GrugTestRunner;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.entity.GrugSavedDataStation;
import net.grug.minecraft.stationapi.control.GrugControlStation;
import net.grug.minecraft.stationapi.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The adapter for a b1.7.3 dedicated server, which is the same loader as {@link StationApiAdapter}
 * in a second process rather than a second mode.
 *
 * <p>It extends the client adapter because everything that is not about which process this is, is
 * the same code: the block registry, the recipes and the inventory and entity host functions have
 * no client in them. The client adapter already loads on a server (it is what {@code InitListener}
 * has always created), so extending it adds no class a server cannot load. What a server cannot
 * inherit is the pair of handles to the level and the player, and the answers about screens and
 * frames, and those are what this replaces. The client-only functions are inherited and each one
 * refuses itself through {@code ModLoaderAdapter.refuseWithoutAClient}, except the ones whose
 * subject is a screen or a cursor: those are forwarded to the joined client, or answered from this
 * server's own screen handler, so a GUI test can run here (#1098). {@code useBlockForTest} is not a
 * client-only function and is overridden: a click is not a client-only function, so the server
 * right-clicks in its own world, the world the test reads (#1090).
 *
 * <p>Excluded from coverage because only the dispatch-only multiplayer job launches a server (#167
 * stage 4). See #165.
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class ServerStationApiAdapter extends StationApiAdapter {

    /** A player's eye is this far above their feet. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * The screen calls this server makes, which its joined client performs: a server has no screen
     * of its own, so every one of them is a round trip to the client of the player who started the
     * run (#1094). A failure is a host function error, which fails the test.
     */
    private final GrugScreenCalls.Forwarded screens =
            new GrugScreenCalls.Forwarded(
                    GrugControlStation::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The camera reads this server makes, which its joined client answers with its own camera: a
     * dedicated server has none, so a test that asserts where the client draws from asks there
     * (#1161). A failure is a host function error, which fails the test.
     */
    private final GrugTestCamera.Forwarded camera =
            new GrugTestCamera.Forwarded(
                    GrugControlStation::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The saved-number reads this server makes, which its joined client answers from its own copy
     * of the block entity: a dedicated server holds the simulated copy, not the one the client's
     * frame pass reads (#1162).
     */
    private final GrugClientSavedNumbers.Forwarded savedNumbers =
            new GrugClientSavedNumbers.Forwarded(
                    GrugControlStation::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The light comparisons this server makes, which its joined client answers from its own copy of
     * the section: a dedicated server holds the simulated copy, not the one the client's frame pass
     * bakes the room from (#1240).
     */
    private final GrugClientLight.Forwarded clientLight =
            new GrugClientLight.Forwarded(
                    GrugControlStation::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The overlay reads this server makes, which its joined client answers about the frame it
     * draws: an overlay belongs to a client's frame pass, so a server never records one (#1099). A
     * failure is a host function error, which fails the test.
     */
    private final GrugOverlayCalls.Forwarded overlays =
            new GrugOverlayCalls.Forwarded(
                    GrugControlStation::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * How thin the column is that asks which collision boxes cover the player's own point. It only
     * has to be non-zero so a box whose edge is exactly on the point still intersects it.
     */
    private static final double POINT_EDGE = 1e-9;

    /**
     * The client keeps its own copy of a grug block entity, so a saved number the script writes on
     * this server is sent to it (#1093). A singleplayer game has one world and installs none.
     */
    public ServerStationApiAdapter() {
        GrugSavedDataSync.install(new GrugSavedDataStation());
    }

    @Override
    public Object getLevel() {
        return GrugServerRun.world();
    }

    /**
     * The first player who joined.
     *
     * <p>A server has no local player, so a test that wants one to build fixtures around and to
     * right-click with is given one of the connected players. Which one does not matter: the tests
     * share one world and build in a room around the test player, so the choice only decides whose
     * position {@code Test.get_origin} reports. The inherited origin goes through {@link
     * #testPlayerEye}, so choosing the player here is all a server has to do.
     */
    @Override
    public Object testPlayer() {
        // Deliberately an untyped list: Fabric Loader will not define a class the game marked
        // server-only in a client, so no reference the verifier has to resolve may name one here.
        // The player who started the run, when one has: forwarded calls use their screen (#1040).
        PlayerEntity starter = GrugControlStation.starterPlayer();
        if (starter != null) {
            return starter;
        }
        List<?> players = GrugServerRun.players();
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's interaction manager sends a use packet the
     * server thread is blocked inside this call and so cannot process. So the click is made here
     * instead, in the same order the game's server uses for a client's use packet: the block's
     * {@code onUse} first, and the held item's {@code useOnBlock} when the block declines.
     *
     * <p>This generation's client jar, which is what this layer compiles against, holds no server
     * interaction manager to call, so the two calls its {@code interactBlock} makes are made here
     * in the same order.
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The client path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106). This generation's {@code Block.onUse} takes no face, so it is the held item's {@code
     * useOnBlock} that sees it.
     */
    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // World.getBlockId() hands back a block id rather than a Block in this generation.
        int blockId = world.getBlockId(blockX, blockY, blockZ);
        Block block =
                (blockId >= 0 && blockId < Block.BLOCKS.length) ? Block.BLOCKS[blockId] : null;

        if (!(block instanceof GrugBlock)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "Test.use_block: There is no grug block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
            return;
        }

        if (!block.onUse(world, blockX, blockY, blockZ, player)) {
            ItemStack held = player.inventory.getSelectedItem();
            if (held != null) {
                held.useOnBlock(
                        player,
                        world,
                        blockX,
                        blockY,
                        blockZ,
                        GrugUseBlockFace.face(testPlayerEye(player), blockX, blockY, blockZ));
            }
        }
    }

    /**
     * The eye of the test player. A server player keeps the feet in {@code y} and a client keeps
     * the eye, so the eye is rebuilt from the bottom of the bounding box, rounded to a millionth to
     * shed the arithmetic's last bits, which is what the client's {@code y} is.
     */
    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        return new Vec3(
                player.x, Math.rint(player.boundingBox.minY * 1e6) / 1e6 + EYE_HEIGHT, player.z);
    }

    /**
     * Snaps this server's copy of the test player to their block centre, so the room, origin and
     * fixtures land on the grid a client-only run's snap puts them on (#1161). The teleport is the
     * one the server run's floor hold uses, so the joined client's camera and view follow it.
     */
    @Override
    public void snapPlayerForTestOnServer() {
        GrugServerRun.snapToBlockCentre((PlayerEntity) testPlayer());
    }

    /**
     * Puts this server's copy of the test player back where the snap found them, through the
     * teleport that carries the joined client's camera back too. The inherited answer restores a
     * client's own player and has none here, so a dedicated server has to restore its own copy.
     */
    @Override
    public void restorePlayerAfterTest() {
        GrugServerRun.restoreTestPlayer((PlayerEntity) testPlayer());
    }

    /** Asked of the client of the player who started the run, with the world frozen (#1040). */
    @Override
    public boolean hasSound(String path) {
        try {
            return GrugControlStation.server().hasSound(path);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "has_sound: " + e.getMessage());
            return false;
        }
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        try {
            GrugControlStation.server().playUiSound(path, volume, pitch);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "play_ui_sound: " + e.getMessage());
        }
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
            return GrugControlStation.server()
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
     * position falls in (#1240). The client's light is the copy its frame pass draws, and this
     * server cannot read it, so the answer is the client's own fingerprint compared with this
     * server's. A server with no starter has no client frame to protect and answers true, as {@link
     * #isWorldSettled} does.
     *
     * <p>No light-engine gate is needed here: this version computes its light on the server tick,
     * so the tick that placed the test's blocks has already worked it out, and the client's own
     * frame settles its copy.
     */
    @Override
    public boolean lightApplied(double x, double y, double z) {
        Object level = getLevel();
        if (level == null || !GrugControlStation.server().hasStarter()) {
            return true;
        }
        World world = (World) level;
        long simulated =
                GrugLightSync.lightFingerprint(
                        ((int) Math.floor(x)) >> 4,
                        ((int) Math.floor(y)) >> 4,
                        ((int) Math.floor(z)) >> 4,
                        (lightX, lightY, lightZ) ->
                                world.getBrightness(LightType.BLOCK, lightX, lightY, lightZ));
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

    // The screen functions a client adapter implements. The ones a test makes on a screen are
    // forwarded to the client of the player who started the run (#1094); GUI.open builds the block
    // entity's screen handler on this server too, so the client's screen is the one this server's
    // slots belong to (#1098). The pointer calls are the handler's, so this server answers them
    // itself: a click is applied to this server's own handler, and a carried stack only the client
    // knows would not be the one the click uses.

    /**
     * Opens the block entity's GUI as this server's own screen handler and has the joined client
     * show it (#1098). Whatever handler the player had open is closed first, one at a time as the
     * game does, so the client's next screen starts from window 0.
     */
    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        if (playerObj instanceof PlayerEntity) {
            GrugServerRun.closeScreenHandler((PlayerEntity) playerObj);
        }
        super.openGui(playerObj, blockEntityObj, guiBuilderObj);
    }

    /**
     * Opens the player's own inventory on the client of the player who started the run, whose own
     * inventory it is (#1094).
     *
     * <p>The screen handler this server has open is closed first, so both sides are on the player's
     * own inventory, window 0, by the time the client's screen is up: a press there carries window
     * 0, which this server only accepts as its own player's inventory handler.
     */
    @Override
    public void openInventory(Object playerObj) {
        if (playerObj instanceof PlayerEntity) {
            GrugServerRun.closeScreenHandler((PlayerEntity) playerObj);
        }
        screens.openInventory();
    }

    /**
     * Closes the screen handler this server has open, which is this server's half of closing the
     * joined client's screen: {@code closeHandledScreen} sends the packet that client closes it
     * with. The client is then asked to close its own screen too (#1094), because the screen call
     * is the client's and a client that closed its screen itself (Escape, say) has already sent
     * this server the packet that empties the handler. One client serves the whole run, so a screen
     * a test opened outlives it, and a capture test that follows a GUI test would otherwise take a
     * frame of the panel rather than of the world (#418).
     */
    @Override
    public void closeOpenScreenForTest() {
        Object playerObj = testPlayer();
        if (playerObj instanceof PlayerEntity) {
            GrugServerRun.closeScreenHandler((PlayerEntity) playerObj);
        }
        screens.closeScreen();
    }

    /**
     * Pressed on the client's screen, by the client of the player who started the run (#1094).
     *
     * <p>The screen is the block entity's handler, which this server built in {@code GUI.open}
     * under the window id it sent to that client, so the click packet the press sends names this
     * server's handler and lands in this server's slots. It is applied by {@link
     * GrugControlServer#applyClientWork} on a later tick, never inside this call, because the
     * server thread is frozen here and cannot read it.
     */
    @Override
    public boolean clickScreen(double x, double y, double button) {
        return screens.clickScreen(x, y, (int) button);
    }

    /**
     * Sets the carried stack this server's next click uses, and tells the client, so a frame it
     * draws and a click it predicts both agree with it (#1094).
     */
    @Override
    public void setCarriedItem(Object itemObj) {
        Object playerObj = testPlayer();
        if (!(playerObj instanceof PlayerEntity)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.set_carried_item: There is no player to set a cursor on.");
            return;
        }
        PlayerEntity player = (PlayerEntity) playerObj;
        player.inventory.setCursorStack(itemObj == null ? null : new ItemStack((Item) itemObj));
        GrugServerRun.sendCursor(player);
    }

    /** Clears the carried stack, and the client's copy of it (#1094). */
    @Override
    public void deleteCarriedItem() {
        Object playerObj = testPlayer();
        if (!(playerObj instanceof PlayerEntity)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "OverlayClickContext.delete_carried_item: There is no player to clear a cursor"
                            + " on.");
            return;
        }
        PlayerEntity player = (PlayerEntity) playerObj;
        player.inventory.setCursorStack(null);
        GrugServerRun.sendCursor(player);
    }

    /** Whether the joined client of the player who started the run has a screen open (#1094). */
    @Override
    public boolean isScreenOpenForTest() {
        return screens.screenOpen();
    }

    /**
     * The joined client's screen is the only one there is, so the open-or-closed answer names it.
     */
    @Override
    public long screenIdentityForTest() {
        return isScreenOpenForTest() ? 1 : 0;
    }

    /** The client's screen width, measured by the client of the player who started the run. */
    @Override
    public double screenWidthForTest() {
        return screens.read(GrugScreenCalls.SCREEN_WIDTH);
    }

    /** The client's GUI x, measured by the client of the player who started the run. */
    @Override
    public double guiLeftForTest() {
        return screens.read(GrugScreenCalls.GUI_LEFT);
    }

    /** The client's GUI y, measured by the client of the player who started the run. */
    @Override
    public double guiTopForTest() {
        return screens.read(GrugScreenCalls.GUI_TOP);
    }

    /** The client's GUI width, measured by the client of the player who started the run. */
    @Override
    public double guiWidthForTest() {
        return screens.read(GrugScreenCalls.GUI_WIDTH);
    }

    /** The client's GUI height, measured by the client of the player who started the run. */
    @Override
    public double guiHeightForTest() {
        return screens.read(GrugScreenCalls.GUI_HEIGHT);
    }

    /** Pressed on the client's screen, by the client of the player who started the run (#1094). */
    @Override
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
        return screens.pressScreenKey(key, ctrl, release);
    }

    /** Turned on the client's screen, by the client of the player who started the run (#1094). */
    @Override
    public boolean scrollScreen(double x, double y, double delta) {
        return screens.scrollScreen(x, y, delta);
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
        PlayerEntity player = (PlayerEntity) playerObj;
        double feet = player.boundingBox.minY;
        return super.isWorldReady(playerObj)
                && GrugPlayerFloor.mayStart(
                        solidBlocks(getLevel()), player.x, feet, player.z, player.onGround);
    }

    /**
     * The collision shapes of a level's blocks, as {@link GrugPlayerFloor} reads them: the top of
     * the highest box that covers the player's own point, which is what the game's own collision
     * meets there. A slab's floor is half a block below its block top, a stair's lower step is not
     * its upper one, and a block with no collision box at all, such as a torch or a plant, is not a
     * floor (#1102, #1136).
     *
     * <p>This generation's {@code Block} holds one set of bounds per block and asks it through
     * {@code addIntersectingBoundingBox}, which is the query {@code World.getEntityCollisions}
     * itself makes, so the answer is the game's. A box is filtered by the thin column through the
     * player's own point rather than by its block position, so a stair answers with the step the
     * player is on.
     */
    @SuppressWarnings("rawtypes")
    public static GrugPlayerFloor.BlockSource solidBlocks(Object levelObj) {
        World world = (World) levelObj;
        return (x, y, z, pointX, pointZ) -> {
            int id = world.getBlockId(x, y, z);
            if (id <= 0 || id >= Block.BLOCKS.length) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
            Block block = Block.BLOCKS[id];
            if (block == null) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
            Box point =
                    Box.create(
                            pointX - POINT_EDGE,
                            y,
                            pointZ - POINT_EDGE,
                            pointX + POINT_EDGE,
                            y + 1.0,
                            pointZ + POINT_EDGE);
            ArrayList boxes = new ArrayList();
            block.addIntersectingBoundingBox(world, x, y, z, point, boxes);
            double top = GrugPlayerFloor.BlockSource.NO_COLLISION;
            for (Object boxObj : boxes) {
                top = Math.max(top, ((Box) boxObj).maxY - y);
            }
            return top;
        };
    }

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlStation.server().hasStarter()) {
            return true;
        }
        try {
            return GrugControlStation.server().worldSettled();
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.world_settled: " + e.getMessage());
            return true;
        }
    }

    /** The server has no frames, so there is nothing to forget. */
    @Override
    public void forgetSettledFrames() {}

    /**
     * Drops a give the joined client sent for the test that just ended, so the next test starts
     * with the inventory its reset cleared rather than an item that give added (#1144). The queue
     * lives in core's server, and only this loader holds that server.
     */
    @Override
    public void forgetQueuedGivesForTest() {
        GrugControlStation.server().forgetQueuedGives();
    }

    /** The tests listed in {@code server-unsupported-tests.txt}, which says why. */
    @Override
    public Set<String> serverUnsupportedTests() {
        try (InputStream in =
                ServerStationApiAdapter.class.getResourceAsStream(
                        "/server-unsupported-tests.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "server-unsupported-tests.txt is not in the layer.");
            }
            return GrugTestRunner.parseTestList(new String(readAll(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read server-unsupported-tests.txt.", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** The client's cap of ticks for one frame, which the server takes as extra ticks per tick. */
    @Override
    public double maxGameSpeed() {
        return ModLoaderAdapter.CLIENT_TICKS_PER_FRAME;
    }

    @Override
    public boolean setGameSpeed(double speed) {
        GrugServerRun.setGameSpeed(GrugGameSpeed.wholeSteps(speed));
        return true;
    }
}
