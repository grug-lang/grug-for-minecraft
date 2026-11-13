package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugClientSavedNumbers;
import net.grug.minecraft.core.GrugClientTestCalls;
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
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.control.GrugControlBeta;
import net.grug.minecraft.ornithe.gui.GrugMenu;
import net.grug.minecraft.ornithe.gui.GrugScreenPacket;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.ornithemc.osl.lifecycle.api.server.MinecraftServerInstance;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 * <p>Excluded from coverage because only the dispatch-only multiplayer job launches a server, and
 * the coverage gate merges no server run's data (#167 stage 4). See #163.
 */
@GrugGenerated("a dedicated server, which only the dispatch-only multiplayer job launches")
public class ServerOrnitheAdapter extends OrnitheAdapter {

    /**
     * How thin the column is that asks which collision boxes cover the player's own point. It only
     * has to be non-zero so a box whose edge is exactly on the point still intersects it.
     */
    private static final double POINT_EDGE = 1e-9;

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
        if (playerObj instanceof PlayerEntity) {
            GrugServerRun.closeOpenMenu((PlayerEntity) playerObj);
        }
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
        Object playerObj = testPlayer();
        if (playerObj instanceof PlayerEntity) {
            GrugServerRun.closeOpenMenu((PlayerEntity) playerObj);
        }
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
        Object playerObj = testPlayer();
        if (!(playerObj instanceof PlayerEntity)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.set_carried_item: There is no player to set a cursor on.");
            return;
        }
        PlayerEntity player = (PlayerEntity) playerObj;
        player.inventory.setCursorItem(itemObj == null ? null : new ItemStack((Item) itemObj));
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
                    GrugControlBeta::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The camera reads this server makes, which its joined client answers with its own camera: a
     * dedicated server has none, so a test that asserts where the client draws from asks there
     * (#1161). A failure is a host function error, which fails the test.
     */
    private final GrugTestCamera.Forwarded camera =
            new GrugTestCamera.Forwarded(
                    GrugControlBeta::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The saved-number reads this server makes, which its joined client answers from its own copy
     * of the block entity: a dedicated server holds the simulated copy, not the one the client's
     * frame pass reads (#1162).
     */
    private final GrugClientSavedNumbers.Forwarded savedNumbers =
            new GrugClientSavedNumbers.Forwarded(
                    GrugControlBeta::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The light comparisons this server makes, which its joined client answers from its own copy of
     * the section: a dedicated server holds the simulated copy, not the one the client's frame pass
     * bakes the room from (#1240).
     */
    private final GrugClientLight.Forwarded clientLight =
            new GrugClientLight.Forwarded(
                    GrugControlBeta::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The overlay reads this server makes, which its joined client answers about the frame it
     * draws: an overlay belongs to a client's frame pass, so a server never records one (#1099). A
     * failure is a host function error, which fails the test.
     */
    private final GrugOverlayCalls.Forwarded overlays =
            new GrugOverlayCalls.Forwarded(
                    GrugControlBeta::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The mining crack and the client resource files of a hot reload, which the joined client
     * answers from its own world and its own mods directory (#1040): the crack is its frame pass's,
     * and a texture is read by the client alone.
     */
    private final GrugClientTestCalls.Forwarded clientTestCalls =
            new GrugClientTestCalls.Forwarded(
                    GrugControlBeta::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    @Override
    public GrugClientTestCalls.Forwarded forwardedClientTestCalls() {
        return clientTestCalls;
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
     * player, so the choice only decides whose position {@code Test.get_origin} reports. The
     * inherited {@code testPlayerEye} and {@code getTestOrigin} both go through this answer, so
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
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's interaction manager sends a use packet the
     * server thread is blocked inside this call and so cannot process. So the click is made here
     * instead, in the same order the game's server uses for a client's use packet: the block's
     * {@code use} first, and the held item's {@code useOn} when the block declines.
     *
     * <p>The dedicated server does have a {@code ServerPlayerInteractionManager.useBlock}, which is
     * what a client's use packet reaches, but the client jar this layer compiles against does not
     * hold that class (only the server jar does), so the two calls it makes are made here in the
     * same order instead.
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
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // World.getBlock() hands back a block id rather than a Block in this generation.
        int blockId = world.getBlock(blockX, blockY, blockZ);
        Block block = (blockId >= 0 && blockId < Block.BY_ID.length) ? Block.BY_ID[blockId] : null;

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

        if (!block.use(world, blockX, blockY, blockZ, player)) {
            ItemStack held = player.inventory.getSelectedItem();
            if (held != null) {
                held.useOn(
                        player,
                        world,
                        blockX,
                        blockY,
                        blockZ,
                        GrugUseBlockFace.face(testPlayerEye(player), blockX, blockY, blockZ));
            }
        }
    }

    /** Asked of the client of the player who started the run, with the world frozen (#1040). */
    @Override
    public boolean hasSound(String path) {
        try {
            return GrugControlBeta.server().hasSound(path);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "has_sound: " + e.getMessage());
            return false;
        }
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        try {
            GrugControlBeta.server().playUiSound(path, volume, pitch);
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
            return GrugControlBeta.server()
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
        if (level == null || !GrugControlBeta.server().hasStarter()) {
            return true;
        }
        World world = (World) level;
        long simulated =
                GrugLightSync.lightFingerprint(
                        ((int) Math.floor(x)) >> 4,
                        ((int) Math.floor(y)) >> 4,
                        ((int) Math.floor(z)) >> 4,
                        (lightX, lightY, lightZ) ->
                                world.getLight(LightType.BLOCK, lightX, lightY, lightZ));
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
        double feet = feetY(player);
        GrugPlayerFloor.BlockSource blocks = solidBlocks(getLevel());
        return super.isWorldReady(playerObj)
                && GrugPlayerFloor.mayStart(blocks, player.x, feet, player.z, player.onGround);
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
            int id = world.getBlock(x, y, z);
            if (id <= 0 || id >= Block.BY_ID.length) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
            Block block = Block.BY_ID[id];
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
        double feet = feetY(player);
        GrugPlayerFloor.BlockSource blocks = solidBlocks(getLevel());
        return blocks.collisionTop(
                        (int) Math.floor(player.x),
                        (int) Math.floor(feet),
                        (int) Math.floor(player.z),
                        player.x,
                        player.z)
                != GrugPlayerFloor.BlockSource.NO_COLLISION;
    }

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlBeta.server().hasStarter()) {
            return true;
        }
        try {
            return GrugControlBeta.server().worldSettled();
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

    /** The tests listed in {@code server-unsupported-tests.txt}, which says why. */
    @Override
    public Set<String> serverUnsupportedTests() {
        try (InputStream in =
                ServerOrnitheAdapter.class.getResourceAsStream("/server-unsupported-tests.txt")) {
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
}
