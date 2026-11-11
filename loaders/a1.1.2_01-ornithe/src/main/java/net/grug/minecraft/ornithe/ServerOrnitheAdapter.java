package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugClientSavedNumbers;
import net.grug.minecraft.core.GrugControlServer;
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
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.grug.minecraft.ornithe.gui.GrugScreenContentPacket;
import net.grug.minecraft.ornithe.gui.GrugScreenOpenPacket;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.entity.mob.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The adapter for an Alpha a0.2.1 dedicated server, which is the same loader as {@link
 * OrnitheAdapter} in a second process rather than a second mode.
 *
 * <p>It extends the client adapter, because everything that is not about which process this is, is
 * the same code: the block registry, the inventory and the entity host functions name no client
 * class. What a server cannot inherit is the pair of handles, the screen and the player snap, and
 * those are all this replaces. {@code GUI.open} is not one of them: the menu it builds belongs to
 * the side that owns the inventory, so this server builds the block entity's own menu, sends its
 * layout to the joined client and applies that client's clicks to it (#1098). The screen calls a
 * test makes are still the client's and are forwarded; the cursor calls are the menu's and this
 * server answers them itself. The other client-only functions are inherited and not overridden:
 * each one refuses itself through {@code ModLoaderAdapter.refuseWithoutAClient}, which turns a
 * screenshot test on a server into an err naming the function instead of an error from inside
 * {@code net.minecraft.client}. {@code useBlockForTest} is not one of them either: a click is not a
 * client-only function, so the server right-clicks in its own world, the world the test reads
 * (#1090).
 *
 * <p>A dedicated server has no {@code MinecraftInstance}, so the world comes from the one the
 * server run saw tick ({@link GrugServerRun#world()}), and the players are that world's.
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
     * The menu this server has open for each player, which is the container a joined client's
     * clicks land in (#1098). Alpha has no menu class of its own, and its server keeps no cursor,
     * so {@link GrugAlphaMenu} holds both sides of the slot table and this map is what a click
     * packet is looked up in.
     */
    private static final Map<PlayerEntity, GrugAlphaMenu> MENUS = new WeakHashMap<>();

    /** The window id the next {@code GUI.open} installs its menu under, never 0. */
    private static int lastWindowId = 0;

    /**
     * The window id the next open uses, one after another as vanilla's own counter does. It stays
     * inside 1..32767: never 0, which the client treats as the player's own inventory, and wide
     * enough that a screen replaced long before cannot answer with an id the new menu now holds,
     * which would let its close packet close the new menu while its screen stayed up.
     */
    private static int nextWindowId() {
        lastWindowId = lastWindowId % Short.MAX_VALUE + 1;
        return lastWindowId;
    }

    /**
     * Opens the block entity's GUI as this server's own menu and has the joined client show it
     * (#1098). Alpha's {@code GUI.open} used to build the screen on the client alone, so a click
     * from it applied to the client's copy of the block entity and this server never saw it; now
     * both sides build the same slots from the layout this sends, and a click from the client is
     * applied here and its result is sent back.
     *
     * <p>Whatever menu the player had open is closed first, so its carried stack is dropped where
     * the player stands rather than reappearing on the new screen. The layout packet goes out
     * before the menu's contents, because a client applies contents only to a screen already up
     * under that window id.
     */
    @Override
    public void openGui(Object playerObj, Object blockEntityObj, Object guiBuilderObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        Inventory inventory = (Inventory) blockEntityObj;
        GrugGuiBuilder layout = (GrugGuiBuilder) guiBuilderObj;
        BlockEntity blockEntity = (BlockEntity) blockEntityObj;

        closeMenu(player);

        GrugAlphaMenu menu = GrugAlphaMenu.build(player.inventory, inventory, layout);
        menu.windowId = nextWindowId();
        MENUS.put(player, menu);

        Connection connection = GrugControlAlpha.connectionOfPlayer(player);
        if (connection == null) {
            // No joined client to show it on, so a test that waits for the screen times out. There
            // is nothing else to do here: only a client can draw a screen.
            return;
        }
        connection.send(
                new GrugScreenOpenPacket(
                        menu.windowId, blockEntity.x, blockEntity.y, blockEntity.z, layout));
        connection.send(GrugScreenContentPacket.of(menu));
    }

    /**
     * Applies a click the joined client sent for the menu this server installed under {@code
     * windowId}, then sends back what the menu holds (#1098). A packet for a menu that has since
     * been replaced is ignored, which is what the window id is for.
     *
     * <p>It runs on the game thread, from the connection's own tick, so the game's own packet
     * handling reads it too, and a forwarded click a test waits on is read by {@code
     * applyClientWork} before the runner's next tick.
     */
    public static void clickFromClient(PacketHandler handler, int windowId, int slot, int button) {
        PlayerEntity player = GrugControlAlpha.playerOf(handler);
        Connection connection = GrugControlAlpha.connectionOf(handler);
        if (player == null || connection == null) {
            return;
        }
        GrugAlphaMenu menu = MENUS.get(player);
        if (menu == null || menu.windowId != windowId) {
            return;
        }
        menu.click(player, slot, button);
        connection.send(GrugScreenContentPacket.of(menu));
    }

    /** Closes the menu a joined client closed, dropping the stack it carried where it stands. */
    public static void closeFromClient(PacketHandler handler, int windowId) {
        PlayerEntity player = GrugControlAlpha.playerOf(handler);
        if (player == null) {
            return;
        }
        GrugAlphaMenu menu = MENUS.get(player);
        if (menu == null || menu.windowId != windowId) {
            return;
        }
        closeMenu(player);
    }

    /**
     * Closes the menu this server has open for {@code player}, as the game's own close does, and
     * drops whatever it carried at the player's feet. A player with nothing open has nothing to
     * close.
     */
    private static void closeMenu(PlayerEntity player) {
        GrugAlphaMenu menu = MENUS.remove(player);
        if (menu == null) {
            return;
        }
        ItemStack cursor = menu.cursor;
        menu.cursor = null;
        if (cursor != null) {
            player.dropItem(cursor);
        }
    }

    /**
     * Forgets the menus of players who left. A weak map cannot do this by itself, because a menu
     * holds the player's inventory, which holds the player, so a menu's value keeps its own key
     * alive. Each one is closed rather than dropped from the map, so a stack the player left on the
     * cursor is put where they stood instead of being deleted with the menu. Called from every
     * server tick.
     */
    public static void forgetMenusOfMissingPlayers(List<?> players) {
        for (PlayerEntity player : new ArrayList<>(MENUS.keySet())) {
            if (!players.contains(player)) {
                closeMenu(player);
            }
        }
    }

    /**
     * The screen calls this server makes, which its joined client performs: a server has no screen
     * of its own, so every one of them is a round trip to the client of the player who started the
     * run (#1094). A failure is a host function error, which fails the test.
     */
    private final GrugScreenCalls.Forwarded screens =
            new GrugScreenCalls.Forwarded(
                    GrugControlAlpha::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The camera reads this server makes, which its joined client answers with its own camera: a
     * dedicated server has none, so a test that asserts where the client draws from asks there
     * (#1161). A failure is a host function error, which fails the test.
     */
    private final GrugTestCamera.Forwarded camera =
            new GrugTestCamera.Forwarded(
                    GrugControlAlpha::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The saved-number reads this server makes, which its joined client answers from its own copy
     * of the block entity: a dedicated server holds the simulated copy, not the one the client's
     * frame pass reads (#1162).
     */
    private final GrugClientSavedNumbers.Forwarded savedNumbers =
            new GrugClientSavedNumbers.Forwarded(
                    GrugControlAlpha::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The light comparisons this server makes, which its joined client answers from its own copy of
     * the section: a dedicated server holds the simulated copy, not the one the client's frame pass
     * bakes the room from (#1240).
     */
    private final GrugClientLight.Forwarded clientLight =
            new GrugClientLight.Forwarded(
                    GrugControlAlpha::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The overlay reads this server makes, which its joined client answers about the frame it
     * draws: an overlay belongs to a client's frame pass, so a server never records one (#1099). A
     * failure is a host function error, which fails the test.
     */
    private final GrugOverlayCalls.Forwarded overlays =
            new GrugOverlayCalls.Forwarded(
                    GrugControlAlpha::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    @Override
    public Object getLevel() {
        return GrugServerRun.world();
    }

    /**
     * The first player who joined. A server has no local player, so a test that wants one to build
     * fixtures around has to be given one of the connected players. Which one does not matter: the
     * tests share one world and build in bands above the origin.
     */
    @Override
    public Object testPlayer() {
        World world = GrugServerRun.world();
        if (world == null) {
            return null;
        }
        // The player who started the run, when one has: it is their screen the forwarded calls use.
        PlayerEntity starter = GrugControlAlpha.starterPlayer();
        if (starter != null) {
            return starter;
        }
        List<?> players = world.players;
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
     * <p>The a0.2.1 server has no {@code ServerPlayerInteractionManager} to call: this generation's
     * client jar, which is what this layer compiles against, does not hold one either, so the two
     * calls its {@code useBlock} makes are made here in the same order.
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The client path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106). Alpha's {@code Block.use} takes no face, so it is the held item's {@code useOn} that
     * sees it.
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

        // In Alpha, World.getBlock() hands back a block id rather than a Block.
        int blockId = GrugWorldAccess.blockId(world, blockX, blockY, blockZ);
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
            return GrugControlAlpha.server().hasSound(path);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "has_sound: " + e.getMessage());
            return false;
        }
    }

    /** Played by the client of the player who started the run, which owns the sound engine. */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        try {
            GrugControlAlpha.server().playUiSound(path, volume, pitch);
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
            return GrugControlAlpha.server()
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
     * <p>No light-engine gate is needed here: Alpha computes its light on the server tick, so the
     * tick that placed the test's blocks has already worked it out, and the client's own frame
     * settles its copy.
     */
    @Override
    public boolean lightApplied(double x, double y, double z) {
        Object level = getLevel();
        if (level == null || !GrugControlAlpha.server().hasStarter()) {
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

    /**
     * This server forwards a test's client-only calls to the player who started the run (#1040).
     */
    @Override
    public boolean forwardsClientCalls() {
        return true;
    }

    /**
     * Closes this server's menu and the joined client's screen (#1094, #1098): Alpha's client
     * closes with Escape alone and sends this server nothing, so the menu is closed here as well,
     * which drops its carried stack where the player stands. A screen a test opened otherwise
     * outlives it, and a capture test that follows a GUI test would take a frame of the panel
     * rather than of the world (#418).
     */
    @Override
    public void closeOpenScreenForTest() {
        Object playerObj = testPlayer();
        if (playerObj instanceof PlayerEntity) {
            closeMenu((PlayerEntity) playerObj);
        }
        screens.closeScreen();
    }

    /**
     * The stack on this server's menu cursor (#1098). A server's {@code PlayerInventory} keeps no
     * cursor of its own, so it lives on the open menu, and no open menu means no cursor.
     */
    @Override
    public Object carriedItem() {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) {
            return null;
        }
        GrugAlphaMenu menu = MENUS.get(player);
        return menu == null || menu.cursor == null ? null : menu.cursor.getItem();
    }

    /** Sets this server's menu cursor and sends it to the client that draws it (#1098). */
    @Override
    public void setCarriedItem(Object itemObj) {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.set_carried_item: There is no player to set a cursor on.");
            return;
        }
        GrugAlphaMenu menu = MENUS.get(player);
        if (menu == null) {
            return;
        }
        menu.cursor = itemObj == null ? null : new ItemStack((Item) itemObj);
        sendToPlayer(player, GrugScreenContentPacket.of(menu));
    }

    /** Clears this server's menu cursor and tells the client (#1098). */
    @Override
    public void deleteCarriedItem() {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "OverlayClickContext.delete_carried_item: There is no player to clear a cursor"
                            + " on.");
            return;
        }
        GrugAlphaMenu menu = MENUS.get(player);
        if (menu == null) {
            return;
        }
        menu.cursor = null;
        sendToPlayer(player, GrugScreenContentPacket.of(menu));
    }

    /** Sends a packet to {@code player}'s joined client when this server knows its connection. */
    private static void sendToPlayer(PlayerEntity player, Packet packet) {
        Connection connection = GrugControlAlpha.connectionOfPlayer(player);
        if (connection != null) {
            connection.send(packet);
        }
    }

    /** Whether the joined client of the player who started the run has a screen open (#1094). */
    @Override
    public boolean isScreenOpenForTest() {
        return screens.screenOpen();
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
     * falling when the first test starts gives an origin whose height differs from run to run,
     * which put one test's blocks above the world's height (#1052).
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
        double feet = feetY(player);
        return super.isWorldReady(playerObj)
                && GrugPlayerFloor.mayStart(
                        solidBlocks(getLevel()), player.x, feet, player.z, player.onGround);
    }

    /**
     * The feet are {@code player.y} here. This server's movement handler stores the client's
     * packet's {@code minY}, which the client fills with its own feet, into the player's position,
     * and the server's own teleport confirms the convention by sending {@code minY = y + 1.62}
     * (#1190). The client keeps the eye in {@code y}, which {@link OrnitheAdapter#feetY} answers
     * there.
     */
    @Override
    protected double feetY(PlayerEntity player) {
        return player.y;
    }

    /**
     * The eye the room was built around: {@link #feetY} is the room's anchor, and this server's
     * player keeps the feet in {@code y} where the joined client keeps the eye, so reading {@code
     * y} directly named a camera one eye height below the client's (#1190). The room build is
     * unchanged, since this only adds the eye above the same anchor.
     */
    @Override
    public Vec3 testPlayerEye(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        return GrugTestOrigin.eyeOfFeet(player.x, feetY(player), player.z);
    }

    /**
     * The collision shapes of a level's blocks, as {@link GrugPlayerFloor} reads them: the top of
     * the highest box that covers the player's own point, which is what the game's own collision
     * meets there. A slab's floor is half a block below its block top, a stair's lower step is not
     * its upper one, and a block with no collision box at all, such as a torch or a plant, is not a
     * floor (#1102, #1136).
     *
     * <p>Alpha's dedicated server is a different version from its client (a0.2.1 against
     * a1.1.2_01), so the block id is read through {@link GrugWorldAccess}, which knows the name
     * each side uses. The boxes come from the block's own {@code addCollisions}, the query the
     * game's entity movement makes, with the thin column through the player's own point as the
     * area, so a box is filtered by where the player stands rather than by its block position.
     */
    @SuppressWarnings("rawtypes")
    public static GrugPlayerFloor.BlockSource solidBlocks(Object levelObj) {
        World world = (World) levelObj;
        return (x, y, z, pointX, pointZ) -> {
            int id = GrugWorldAccess.blockId(world, x, y, z);
            if (id <= 0 || id >= Block.BY_ID.length) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
            Block block = Block.BY_ID[id];
            if (block == null) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
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
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame or block packet is owed to anyone;
     * that is also the answer when no client has started a run.
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlAlpha.server().hasStarter()) {
            return true;
        }
        try {
            return GrugControlAlpha.server().worldSettled();
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
     * lives in core's server, and only this loader holds that server. Alpha's adapter cannot name
     * an item, so a give from a joined client is reported by the client instead of reaching here.
     */
    @Override
    public void forgetQueuedGivesForTest() {
        GrugControlAlpha.server().forgetQueuedGives();
    }

    /**
     * The server's {@code Inventory} interface has no {@code setItem}, which the client's has, so
     * the call is made on {@code PlayerInventory} itself, which declares it on both.
     */
    @Override
    public void clearInventoryOf(Object playerObj) {
        PlayerEntity player = (PlayerEntity) playerObj;
        PlayerInventory inventory = player.inventory;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, null);
        }
        inventory.selectedSlot = 0;
    }

    /**
     * The recipe result of a crafting grid, which this server cannot compute. The a0.2.1 server jar
     * has no crafting package at all: Alpha crafted on the client, so the class the client
     * adapter's computation loads, {@code CraftingManager}, is absent here, and running the
     * inherited method would fail with {@code NoClassDefFoundError} rather than answer. Reporting
     * the call keeps the game running with the call's own error, which is what a mod that inserts
     * into an autocrafting table sees; giving the server a recipe table of its own is a separate
     * issue, and until then a test that needs a recipe result stays in this loader's unsupported
     * list.
     */
    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot) {
        Grug.hostFunctionErrorHappened(
                Grug.statePtr,
                "BlockEntity.update_recipe_output: the a0.2.1 server has no crafting manager, so it"
                        + " cannot compute a recipe result.");
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
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
        GrugServerRun.setGameSpeed((int) speed);
        return true;
    }
}
