package net.grug.minecraft.ornithe;

import net.grug.minecraft.core.GrugClientLight;
import net.grug.minecraft.core.GrugClientSavedNumbers;
import net.grug.minecraft.core.GrugClientTestCalls;
import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugLightResend;
import net.grug.minecraft.core.GrugLightSync;
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
import net.grug.minecraft.ornithe.block.GrugBlock;
import net.grug.minecraft.ornithe.block.entity.GrugBlockEntity;
import net.grug.minecraft.ornithe.control.GrugControlAlpha;
import net.grug.minecraft.ornithe.gui.GrugScreenContentPacket;
import net.grug.minecraft.ornithe.gui.GrugScreenOpenPacket;
import net.grug.minecraft.ornithe.gui.GrugUseBlockPacket;
import net.grug.minecraft.ornithe.server.GrugPlayerManagerAccess;
import net.grug.minecraft.ornithe.server.GrugServerRun;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.entity.mob.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.WorldChunkPacket;
import net.minecraft.util.math.Box;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.Deflater;

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

    /**
     * The cursor this server last set on the player's own inventory. The remapped player inventory
     * class on a dedicated server lacks the client-only {@code cursorItem} field, so the server's
     * copy of that cursor lives here instead (#1480).
     */
    private static final Map<PlayerEntity, ItemStack> CARRIED_ITEMS = new WeakHashMap<>();

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
        ItemStack carried = CARRIED_ITEMS.remove(player);

        GrugAlphaMenu menu = GrugAlphaMenu.build(player.inventory, inventory, layout);
        menu.windowId = nextWindowId();
        menu.cursor = carried;
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
                CARRIED_ITEMS.remove(player);
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

    /**
     * The mining crack and the client resource files of a hot reload, which the joined client
     * answers from its own world and its own mods directory (#1040): the crack is its frame pass's,
     * and a texture is read by the client alone.
     */
    private final GrugClientTestCalls.Forwarded clientTestCalls =
            new GrugClientTestCalls.Forwarded(
                    GrugControlAlpha::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    @Override
    public GrugClientTestCalls.Forwarded forwardedClientTestCalls() {
        return clientTestCalls;
    }

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
     * Whether the server's operator list holds the player, which is the game's own check. A
     * dedicated server has no singleplayer owner, so the list is the whole answer (#1331).
     */
    @Override
    public boolean isOperator(Object playerObj) {
        return GrugPlayerManagerAccess.isOp(((PlayerEntity) playerObj).name);
    }

    /** Adds the player to the server's operator list, which the game saves to {@code ops.txt}. */
    @Override
    public void makeOperator(Object playerObj) {
        GrugPlayerManagerAccess.addOp(((PlayerEntity) playerObj).name);
    }

    /**
     * Removes the player from the server's operator list, which the game saves to {@code ops.txt}.
     */
    @Override
    public void removeOperator(Object playerObj) {
        GrugPlayerManagerAccess.removeOp(((PlayerEntity) playerObj).name);
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's interaction manager sends a use packet the
     * server thread is blocked inside this call and so cannot process. So the click is made here
     * instead, in the same order the game's client uses for a right-click: the block's {@code use}
     * first, and the held item's {@code useOn} when the block declines.
     *
     * <p>The a0.2.1 server jar's {@code ServerPlayerInteractionManager} makes that order, but this
     * layer compiles against the client jar, where that class does not exist, so it cannot be named
     * here and this layer makes the order itself. What it cannot make here is the screen: an Alpha
     * container screen is the client's own, because {@code ChestBlock.use} ends in {@code
     * PlayerEntity.openChestMenu}, which a joined client overrides to open the game's chest screen
     * and which sends a server player nothing (#1494). A vanilla block's click is therefore also
     * sent to the joined client, which runs the same activation on its own copy, the half a real
     * Alpha client made for itself after sending its use packet. A grug block is not sent: its
     * {@code use} opens the menu this server holds (#1098).
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
                    Grug.statePtr,
                    "TestTickContext.use_block: There is no player to right-click with.");
            return;
        }

        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);

        // In Alpha, World.getBlock() hands back a block id rather than a Block, and 0 is air. A
        // click names a block, so a position with none is a mistake in the test rather than a
        // click on nothing.
        int blockId = GrugWorldAccess.blockId(world, blockX, blockY, blockZ);
        if (blockId <= 0 || blockId >= Block.BY_ID.length || Block.BY_ID[blockId] == null) {
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
        Block block = Block.BY_ID[blockId];

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

        if (block instanceof GrugBlock) {
            return;
        }
        // The client's copy needs the block this click names, which a test may have placed in this
        // same tick, before the click that opens its screen arrives (#1494).
        GrugServerRun.flushBlockChanges();
        Connection connection = GrugControlAlpha.connectionOfPlayer(player);
        if (connection != null) {
            connection.send(new GrugUseBlockPacket(blockX, blockY, blockZ));
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

    /**
     * This server forwards a test's client-only calls to the player who started the run (#1040).
     */
    @Override
    public boolean forwardsClientCalls() {
        return true;
    }

    /**
     * Opens the player's own inventory on the client of the player who started the run, whose own
     * inventory it is (#1094). Alpha's own inventory screen is the client's alone, so the only menu
     * this server can hold is a block entity's, and it is closed first so both sides are on the
     * player's inventory.
     */
    @Override
    public void openInventory(Object playerObj) {
        if (playerObj instanceof PlayerEntity) {
            closeMenu((PlayerEntity) playerObj);
        }
        screens.openInventory();
    }

    /**
     * Clicked on the client's screen, by the client of the player who started the run (#1094). The
     * press happens on the client and a block entity's menu click it sends is applied here by
     * {@link #clickFromClient} on a later tick, never inside this call.
     */
    @Override
    public boolean clickScreen(double x, double y, double button) {
        return screens.clickScreen(x, y, (int) button);
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
     * The stack on this server's cursor (#1098). A server's custom menu keeps its cursor in the
     * menu, but the player's own inventory keeps it in the inventory, and a server with neither
     * open has no cursor.
     */
    @Override
    public Object carriedItem() {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) {
            return null;
        }
        GrugAlphaMenu menu = MENUS.get(player);
        if (menu != null) {
            return menu.cursor == null ? null : menu.cursor.getItem();
        }
        ItemStack stack = CARRIED_ITEMS.get(player);
        return stack == null ? null : stack.getItem();
    }

    /** Sets this server's cursor and sends it to the client that draws it (#1098, #1389). */
    @Override
    public void setCarriedItem(Object itemObj) {
        PlayerEntity player = (PlayerEntity) testPlayer();
        if (player == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "TestTickContext.set_carried_item: There is no player to set a cursor on.");
            return;
        }
        GrugAlphaMenu menu = MENUS.get(player);
        if (menu != null) {
            menu.cursor = itemObj == null ? null : new ItemStack((Item) itemObj);
            sendToPlayer(player, GrugScreenContentPacket.of(menu));
            return;
        }
        ItemStack stack = itemObj == null ? null : new ItemStack((Item) itemObj);
        CARRIED_ITEMS.put(player, stack);
        sendCursorToPlayer(player, stack);
    }

    /** Clears this server's cursor and tells the client (#1098, #1389). */
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
        if (menu != null) {
            menu.cursor = null;
            sendToPlayer(player, GrugScreenContentPacket.of(menu));
            return;
        }
        CARRIED_ITEMS.remove(player);
        sendCursorToPlayer(player, null);
    }

    /** Sends the cursor of the player's own inventory to {@code player}'s joined client. */
    private static void sendCursorToPlayer(PlayerEntity player, ItemStack cursor) {
        Connection connection = GrugControlAlpha.connectionOfPlayer(player);
        if (connection != null) {
            connection.send(GrugScreenContentPacket.forInventoryCursor(cursor));
        }
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
        boolean hasWorld = world != null;
        boolean loaded =
                hasWorld && world.isChunkLoaded((int) Math.floor(x), 64, (int) Math.floor(z));
        if (!GrugTestPlayerMove.canMove(player != null, hasWorld, loaded)) {
            return false;
        }
        GrugServerRun.moveToFeet(player, x, y, z);
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
     *
     * <p>A region packet still owed to the joined client is not settled, and neither is the poll
     * that only arms its send: the region is what marks the client's sections dirty, so a capture
     * taken before it reads a mesh baked from the light before it. Such a poll therefore answers
     * before it asks the client, as 1.2.5-forge's does, and the poll after the send asks it fresh:
     * {@link GrugLightResend#due} sends nothing for the first poll after a change, which used to
     * fall through to the client's answer and let the capture go (#1392, #1161).
     *
     * <p>A poll is one question, not a guarantee: the owed polls answer false without asking the
     * client, because the region has to follow the end-of-tick flush of the block changes and
     * sending earlier would be overwritten by the client's relight. A test that needs the client to
     * have a change therefore asks until it answers true (#1502).
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlAlpha.server().hasStarter()) {
            return true;
        }
        boolean owed = lightResend.pending();
        if (owed) {
            resendLight();
        }
        // A poll that owed the region asked no client, so nothing is settled for this poll.
        boolean clientSettled = false;
        if (!owed) {
            try {
                clientSettled = GrugControlAlpha.server().worldSettled();
            } catch (GrugControlServer.ControlException e) {
                Grug.hostFunctionErrorHappened(
                        Grug.statePtr, "TestTickContext.world_settled: " + e.getMessage());
                return true;
            }
        }
        return GrugRenderSettle.serverSettled(clientSettled, owed);
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
        PlayerEntity starter = GrugControlAlpha.starterPlayer();
        World world = (World) getLevel();
        if (starter == null || world == null) {
            return false;
        }
        // The placements left the field their order made, which differs from the one the final
        // blocks define and from the client-only run's, so it is recomputed before it is sent.
        int[] box = lightResend.lastBox();
        relightRoom(
                world, box[0], Math.max(box[1], 0), box[2], box[3], Math.min(box[4], 127), box[5]);
        for (int[] region : regions) {
            // A chunk this server never loaded is an empty one with no block array, which a region
            // packet cannot be made of, and the client holds no light for it either.
            if (world.getChunkAt(region[0] >> 4, region[2] >> 4).blocks == null) {
                continue;
            }
            sendToPlayer(starter, buildWorldChunkPacket(world, region));
        }
        return true;
    }

    /** Sweeps of the room's cells the light may take before it is called not settling. */
    private static final int RELIGHT_SWEEP_LIMIT = 16;

    /**
     * Brings the room's block light to the one field its blocks define, whatever rooms were built
     * before it.
     */
    private static void relightRoom(
            World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        GrugRelight.sweepUntilStable(
                () -> relightSweep(world, minX, minY, minZ, maxX, maxY, maxZ), RELIGHT_SWEEP_LIMIT);
    }

    /**
     * Recomputes the block light of every cell of the box once and hashes what the box holds
     * afterwards.
     */
    private static long relightSweep(
            World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        world.updateLight(LightType.BLOCK, minX, minY, minZ, maxX, maxY, maxZ);
        while (world.doLightUpdates()) {}
        long hash = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    hash = hash * 31 + world.getLight(LightType.BLOCK, x, y, z);
                }
            }
        }
        return hash;
    }

    /**
     * Builds the Alpha region packet for the given region, which has no constructor that takes a
     * world: the compressed data is assembled from the server's blocks, metadata and light.
     */
    private static WorldChunkPacket buildWorldChunkPacket(World world, int[] region) {
        int x = region[0];
        int y = region[1];
        int z = region[2];
        int sizeX = region[3];
        int sizeY = region[4];
        int sizeZ = region[5];

        // The nibble sections are packed two y layers per byte, aligned to even y. Round the
        // region out to whole nibble bytes so the client unpacks the values we send.
        int adjustedY = y & ~1;
        int adjustedSizeY = ((y + sizeY + 1) & ~1) - adjustedY;

        WorldChunkPacket packet = new WorldChunkPacket();
        packet.x = x;
        packet.y = adjustedY;
        packet.z = z;
        packet.sizeX = sizeX;
        packet.sizeY = adjustedSizeY;
        packet.sizeZ = sizeZ;
        packet.shouldDelay = true;

        byte[] raw = packChunkData(world, x, adjustedY, z, sizeX, adjustedSizeY, sizeZ);
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        packet.data = new byte[raw.length];
        setCompressedSize(packet, deflater.deflate(packet.data));
        deflater.end();
        return packet;
    }

    /** Packs the region's cells with {@link GrugChunkData}, which documents the layout. */
    private static byte[] packChunkData(
            World world, int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
        return GrugChunkData.pack(x, y, z, sizeX, sizeY, sizeZ, new WorldSource(world));
    }

    /**
     * A region's cells read from the server's own {@link World}, which {@link GrugChunkData} cannot
     * name itself without tying the layout its test pins to a game class. It carries {@code
     * GrugGenerated} for the reason this adapter does: an Alpha world exists only inside a running
     * game, so no unit test can stand in for it, and only the dispatch-only multiplayer job
     * launches that game.
     */
    @GrugGenerated(
            "reads Alpha's dedicated server World, which only the dispatch-only multiplayer job"
                    + " launches")
    private static final class WorldSource implements GrugChunkData.Source {

        private final World world;

        WorldSource(World world) {
            this.world = world;
        }

        @Override
        public int block(int x, int y, int z) {
            return world.getBlock(x, y, z);
        }

        @Override
        public int metadata(int x, int y, int z) {
            return world.getBlockMetadata(x, y, z);
        }

        @Override
        public int blockLight(int x, int y, int z) {
            return world.getLight(LightType.BLOCK, x, y, z);
        }

        @Override
        public int skyLight(int x, int y, int z) {
            return world.getLight(LightType.SKY, x, y, z);
        }
    }

    /**
     * The Alpha packet keeps its compressed size private, so it has to be set by reflection. The
     * field is {@code compressedSize} in the a1.1.2_01 development client this layer is compiled
     * against, and {@code f_62093482} in the Ornithe intermediary the a0.2.1 dedicated server runs
     * (its obfuscated class is {@code cz}, whose {@code h} is that intermediary), which both the
     * server jar and a player's client jar carry, so both names are tried (#1486).
     */
    private static final Field COMPRESSED_SIZE_FIELD = compressedSizeField();

    private static Field compressedSizeField() {
        try {
            return GrugFieldAccess.field(WorldChunkPacket.class, "compressedSize", "f_62093482");
        } catch (NoSuchFieldException e) {
            throw Grug.fatal(
                    "neither compressedSize, the a1.1.2_01 development name, nor f_62093482, the"
                            + " Ornithe intermediary name of the a0.2.1 dedicated server's"
                            + " ornithe-server/server.jar, is a field of WorldChunkPacket, so the"
                            + " region packet has no compressed size to send",
                    e);
        }
    }

    private static void setCompressedSize(WorldChunkPacket packet, int size) {
        try {
            COMPRESSED_SIZE_FIELD.setInt(packet, size);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set WorldChunkPacket.compressedSize", e);
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
        CARRIED_ITEMS.remove(player);
        sendCursorToPlayer(player, null);
    }

    /**
     * The recipe result of a crafting grid, from this server's own recipe table (#1387).
     *
     * <p>The a0.2.1 server jar has no crafting package at all: Alpha crafted on the client, so the
     * class the client adapter's computation loads, {@code CraftingManager}, is absent here, and
     * running the inherited method would fail with {@code NoClassDefFoundError} rather than answer.
     * {@link GrugServerRecipes} holds the recipes the client registers, matched by the same rule in
     * core, so a grid answers the result the client would give for it.
     */
    @Override
    public void updateRecipeOutput(Object blockEntityObj, double startSlot) {
        if (!(blockEntityObj instanceof GrugBlockEntity)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "update_recipe_output: "
                            + blockEntityObj.getClass().getName()
                            + " has no crafting result container.");
            return;
        }
        GrugBlockEntity gbe = (GrugBlockEntity) blockEntityObj;
        ItemStack result =
                GrugServerRecipes.result(
                        GrugServerRecipes.gridOf((Inventory) blockEntityObj, (int) startSlot));
        gbe.setResultStack(result != null ? result.copy() : null);
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

    /**
     * Sweeps the items as the client-only game does, then tells the joined client they are gone.
     * The sweep takes a dead item out of the world's lists itself, so the world's own update never
     * reaches the entity tracker for it and no destroy packet is sent: the joined client kept a
     * ghost of every swept item, such as a diamond a pipe test dropped, and drew it into later
     * captures (#1096, #1068). The tracker is a server-only class, so it is reached by intermediary
     * name through {@link GrugPlayerManagerAccess}.
     */
    @Override
    public void clearItemEntities(
            Object levelObj, double x1, double y1, double z1, double x2, double y2, double z2) {
        World world = (World) levelObj;
        List<ItemEntity> items =
                new ArrayList<>(
                        world.getEntitiesOfType(ItemEntity.class, Box.of(x1, y1, z1, x2, y2, z2)));
        super.clearItemEntities(levelObj, x1, y1, z1, x2, y2, z2);
        for (ItemEntity item : items) {
            GrugPlayerManagerAccess.untrack(item);
        }
    }
}
