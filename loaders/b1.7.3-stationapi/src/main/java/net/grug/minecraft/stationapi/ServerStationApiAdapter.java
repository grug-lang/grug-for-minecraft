package net.grug.minecraft.stationapi;

import net.grug.minecraft.core.GrugArms;
import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugClientSavedNumbers;
import net.grug.minecraft.core.GrugClientTestCalls;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugLightResend;
import net.grug.minecraft.core.GrugLightSync;
import net.grug.minecraft.core.GrugOverlayCalls;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugSavedDataSync;
import net.grug.minecraft.core.GrugScreenCalls;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestOrigin;
import net.grug.minecraft.core.GrugTestPlayerMove;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.core.ModLoaderAdapter;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.stationapi.block.entity.GrugSavedDataStation;
import net.grug.minecraft.stationapi.control.GrugControlStation;
import net.grug.minecraft.stationapi.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.entity.EntityTracker;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * <p>Measured by the coverage gate like any class. The arms no test can force are annotated one
 * method at a time with their reason (#1606).
 */
public class ServerStationApiAdapter extends StationApiAdapter {

    /** A forwarded call the joined client could not answer is a host function error. */
    @GrugGenerated("a forwarded call fails only when the joined client cannot answer it")
    private static void forwardedCallFailed(String message) {
        Grug.hostFunctionErrorHappened(Grug.statePtr, message);
    }

    /**
     * The screen calls this server makes, which its joined client performs: a server has no screen
     * of its own, so every one of them is a round trip to the client of the player who started the
     * run (#1094). A failure is a host function error, which fails the test.
     */
    private final GrugScreenCalls.Forwarded screens =
            new GrugScreenCalls.Forwarded(
                    GrugControlStation::server, ServerStationApiAdapter::forwardedCallFailed);

    /**
     * The camera reads this server makes, which its joined client answers with its own camera: a
     * dedicated server has none, so a test that asserts where the client draws from asks there
     * (#1161). A failure is a host function error, which fails the test.
     */
    private final GrugTestCamera.Forwarded camera =
            new GrugTestCamera.Forwarded(
                    GrugControlStation::server, ServerStationApiAdapter::forwardedCallFailed);

    /**
     * The saved-number reads this server makes, which its joined client answers from its own copy
     * of the block entity: a dedicated server holds the simulated copy, not the one the client's
     * frame pass reads (#1162).
     */
    private final GrugClientSavedNumbers.Forwarded savedNumbers =
            new GrugClientSavedNumbers.Forwarded(
                    GrugControlStation::server, ServerStationApiAdapter::forwardedCallFailed);

    /**
     * The light comparisons this server makes, which its joined client answers from its own copy of
     * the section: a dedicated server holds the simulated copy, not the one the client's frame pass
     * bakes the room from (#1240).
     */
    private final GrugClientLight.Forwarded clientLight =
            new GrugClientLight.Forwarded(
                    GrugControlStation::server, ServerStationApiAdapter::forwardedCallFailed);

    /**
     * The overlay reads this server makes, which its joined client answers about the frame it
     * draws: an overlay belongs to a client's frame pass, so a server never records one (#1099). A
     * failure is a host function error, which fails the test.
     */
    private final GrugOverlayCalls.Forwarded overlays =
            new GrugOverlayCalls.Forwarded(
                    GrugControlStation::server, ServerStationApiAdapter::forwardedCallFailed);

    /**
     * The mining crack and the client resource files of a hot reload, which the joined client
     * answers from its own world and its own mods directory (#1040): the crack is its frame pass's,
     * and a texture is read by the client alone.
     */
    private final GrugClientTestCalls.Forwarded clientTestCalls =
            new GrugClientTestCalls.Forwarded(
                    GrugControlStation::server, ServerStationApiAdapter::forwardedCallFailed);

    /**
     * The dedicated server has no client to clear a toast on, and the base adapter would ask for a
     * Minecraft instance this process does not have, so the test runner's call is a no-op here: the
     * joined client marks its own achievements before it captures anything (#1576).
     */
    @Override
    public void markAchievementsEarnedForTest() {}

    @Override
    public GrugClientTestCalls.Forwarded forwardedClientTestCalls() {
        return clientTestCalls;
    }

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
     * position {@code TestTickContext.get_origin} reports. The inherited origin goes through {@link
     * #testPlayerEye}, so choosing the player here is all a server has to do.
     */
    @Override
    public Object testPlayer() {
        // Deliberately an untyped list: Fabric Loader will not define a class the game marked
        // server-only in a client, so no reference the verifier has to resolve may name one here.
        // The player who started the run, when one has: forwarded calls use their screen (#1040).
        PlayerEntity starter = GrugControlStation.starterPlayer();
        return GrugArms.askIf(
                Objects.isNull(starter), starter, ServerStationApiAdapter::firstJoinedPlayer);
    }

    @GrugGenerated(
            "the run's player is always the starter, so no fallback to the first one is made")
    private static Object firstJoinedPlayer() {
        List<?> players = GrugServerRun.players();
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * Whether the server's operator list holds the player, which is the game's own check. A
     * dedicated server has no singleplayer owner, so the list is the whole answer (#1331).
     */
    @Override
    public boolean isOperator(Object playerObj) {
        return GrugServerRun.isOperator(((PlayerEntity) playerObj).name);
    }

    /** Adds the player to the server's operator list, which the game saves to {@code ops.txt}. */
    @Override
    public void makeOperator(Object playerObj) {
        GrugServerRun.addToOperators(((PlayerEntity) playerObj).name);
    }

    /**
     * Removes the player from the server's operator list, which the game saves to {@code ops.txt}.
     */
    @Override
    public void removeOperator(Object playerObj) {
        GrugServerRun.removeFromOperators(((PlayerEntity) playerObj).name);
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's interaction manager sends a use packet the
     * server thread is blocked inside this call and so cannot process. So the click is made here
     * instead, through the same {@code ServerPlayerInteractionManager.interactBlock} the game's own
     * server calls when a client's use packet arrives. Asking it rather than calling the block's
     * {@code onUse} and the held item's {@code useOnBlock} in that order here is what lets a
     * vanilla block answer: a chest opens through its own {@code onUse}, which sends this player
     * the game's container screen, and a chest with a solid block above it stays shut because the
     * game's rule says so (#1388).
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
        GrugArms.ifType(
                testPlayer(),
                PlayerEntity.class,
                player -> useBlockAs(player, (World) levelObj, x, y, z),
                ServerStationApiAdapter::noPlayerToClickWith);
    }

    @GrugGenerated("a run's clicks happen while the player who started it is joined")
    private static void noPlayerToClickWith() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "TestTickContext.use_block: There is no player to right-click with.");
    }

    private void useBlockAs(PlayerEntity player, World world, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // World.getBlockId() hands back a block id rather than a Block in this generation, and 0 is
        // air. A click names a block, so a position with none is a mistake in the test rather than
        // a click on nothing.
        if (world.getBlockId(blockX, blockY, blockZ) <= 0) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "TestTickContext.use_block: There is no block at "
                            + blockX
                            + ", "
                            + blockY
                            + ", "
                            + blockZ
                            + ".");
            return;
        }

        ((ServerPlayerEntity) player)
                .interactionManager.interactBlock(
                        player,
                        world,
                        player.inventory.getSelectedItem(),
                        blockX,
                        blockY,
                        blockZ,
                        GrugUseBlockFace.face(testPlayerEye(player), blockX, blockY, blockZ));
    }

    /**
     * Builds the room around the height the run holds the player at, not wherever the player's box
     * is this tick: a box a hair under a whole number put the stand block a block too low, and the
     * hold kept the player on it, so every later capture was drawn one block lower than a
     * client-only run's (#1068, #1629). Logs the feet the room is anchored at.
     */
    @Override
    public void buildTestBox(int radius) {
        // The run holds the starter's height before its runner exists, and the runner is what
        // builds a room, so there is always a player here.
        PlayerEntity player = Objects.requireNonNull((PlayerEntity) testPlayer());
        double held = GrugServerRun.heldFeetY();
        double anchor = GrugPlayerFloor.buildAnchor(player.boundingBox.minY, held);
        System.out.println(
                "[GRUG CI] buildTestBox on the server: feet "
                        + player.boundingBox.minY
                        + " held "
                        + held
                        + " anchor "
                        + anchor);
        super.buildTestBoxAt(radius, anchor);
    }

    /**
     * The eye of the test player. A server player keeps the feet in {@code y} and a client keeps
     * the eye, so the eye is rebuilt from the bottom of the bounding box, rounded to a millionth to
     * shed the arithmetic's last bits, which is what the client's {@code y} is. The height added is
     * {@link GrugTestOrigin#EYE_HEIGHT}, the game's own float, the same one the client's eye and
     * the capture pin use (#1662).
     */
    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        return new Vec3(
                player.x,
                Math.rint(player.boundingBox.minY * 1e6) / 1e6 + GrugTestOrigin.EYE_HEIGHT,
                player.z);
    }

    /**
     * Moves this server's copy of the test player to the feet position when the server has that
     * chunk, through the teleport the run's floor hold uses, so the joined client's camera follows
     * (#1040). False when there is no player, no world or the chunk is not loaded, which the caller
     * reports without moving anyone: a teleport into a chunk the joined client has not received
     * crashes this generation of the game.
     */
    @Override
    public boolean setPlayerPositionForTest(double x, double y, double z) {
        PlayerEntity player = (PlayerEntity) testPlayer();
        World world = (World) getLevel();
        boolean hasWorld = Objects.nonNull(world);
        boolean loaded =
                GrugArms.askIf(
                        hasWorld,
                        false,
                        () -> world.isPosLoaded((int) Math.floor(x), 64, (int) Math.floor(z)));
        return GrugArms.askIf(
                GrugTestPlayerMove.canMove(Objects.nonNull(player), hasWorld, loaded),
                false,
                () -> movePlayer(player, x, y, z));
    }

    private static boolean movePlayer(PlayerEntity player, double x, double y, double z) {
        GrugServerRun.moveToFeet(player, x, y, z);
        GrugTestState.playerMovedTo(x, y, z);
        return true;
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
        return GrugArms.tryAsk(
                () -> GrugControlStation.server().hasSound(path),
                ServerStationApiAdapter::hasSoundFailed);
    }

    @GrugGenerated("a forwarded sound probe fails only when the joined client cannot answer it")
    private static Boolean hasSoundFailed(GrugControlServer.ControlException e) {
        Grug.hostFunctionErrorHappened(Grug.statePtr, "has_sound: " + e.getMessage());
        return Boolean.FALSE;
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        GrugArms.tryAsk(
                () -> {
                    GrugControlStation.server().playUiSound(path, volume, pitch);
                    return Boolean.TRUE;
                },
                ServerStationApiAdapter::uiSoundFailed);
    }

    @GrugGenerated("a forwarded sound fails only when the joined client cannot answer it")
    private static Boolean uiSoundFailed(GrugControlServer.ControlException e) {
        Grug.hostFunctionErrorHappened(Grug.statePtr, "play_ui_sound: " + e.getMessage());
        return Boolean.FALSE;
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
     * The eye the capture is drawn from: where the test moved the player to, else the origin the
     * running test's room was built above, less {@link GrugTestOrigin#LIFT}, or null when this side
     * has no player to measure one from.
     */
    private Vec3 captureEye() {
        return GrugTestState.captureEye(this::getTestOrigin);
    }

    /** The joined client's own camera, which is where its captures are drawn from (#1161). */
    @Override
    public Vec3 testCamera() {
        return camera.camera();
    }

    /** The crack is drawn by the joined client's frame pass, so the client answers. */
    @Override
    public boolean miningCrackForTest(Object levelObj, double x, double y, double z) {
        return clientTestCalls.miningCrack(x, y, z);
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
        return GrugArms.askIf(
                GrugArms.both(Objects.nonNull(level), GrugControlStation.server().hasStarter()),
                true,
                () -> askClientLight((World) level, x, y, z));
    }

    private boolean askClientLight(World world, double x, double y, double z) {
        long simulated =
                GrugLightSync.lightFingerprint(
                        ((int) Math.floor(x)) >> 4,
                        ((int) Math.floor(y)) >> 4,
                        ((int) Math.floor(z)) >> 4,
                        (lightX, lightY, lightZ) ->
                                world.getBrightness(LightType.BLOCK, lightX, lightY, lightZ),
                        (lightX, lightY, lightZ) ->
                                world.getBrightness(LightType.SKY, lightX, lightY, lightZ));
        // Diagnostic only (#1765): the simulated side's own levels at the cell the wait asked
        // about, and the six engine cells and the air faces the camera sees.
        int cellX = (int) Math.floor(x);
        int cellY = (int) Math.floor(y);
        int cellZ = (int) Math.floor(z);
        StringBuilder serverLightText =
                new StringBuilder("[GRUG RELIGHT] server light at ")
                        .append(cellX)
                        .append(',')
                        .append(cellY)
                        .append(',')
                        .append(cellZ)
                        .append(" sky=")
                        .append(world.getBrightness(LightType.SKY, cellX, cellY, cellZ))
                        .append(" block=")
                        .append(world.getBrightness(LightType.BLOCK, cellX, cellY, cellZ))
                        .append(" fingerprint=")
                        .append(simulated);
        lightRow(serverLightText, world, "engine sky", LightType.SKY, -56);
        lightRow(serverLightText, world, "engine block", LightType.BLOCK, -56);
        lightRow(serverLightText, world, "face sky", LightType.SKY, -57);
        lightRow(serverLightText, world, "face block", LightType.BLOCK, -57);
        System.out.println(serverLightText);
        return clientLight.lightApplied(x, y, z, simulated);
    }

    /** Diagnostic only (#1765): appends a row of six levels at {@code y=74, z}. */
    private static void lightRow(
            StringBuilder text, World world, String label, LightType type, int z) {
        text.append(' ').append(label);
        for (int i = 0; i < 6; i++) {
            text.append(' ').append(world.getBrightness(type, -22 + i, 74, z));
        }
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

    /**
     * Whether the joined client's drawn mark was already clear when the first frame of its open
     * screen began (#1391): the frames are that client's, and it records the mark at the moment the
     * screen's first frame begins, which a server cannot reconstruct from its own empty client.
     */
    @Override
    public boolean overlayFirstFrameClearedForTest(String mod) {
        return overlays.firstFrameCleared(mod);
    }

    /**
     * Whether the joined client asks the overlays' world key, HUD and world exports, which this
     * server's own answer would deny: the loader marks support where it registers the hooks, and a
     * server has none (#1040).
     */
    @Override
    public boolean worldOverlaySupportedForTest() {
        return overlays.worldSupported();
    }

    /**
     * Whether a frame of the joined client drew the world overlay of the running test's mod since
     * its last key press (#1040): the mark belongs to that client's frame pass.
     */
    @Override
    public boolean worldOverlayDrawnForTest() {
        return overlays.worldDrawn(GrugTestState.currentTestMod);
    }

    /** The press is delivered by the joined client's own key path (#1040). */
    @Override
    public void pressWorldKey(int key, boolean ctrl) {
        overlays.pressWorldKey(GrugTestState.currentTestMod, key, ctrl);
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
        closeHandlerOf(playerObj);
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
        closeHandlerOf(playerObj);
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
        closeHandlerOf(testPlayer());
        screens.closeScreen();
    }

    /** Closes the screen handler of {@code playerObj} when it is a player the server holds. */
    private static void closeHandlerOf(Object playerObj) {
        GrugArms.ifType(
                playerObj,
                PlayerEntity.class,
                GrugServerRun::closeScreenHandler,
                ServerStationApiAdapter::noPlayerToClose);
    }

    @GrugGenerated("a run starts only once its player has joined, so a player is always there")
    private static void noPlayerToClose() {}

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
        GrugArms.ifType(
                testPlayer(),
                PlayerEntity.class,
                player -> {
                    player.inventory.setCursorStack(
                            GrugArms.askIf(
                                    Objects.nonNull(itemObj),
                                    null,
                                    () -> new ItemStack((Item) itemObj)));
                    GrugServerRun.sendCursor(player);
                },
                ServerStationApiAdapter::noPlayerToSetCursor);
    }

    @GrugGenerated("a run's cursor work happens while the player is joined")
    private static void noPlayerToSetCursor() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "TestTickContext.set_carried_item: There is no player to set a cursor on.");
    }

    /** Clears the carried stack, and the client's copy of it (#1094). */
    @Override
    public void deleteCarriedItem() {
        GrugArms.ifType(
                testPlayer(),
                PlayerEntity.class,
                player -> {
                    player.inventory.setCursorStack(null);
                    GrugServerRun.sendCursor(player);
                },
                ServerStationApiAdapter::noPlayerToClearCursor);
    }

    @GrugGenerated("a run's cursor work happens while the player is joined")
    private static void noPlayerToClearCursor() {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "OverlayClickContext.delete_carried_item: There is no player to clear a cursor"
                        + " on.");
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
        return GrugArms.askIf(
                super.isWorldReady(playerObj),
                false,
                () ->
                        GrugPlayerFloor.mayStart(
                                solidBlocks(getLevel()),
                                player.x,
                                feet,
                                player.z,
                                player.onGround));
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
            return GrugArms.askIf(
                    GrugArms.inRange(id, 1, Block.BLOCKS.length),
                    GrugPlayerFloor.BlockSource.NO_COLLISION,
                    () -> blockTop(world, Block.BLOCKS[id], x, y, z, pointX, pointZ));
        };
    }

    @SuppressWarnings("rawtypes")
    private static double blockTop(
            World world, Block block, int x, int y, int z, double pointX, double pointZ) {
        return GrugArms.askIf(
                Objects.nonNull(block),
                GrugPlayerFloor.BlockSource.NO_COLLISION,
                () -> boxesTop(world, block, x, y, z, pointX, pointZ));
    }

    @SuppressWarnings("rawtypes")
    private static double boxesTop(
            World world, Block block, int x, int y, int z, double pointX, double pointZ) {
        {
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
        }
    }

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     *
     * <p>A region packet still owed to the joined client is not settled, and neither is the poll
     * that only arms its send: the region is what marks the client's sections dirty, so a capture
     * taken before it reads a mesh baked from the light before it. {@link GrugLightResend#due}
     * sends nothing for the first poll after a change, which used to fall through to the client's
     * answer and let the capture go (#1392).
     */
    @Override
    public boolean isWorldSettled() {
        return GrugArms.askIf(
                GrugControlStation.server().hasStarter(), true, this::settledForStarter);
    }

    private boolean settledForStarter() {
        if (lightResend.pending()) {
            resendLight();
            return false;
        }
        return GrugArms.tryAsk(
                () -> GrugControlStation.server().worldSettled(),
                ServerStationApiAdapter::worldSettledFailed);
    }

    @GrugGenerated("a forwarded settled check fails only when the joined client cannot answer it")
    private static Boolean worldSettledFailed(GrugControlServer.ControlException e) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr, "TestTickContext.world_settled: " + e.getMessage());
        return Boolean.TRUE;
    }

    /**
     * The blocks a test placed, whose light the joined client lights for itself and so can light
     * differently from this server's engine (#1092). See {@link GrugLightResend}.
     */
    private final GrugLightResend lightResend = new GrugLightResend();

    /** A block placed here also owes the joined client the light around it. */
    @Override
    public void placeBlock(Object levelObj, double x, double y, double z, String blockName) {
        super.placeBlock(levelObj, x, y, z, blockName);
        lightResend.noteBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /**
     * Sends the joined client this server's light for the blocks a test changed, once the tick that
     * changed them has been flushed to it, and answers whether it did.
     *
     * <p>This version's client lights a block-change packet from that change alone, while a chunk
     * region packet carries the server's own light, so a room that touches a chunk with few blocks
     * left that chunk's light a level off the server's, which the client-only run's golden does not
     * show. The region packet is the one carrier of light this version has, so the settle wait
     * sends it: the answer is false for the poll that sent it, so the next one asks a client that
     * has the light. See {@link GrugLightResend} and #1092.
     */
    private boolean resendLight() {
        List<int[]> regions = lightResend.due();
        if (regions.isEmpty()) {
            return false;
        }
        PlayerEntity starter = GrugControlStation.starterPlayer();
        World world = (World) getLevel();
        return GrugArms.askIf(
                GrugTestPlayerMove.canMove(Objects.nonNull(starter), Objects.nonNull(world), true),
                false,
                () -> sendLightRegions(starter, world, regions));
    }

    private boolean sendLightRegions(PlayerEntity starter, World world, List<int[]> regions) {
        // The placements left the field their order made, which differs from the one the final
        // blocks define and from the client-only run's, so it is recomputed before it is sent.
        int[] box = lightResend.lastBox();
        relightRoom(
                world, box[0], Math.max(box[1], 0), box[2], box[3], Math.min(box[4], 127), box[5]);
        for (int[] region : regions) {
            // A chunk this server never loaded is an empty one with no block array, which a region
            // packet cannot be made of, and the client holds no light for it either.
            boolean loaded = world.getChunk(region[0] >> 4, region[2] >> 4).blocks != null;
            // Diagnostic only (#1765): what the resend actually puts on the wire.
            System.out.println(
                    "[GRUG RELIGHT] region "
                            + region[0]
                            + ","
                            + region[1]
                            + ","
                            + region[2]
                            + " size "
                            + region[3]
                            + ","
                            + region[4]
                            + ","
                            + region[5]
                            + " loaded="
                            + loaded);
            if (!loaded) {
                continue;
            }
            ((ServerPlayerEntity) starter)
                    .networkHandler.sendPacket(
                            new ChunkDataS2CPacket(
                                    region[0], region[1], region[2], region[3], region[4],
                                    region[5], world));
        }
        return true;
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

    /**
     * Removes the entities as the client-only game does, then tells the joined client they are
     * gone.
     */
    @Override
    public void removeEntities(Object levelObj, String kind) {
        List<net.minecraft.entity.Entity> mobs = entitiesOf((World) levelObj, kind);
        super.removeEntities(levelObj, kind);
        EntityTracker tracker =
                ((net.minecraft.server.MinecraftServer)
                                net.fabricmc.loader.api.FabricLoader.getInstance()
                                        .getGameInstance())
                        .getEntityTracker(0);
        for (net.minecraft.entity.Entity mob : mobs) {
            tracker.onEntityRemoved(mob);
        }
    }

    /**
     * Sweeps the items as the client-only game does, then tells the joined client they are gone.
     * The sweep takes a dead item out of the world's lists itself, so the world's own update never
     * reaches the entity tracker for it and no destroy packet is sent: the joined client kept a
     * ghost of every swept item, such as a diamond a pipe test dropped, and drew it into later
     * captures (#1096, #1068).
     */
    @Override
    public void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2) {
        World world = (World) levelObj;
        List<ItemEntity> items =
                new ArrayList<>(
                        world.collectEntitiesByClass(
                                ItemEntity.class, Box.create(x1, y1, z1, x2, y2, z2)));
        List<net.minecraft.entity.Entity> mobs = new ArrayList<>(spawnedMobs);
        super.clearItemEntities(levelObj, x1, y1, z1, x2, y2, z2);
        EntityTracker tracker =
                ((net.minecraft.server.MinecraftServer)
                                net.fabricmc.loader.api.FabricLoader.getInstance()
                                        .getGameInstance())
                        .getEntityTracker(0);
        for (ItemEntity item : items) {
            tracker.onEntityRemoved(item);
        }
        for (net.minecraft.entity.Entity mob : mobs) {
            tracker.onEntityRemoved(mob);
        }
    }
}
