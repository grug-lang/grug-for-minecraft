package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugClientSavedNumbers;
import net.grug.minecraft.core.GrugClientTestCalls;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugGameSpeed;
import net.grug.minecraft.core.GrugLightAsk;
import net.grug.minecraft.core.GrugLightResend;
import net.grug.minecraft.core.GrugLightRetry;
import net.grug.minecraft.core.GrugLightSync;
import net.grug.minecraft.core.GrugLinkArms;
import net.grug.minecraft.core.GrugOverlayCalls;
import net.grug.minecraft.core.GrugPlayerFloor;
import net.grug.minecraft.core.GrugRelight;
import net.grug.minecraft.core.GrugRenderSettle;
import net.grug.minecraft.core.GrugScreenCalls;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugTestCamera;
import net.grug.minecraft.core.GrugTestOrigin;
import net.grug.minecraft.core.GrugTestPlayerMove;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.block.entity.GrugSavedDataPacket;
import net.grug.minecraft.ornithe.control.GrugControlBeta;
import net.grug.minecraft.ornithe.gui.GrugMenu;
import net.grug.minecraft.ornithe.gui.GrugScreenPacket;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.WorldChunkPacket;
import net.minecraft.server.EntityMap;
import net.minecraft.server.entity.mob.player.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The adapter for a b1.7.3 dedicated server, which is the same loader as {@link OrnitheAdapter} in
 * a second process rather than a second mode.
 *
 * <p>It extends the client adapter rather than implementing {@link
 * net.grug.minecraft.core.ModLoaderAdapter} again, because everything that is not about which
 * process this is, is the same code: the block registry has no client in it, the recipe manager has
 * no client in it, and neither has any of the inventory or entity host functions. What a server
 * cannot inherit is the pair of handles, the player position convention and the player the origin
 * follows. The position convention is the one override that matters here: this server's player
 * keeps the feet in {@code y}, so {@link #testPlayerEye} adds the eye height above the room's floor
 * anchor (#1178).
 *
 * <p>What that leaves is the calls that are neither client-only nor server-only. {@code
 * useBlockForTest} is one: a click is not a client-only function, so the server right-clicks in its
 * own world, the world the test reads (#1090). {@code GUI.open} is another, since the menu it
 * builds belongs to wherever it runs, so this server builds its own menu and sends it to the joined
 * client (#1098). A call a test makes on a screen is the client's even when the server's menu is
 * its subject, so {@code openInventory} and {@code clickScreen} are forwarded to that client, and
 * the cursor calls set this server's own cursor and sync it to the screen that draws it. The other
 * client-only functions are inherited and each refuses itself through {@code
 * ModLoaderAdapter.refuseWithoutAClient}, which is what turns a screenshot test on a server into an
 * err naming the function, rather than an exception from inside {@code net.minecraft.client}.
 *
 * <p>The coverage gate counts this class from the dedicated server's own run (#1647): a guard arm
 * no run reaches lives in a tested core helper, and what no test can force is annotated per method.
 */
public class ServerOrnitheAdapter extends OrnitheAdapter {

    /**
     * How thin the column is that asks which collision boxes cover the player's own point. It only
     * has to be non-zero so a box whose edge is exactly on the point still intersects it.
     */
    private static final double POINT_EDGE = 1e-9;

    /**
     * The dedicated server has no client to clear a toast on, and the base adapter would ask for a
     * Minecraft instance this process does not have, so the test runner's call is a no-op here: the
     * joined client marks its own achievements before it captures anything (#1576).
     */
    @Override
    public void markAchievementsEarnedForTest() {}

    @Override
    public GrugSide getSide() {
        return GrugSide.DEDICATED_SERVER;
    }

    /**
     * Opens the block entity's GUI as this server's own menu and has the joined client show it
     * (#1098). Beta's {@code GUI.open} used to build the screen on the client alone, so a click
     * from it carried slot ids for a menu this server did not have; now both sides build the same
     * slots from the layout this sends, and the click lands in the server's inventory.
     *
     * <p>Whatever menu the player had open is closed first, one at a time as the game does, so the
     * client's next screen starts from window 0. The layout packet goes out before the menu's own
     * content packets, because a client applies a content packet only to a screen that is already
     * up under that window id.
     *
     * <p>The window id is never 0: the client treats window 0 as the player's own inventory, so a
     * menu opened under it would never receive its slot contents.
     */
    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        Inventory inventory = (Inventory) blockEntityObj;
        BlockEntity blockEntity = (BlockEntity) blockEntityObj;
        GrugGuiBuilder layout = (GrugGuiBuilder) guiBuilderObj;

        GrugServerRun.closeOpenMenu(player);

        GrugMenu menu = new GrugMenu(player, inventory, layout);
        int windowId = nextWindowId();
        menu.networkId = windowId;
        player.menu = menu;
        GrugServerRun.sendToPlayer(
                player,
                new GrugScreenPacket(
                        windowId, blockEntity.x, blockEntity.y, blockEntity.z, layout));
        GrugServerRun.listenToMenu(player, menu);
    }

    /**
     * The window id the next {@code GUI.open} installs its menu under, one after another as
     * vanilla's own counter does. It must not be 0, which is the player's own inventory.
     */
    private static int nextWindowId() {
        windowId = windowId % 99 + 1;
        return windowId;
    }

    private static int windowId = 0;

    // The screen functions a client adapter implements. The ones a test makes on a screen are
    // forwarded to the client of the player who started the run (#1094); GUI.open builds the block
    // entity's menu here, so the client's screen is the one this server's slots belong to (#1098).
    // The pointer calls are the menu's, so this server answers them itself: a click is applied to
    // this server's own menu, and a carried stack only the client knows would not be the one the
    // click uses.

    /**
     * Opens the player's own inventory on the client of the player who started the run, whose own
     * inventory it is (#1094).
     *
     * <p>The menu this server has open is closed first, so both sides are on the player's own
     * inventory, window 0, by the time the client's screen is up: a press there carries window 0,
     * which this server only accepts as its own player's inventory menu.
     */
    @Override
    public void openInventory(Object playerObj) {
        GrugLinkArms.applyTo(playerObj, PlayerEntity.class, GrugServerRun::closeOpenMenu);
        screens.openInventory();
    }

    /**
     * Closes the menu this server has open, which this server's half of closing the joined client's
     * screen: {@code closeMenu} sends the packet that client closes it with. The client is then
     * asked to close its own screen too (#1094), because the screen call is the client's and a
     * client that closed its screen itself (Escape, say) has already sent this server the packet
     * that empties the menu. One client serves the whole run, so a screen a test opened outlives
     * it, and a capture test that follows a GUI test would otherwise take a frame of the panel
     * rather than of the world (#418).
     */
    @Override
    public void closeOpenScreenForTest() {
        GrugLinkArms.applyTo(testPlayer(), PlayerEntity.class, GrugServerRun::closeOpenMenu);
        screens.closeScreen();
    }

    /**
     * Pressed on the client's screen, by the client of the player who started the run (#1094).
     *
     * <p>The screen is the block entity's menu, which this server built in {@code GUI.open} under
     * the window id it sent to that client, so the click packet the press sends names this server's
     * menu and lands in this server's slots. It is applied by {@link
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
        GrugLinkArms.deliverOrReport(
                testPlayer(),
                "TestTickContext.set_carried_item: There is no player to set a cursor on.",
                ServerOrnitheAdapter::reportFailure,
                playerObj -> setCursor((PlayerEntity) playerObj, (Item) itemObj));
    }

    private static void setCursor(PlayerEntity player, Item item) {
        player.inventory.setCursorItem(new ItemStack(item));
        GrugServerRun.sendCursor(player);
    }

    /** Clears the carried stack, and the client's copy of it (#1094). */
    @Override
    public void deleteCarriedItem() {
        GrugLinkArms.deliverOrReport(
                testPlayer(),
                "OverlayClickContext.delete_carried_item: There is no player to clear a cursor"
                        + " on.",
                ServerOrnitheAdapter::reportFailure,
                playerObj -> clearCursor((PlayerEntity) playerObj));
    }

    private static void clearCursor(PlayerEntity player) {
        player.inventory.setCursorItem(null);
        GrugServerRun.sendCursor(player);
    }

    /**
     * The screen calls this server makes, which its joined client performs: a server has no screen
     * of its own, so every one of them is a round trip to the client of the player who started the
     * run (#1094). A failure is a host function error, which fails the test.
     */
    private final GrugScreenCalls.Forwarded screens =
            new GrugScreenCalls.Forwarded(
                    GrugControlBeta::server, ServerOrnitheAdapter::reportFailure);

    /**
     * The camera reads this server makes, which its joined client answers with its own camera: a
     * dedicated server has none, so a test that asserts where the client draws from asks there
     * (#1161). A failure is a host function error, which fails the test.
     */
    private final GrugTestCamera.Forwarded camera =
            new GrugTestCamera.Forwarded(
                    GrugControlBeta::server, ServerOrnitheAdapter::reportFailure);

    /**
     * The saved-number reads this server makes, which its joined client answers from its own copy
     * of the block entity: a dedicated server holds the simulated copy, not the one the client's
     * frame pass reads (#1162).
     */
    private final GrugClientSavedNumbers.Forwarded savedNumbers =
            new GrugClientSavedNumbers.Forwarded(
                    GrugControlBeta::server, ServerOrnitheAdapter::reportFailure);

    /**
     * The light comparisons this server makes, which its joined client answers from its own copy of
     * the section: a dedicated server holds the simulated copy, not the one the client's frame pass
     * bakes the room from (#1240).
     */
    private final GrugClientLight.Forwarded clientLight =
            new GrugClientLight.Forwarded(
                    GrugControlBeta::server, ServerOrnitheAdapter::reportFailure);

    /**
     * The overlay reads this server makes, which its joined client answers about the frame it
     * draws: an overlay belongs to a client's frame pass, so a server never records one (#1099). A
     * failure is a host function error, which fails the test.
     */
    private final GrugOverlayCalls.Forwarded overlays =
            new GrugOverlayCalls.Forwarded(
                    GrugControlBeta::server, ServerOrnitheAdapter::reportFailure);

    /**
     * The mining crack and the client resource files of a hot reload, which the joined client
     * answers from its own world and its own mods directory (#1040): the crack is its frame pass's,
     * and a texture is read by the client alone.
     */
    private final GrugClientTestCalls.Forwarded clientTestCalls =
            new GrugClientTestCalls.Forwarded(
                    GrugControlBeta::server, ServerOrnitheAdapter::reportFailure);

    @Override
    public GrugClientTestCalls.Forwarded forwardedClientTestCalls() {
        return clientTestCalls;
    }

    /**
     * Reports a failed call of the joined client as a host function error, which fails the test.
     */
    @GrugGenerated(
            "a call fails only when the joined client cannot answer it or refuses it, which a"
                    + " passing run never has")
    private static void reportFailure(String message) {
        Grug.hostFunctionErrorHappened(Grug.statePtr, message);
    }

    @Override
    public Object getLevel() {
        return MinecraftServerInstance.get().getWorld(0);
    }

    /**
     * The first player who joined.
     *
     * <p>A server has no local player, so a test that wants one to build fixtures around and to
     * right-click with has to be given one of the connected players. Which one does not matter: the
     * tests share one world, and they are told to build in bands above the origin rather than at a
     * player, so the choice only decides whose position {@code TestTickContext.get_origin} reports.
     * The inherited {@code testPlayerEye} and {@code getTestOrigin} both go through this answer, so
     * choosing the player here is all a server has to do. See #188.
     */
    @Override
    public Object testPlayer() {
        // Deliberately not List<ServerPlayerEntity>. Fabric Loader will not define a class the game
        // marked server-only in a client, so any reference the verifier has to resolve here would
        // break every client. A local variable's type argument is erased and so would survive, but
        // it promises a type the runtime cannot hand over: the next loop or cast written in this
        // method would break the client, and nothing at the call site would say why.
        // The player who started the run, when one has: forwarded calls use their screen (#1040).
        PlayerEntity starter = GrugControlBeta.starterPlayer();
        if (starter != null) {
            return starter;
        }
        List<?> players = MinecraftServerInstance.get().playerManager.players;
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * Whether the server's operator list holds the player, which is the game's own check: {@code
     * PlayerManager.isOp} lower-cases the name and looks it up in the set {@code ops.txt} fills
     * (#1331). A dedicated server has no singleplayer owner, so the list is the whole answer.
     */
    @Override
    public boolean isOperator(Object playerObj) {
        return MinecraftServerInstance.get().playerManager.isOp(((PlayerEntity) playerObj).name);
    }

    /** Adds the player to the server's operator list, which the game saves to {@code ops.txt}. */
    @Override
    public void makeOperator(Object playerObj) {
        MinecraftServerInstance.get().playerManager.addOp(((PlayerEntity) playerObj).name);
    }

    /**
     * Removes the player from the server's operator list, which the game saves to {@code ops.txt}.
     */
    @Override
    public void removeOperator(Object playerObj) {
        MinecraftServerInstance.get().playerManager.removeOp(((PlayerEntity) playerObj).name);
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's interaction manager sends a use packet the
     * server thread is blocked inside this call and so cannot process. So the click is made here
     * instead, through the same {@code ServerPlayerInteractionManager.useBlock} the game's own
     * server calls when a client's use packet arrives. Asking it rather than calling the block's
     * {@code use} and the held item's {@code useOn} in that order here is what lets a vanilla block
     * answer: a chest opens through its own {@code use}, which sends this player the game's
     * container screen, and a chest with a solid block above it stays shut because the game's rule
     * says so (#1388).
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The client path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106). This generation's {@code Block.use} takes no face, so it is the held item's {@code
     * useOn} that sees it.
     */
    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        World world = (World) levelObj;
        GrugLinkArms.deliverOrReport(
                (PlayerEntity) testPlayer(),
                "TestTickContext.use_block: There is no player to right-click with.",
                ServerOrnitheAdapter::reportFailure,
                player -> useBlockAs(world, player, x, y, z));
    }

    private void useBlockAs(World world, PlayerEntity player, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // World.getBlock() hands back a block id rather than a Block in this generation, and 0 is
        // air. A click names a block, so a position with none is a mistake in the test rather than
        // a click on nothing.
        if (world.getBlock(blockX, blockY, blockZ) <= 0) {
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
                .interactionManager.useBlock(
                        player,
                        world,
                        player.inventory.getSelectedItem(),
                        blockX,
                        blockY,
                        blockZ,
                        GrugUseBlockFace.face(testPlayerEye(player), blockX, blockY, blockZ));
    }

    /** Asked of the client of the player who started the run, with the world frozen (#1040). */
    @Override
    public boolean hasSound(String path) {
        return GrugLinkArms.callOrReport(
                () -> GrugControlBeta.server().hasSound(path),
                "has_sound: ",
                false,
                ServerOrnitheAdapter::reportFailure);
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        GrugLinkArms.runOrReport(
                () -> GrugControlBeta.server().playUiSound(path, volume, pitch),
                "play_ui_sound: ",
                ServerOrnitheAdapter::reportFailure);
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
            return GrugControlBeta.server()
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
        return GrugLinkArms.ifHereElse(
                GrugLinkArms.bothHere(
                        GrugLinkArms.known(level), GrugControlBeta.server().hasStarter()),
                true,
                () -> clientLightApplied(x, y, z, (World) level));
    }

    private boolean clientLightApplied(double x, double y, double z, World world) {
        long simulated =
                GrugLightSync.lightFingerprint(
                        ((int) Math.floor(x)) >> 4,
                        ((int) Math.floor(y)) >> 4,
                        ((int) Math.floor(z)) >> 4,
                        (lightX, lightY, lightZ) ->
                                world.getLight(LightType.BLOCK, lightX, lightY, lightZ),
                        (lightX, lightY, lightZ) ->
                                world.getLight(LightType.SKY, lightX, lightY, lightZ));
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
     * Ready once the world around the player is loaded, the player is out of any block and the
     * player has landed. The test origin is read from the player's position, and a player still
     * falling when the first test starts gives an origin whose height differs from run to run,
     * which put one test's blocks above the world's height (#1052).
     *
     * <p>{@code onGround} alone is not enough: the server copies it from the client's movement
     * packets, and a client that fell through the terrain while its chunks were arriving reports a
     * position a fraction of a block inside the ground, which the server accepts because the box
     * its collision check tests is contracted by 0.0625. From there the client's own collision
     * never reports support, so the wait timed out with the client in the world and the run already
     * started (#1095). {@link GrugPlayerFloor#mayStart} is that rule, and every dedicated-server
     * loader asks it, so the five cannot drift apart (#1136). The ground is the shape's top, not
     * the block's top, so a player standing on a slab at 70.5 is ready rather than read as half a
     * block inside it (#1102).
     */
    @Override
    public boolean isWorldReady(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        return GrugLinkArms.ifHere(
                super.isWorldReady(playerObj),
                () ->
                        GrugPlayerFloor.mayStart(
                                solidBlocks(getLevel()),
                                player.x,
                                feetY(player),
                                player.z,
                                player.onGround));
    }

    /**
     * The collision shapes of a level's blocks, as {@link GrugPlayerFloor} reads them: the top of
     * the highest box that covers the player's own point, which is what the game's own collision
     * meets there. A slab's floor is half a block below its block top, a stair's lower step is not
     * its upper one, and a block with no collision box at all, such as a torch or a plant, is not a
     * floor (#1102).
     */
    @SuppressWarnings("rawtypes")
    public static GrugPlayerFloor.BlockSource solidBlocks(Object levelObj) {
        World world = (World) levelObj;
        return (x, y, z, pointX, pointZ) -> {
            Block block = GrugLinkArms.tableEntry(Block.BY_ID, world.getBlock(x, y, z));
            if (block == null) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
            // The game's own query, which adds every box at the position that meets the given box,
            // so a shaped block answers the box the player's own column is in rather than its full
            // cube. The box is a thin column through the block at the player's point.
            Box point =
                    Box.of(
                            pointX - POINT_EDGE,
                            y,
                            pointZ - POINT_EDGE,
                            pointX + POINT_EDGE,
                            y + 1.0,
                            pointZ + POINT_EDGE);
            ArrayList boxes = new ArrayList();
            block.addCollisions(world, x, y, z, point, boxes);
            double top = GrugPlayerFloor.BlockSource.NO_COLLISION;
            for (Object boxObj : boxes) {
                top = Math.max(top, ((Box) boxObj).maxY - y);
            }
            return top;
        };
    }

    /**
     * Keeps the block at the player's feet when it is a collision shape, because that shape's top
     * is the surface the hold puts the player on: a slab at 70 stops the feet at 70.5, so clearing
     * it would drop them onto the room's stand block at 70 by the missing half block (#1102). A
     * block with no collision shape, such as a plant, water or a wall torch, is cleared as on every
     * other loader.
     */
    @Override
    protected boolean keepsFeetBlock(PlayerEntity player) {
        return GrugPlayerFloor.hasCollisionAt(
                solidBlocks(getLevel()), player.x, feetY(player), player.z);
    }

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     *
     * <p>A region packet still owed to the joined client is not settled, and neither is the poll
     * that only arms its send: the region is what marks the client's sections dirty, so a capture
     * taken before it reads a mesh baked from the light before it. Such a poll therefore answers
     * before it asks the client, as 1.2.5-forge's does, and the poll after the send asks it fresh:
     * {@link GrugLightResend#due} sends nothing for the first poll after a change, which used to
     * fall through to the client's answer and let the capture go (#1392, #1161).
     */
    @Override
    public boolean isWorldSettled() {
        return GrugLinkArms.ifHereElse(
                GrugControlBeta.server().hasStarter(), true, this::pollClientSettled);
    }

    private boolean pollClientSettled() {
        boolean owed = lightResend.pending();
        GrugLinkArms.runIf(owed, this::resendLight);
        // A poll that owed the region asked no client, so nothing is settled for this poll. A
        // client that refuses the question fails the test and settles the poll.
        boolean clientSettled =
                GrugLinkArms.ifHereElse(
                        !owed,
                        false,
                        () ->
                                lightRetry.settled(
                                        GrugLinkArms.callOrReport(
                                                this::lightVerdict,
                                                "TestTickContext.world_settled: ",
                                                GrugLightRetry.Verdict.SETTLED,
                                                ServerOrnitheAdapter::reportFailure),
                                        lightResend::rearm));
        return GrugRenderSettle.serverSettled(clientSettled, owed);
    }

    /**
     * Asks the joined client whether it settled, naming the first cell whose light differs when its
     * answers say it holds another field (#1695). A divergence that outlasted its re-sends is
     * thrown as the report, so the poll reports it rather than asking until the tick budget is
     * gone. See {@link GrugLightRetry}.
     */
    private GrugLightRetry.Verdict lightVerdict() throws GrugControlServer.ControlException {
        return lightRetry.reportIfPersisted(
                lightRetry.answered(
                        GrugControlBeta.server().settle(lightAsk()),
                        lightResend.lastBox(),
                        this::serverBlockLight,
                        this::serverSkyLight,
                        GrugControlBeta.server()::probeLight,
                        this::clientLightFingerprint));
    }

    /** This server's block light at a cell, read only when a divergence locates one. */
    @GrugGenerated(
            "read only when a client's second divergent answer locates a differing cell, which a"
                    + " passing run never has")
    private int serverBlockLight(int x, int y, int z) {
        return ((World) getLevel()).getLight(LightType.BLOCK, x, y, z);
    }

    /** This server's sky light at a cell, read only when a divergence locates one. */
    @GrugGenerated(
            "read only when a client's second divergent answer locates a differing cell, which a"
                    + " passing run never has")
    private int serverSkyLight(int x, int y, int z) {
        return ((World) getLevel()).getLight(LightType.SKY, x, y, z);
    }

    /**
     * The client's light fingerprint of a cell's section, asked only when a divergence locates one.
     */
    @GrugGenerated(
            "asked only when a client's second divergent answer locates a differing cell, which a"
                    + " passing run never has")
    private long clientLightFingerprint(int x, int y, int z)
            throws GrugControlServer.ControlException {
        return (long) GrugControlBeta.server().clientLightFingerprint(x, y, z);
    }

    /** The light answers so far, so a divergence is reported when it stops moving (#1695). */
    private final GrugLightRetry lightRetry = new GrugLightRetry();

    /** Forgets the last test's light box and divergence, before the next test's room build. */
    @Override
    public void forgetLightForTest() {
        lightResend.forget();
        lightRetry.forget();
    }

    /**
     * Keeps the light resend to the room this test builds, in place of every block the room build
     * cleared. See {@link ModLoaderAdapter#boundLightResend} and #1695.
     */
    @Override
    public void boundLightResend(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        lightResend.bound(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * The light of the box this server last sent the joined client, as a settle ask's text (#1695).
     * The box is the region {@link GrugLightResend#due} sent, so the client is compared over the
     * cells it was sent rather than a section that holds cells it never received, and the field is
     * read after {@link #resendLight} recomputed it.
     */
    private String lightAsk() {
        World world = (World) getLevel();
        return GrugLightAsk.ask(
                lightResend.lastBox(),
                (x, y, z) -> world.getLight(LightType.BLOCK, x, y, z),
                (x, y, z) -> world.getLight(LightType.SKY, x, y, z));
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
        PlayerEntity starter = GrugControlBeta.starterPlayer();
        World world = (World) getLevel();
        return GrugLinkArms.ifHereElse(
                GrugLinkArms.bothHere(GrugLinkArms.known(starter), GrugLinkArms.known(world)),
                false,
                () -> sendRegions(starter, world, regions));
    }

    private boolean sendRegions(PlayerEntity starter, World world, List<int[]> regions) {
        // The placements left the field their order made, which differs from the one the final
        // blocks define and from the client-only run's, so it is recomputed before it is sent.
        int[] box = lightResend.lastBox();
        relightRoom(
                world, box[0], Math.max(box[1], 0), box[2], box[3], Math.min(box[4], 127), box[5]);
        for (int[] region : regions) {
            // A chunk this server never loaded is an empty one with no block array, which a region
            // packet cannot be made of, and the client holds no light for it either.
            GrugLinkArms.deliverIf(
                    GrugLinkArms.known(world.getChunk(region[0] >> 4, region[2] >> 4).blocks),
                    region,
                    present -> sendRegion(starter, world, present));
        }
        return true;
    }

    private static void sendRegion(PlayerEntity starter, World world, int[] region) {
        ((ServerPlayerEntity) starter)
                .networkHandler.sendPacket(
                        new WorldChunkPacket(
                                region[0], region[1], region[2], region[3], region[4], region[5],
                                world));
        resendSavedData(world, (ServerPlayerEntity) starter, region);
    }

    /**
     * Sends the saved data of every grug block entity the region holds, because the client drops
     * its block entities in a region a chunk region packet covers and makes empty ones on the next
     * read, and this version's region packet carries no tile entity data. Without it a number a
     * script saved before the resend is never seen by the client again (#1040).
     */
    private static void resendSavedData(World world, ServerPlayerEntity player, int[] region) {
        for (int x = region[0]; x < region[0] + region[3]; x++) {
            for (int y = region[1]; y < region[1] + region[4]; y++) {
                for (int z = region[2]; z < region[2] + region[5]; z++) {
                    BlockEntity entity = world.getBlockEntity(x, y, z);
                    if (entity instanceof GrugBlockEntity) {
                        player.networkHandler.sendPacket(
                                new GrugSavedDataPacket((GrugBlockEntity) entity, true));
                    }
                }
            }
        }
    }

    /** Sweeps of the room's cells the light may take before it is called not settling. */
    private static final int RELIGHT_SWEEP_LIMIT = 16;

    /**
     * Brings the room's sky and block light to the one field its blocks define, whatever rooms were
     * built before it.
     *
     * <p>Both fields are swept because the ask's fingerprint reads both: a block update recomputes
     * only the field it was queued for, so a room put up where the sky was open keeps the sky's 15
     * until it is queued for the sky too, and the resend would then impose a field its blocks do
     * not define. The box is handed to the engine as {@link GrugRelight} cuts it, because this
     * version's {@code LightUpdate} prints {@code Light too large, skipping!} and recomputes
     * nothing for a box over 32768 cells, which a radius 13 room's box is.
     */
    private static void relightRoom(
            World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        GrugRelight.settleImmediately(
                new ImmediateLight(world), minX, minY, minZ, maxX, maxY, maxZ, RELIGHT_SWEEP_LIMIT);
    }

    /**
     * This version's engine as {@link GrugRelight.ImmediateRoom} drives it. {@code updateLight}
     * recomputes the box there and then, so each {@link GrugRelight} piece is drained before the
     * next is handed over, and the hashed fields are the ones the resend will send. Both fields are
     * updated, because an update recomputes only the field it was given.
     */
    private static final class ImmediateLight implements GrugRelight.ImmediateRoom {
        private final World world;

        ImmediateLight(World world) {
            this.world = world;
        }

        @Override
        public void update(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.world.updateLight(LightType.SKY, minX, minY, minZ, maxX, maxY, maxZ);
            this.world.updateLight(LightType.BLOCK, minX, minY, minZ, maxX, maxY, maxZ);
            GrugLinkArms.drain(this.world, this.world::doLightUpdates);
        }

        @Override
        public int sky(int x, int y, int z) {
            return this.world.getLight(LightType.SKY, x, y, z);
        }

        @Override
        public int block(int x, int y, int z) {
            return this.world.getLight(LightType.BLOCK, x, y, z);
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
        GrugControlBeta.server().forgetQueuedGives();
    }

    /**
     * The feet are the bottom of the bounding box, rounded to a millionth to shed the arithmetic's
     * last bits: the server's player keeps the feet in {@code y}, so the client's eye offset built
     * the test room's floor a block and a half too low and dropped the player out of the world
     * (#163). A client's box is not used because it sits {@code ySize} above the feet for a few
     * ticks after a step-up, a respawn or a teleport.
     */
    @Override
    protected double feetY(PlayerEntity player) {
        return Math.rint(player.shape.minY * 1e6) / 1e6;
    }

    /**
     * The eye the room was built around: {@link #feetY} is the floor's anchor, and this server's
     * player keeps the feet in {@code y} while the client keeps the eye there, so reading {@code y}
     * directly named a camera one eye height below the joined client's (#1178). The room build is
     * unchanged, since this only adds the eye above the same anchor.
     */
    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        return GrugTestOrigin.eyeOfFeet(player.x, feetY(player), player.z);
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
        if (!GrugTestPlayerMove.canMoveTo(
                player,
                (World) getLevel(),
                world -> world.isChunkLoaded((int) Math.floor(x), 64, (int) Math.floor(z)))) {
            return false;
        }
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

    /** The world's own tick, as on the other loaders, so a test need not wait for the server. */
    @Override
    public int tickLevel(Object levelObj, int ticks) {
        return GrugServerRun.advance((World) levelObj, ticks);
    }

    /** The client's cap of ticks for one frame, which the server takes as extra ticks per tick. */
    @Override
    public double maxGameSpeed() {
        return CLIENT_TICKS_PER_FRAME;
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
        EntityMap tracker = MinecraftServerInstance.get().getEntityMap(0);
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
                        world.getEntitiesOfType(ItemEntity.class, Box.of(x1, y1, z1, x2, y2, z2)));
        List<net.minecraft.entity.Entity> mobs = new ArrayList<>(spawnedMobs);
        super.clearItemEntities(levelObj, x1, y1, z1, x2, y2, z2);
        EntityMap tracker = MinecraftServerInstance.get().getEntityMap(0);
        for (ItemEntity item : items) {
            tracker.onEntityRemoved(item);
        }
        for (net.minecraft.entity.Entity mob : mobs) {
            tracker.onEntityRemoved(mob);
        }
    }
}
