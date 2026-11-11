package net.grug.minecraft.forge;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * The adapter for a 1.20.6 dedicated server, which is the same loader as {@link ForgeAdapter} in a
 * second process rather than a second mode.
 *
 * <p>It extends the client adapter rather than implementing {@link
 * net.grug.minecraft.core.ModLoaderAdapter} again, because everything that is not about which
 * process this is, is the same code: the registries have no client in them, the recipe manager has
 * no client in it, and neither has any of the inventory or entity host functions.
 *
 * <p>Only the side and the player are replaced. {@code getLevel} is not, because the client adapter
 * already prefers the running server's world when there is one, and a dedicated server has one from
 * the moment it starts. {@code getTestOrigin} is not either: it and the client adapter's {@code
 * testPlayerEye} go through {@link #testPlayer()}, so a server is measured from the player it chose
 * rather than from the null local client player. See #188.
 *
 * <p>The client-only functions are inherited too, and are not overridden here: each one refuses
 * itself through {@code ModLoaderAdapter.refuseWithoutAClient}, which is what turns a screenshot
 * test on a server into an err naming the function, rather than an exception from inside {@code
 * net.minecraft.client}. {@code useBlockForTest} is not one of them and is overridden: a click is
 * not a client-only function, so the server right-clicks in its own world, the world the test reads
 * (#1090). {@code playUiSound} is overridden as well: the sound engine belongs to the client, so a
 * server forwards the play to the player who started the run (#1032). The screen calls are
 * forwarded the same way (#1094), and so are the cursor calls, which this server answers from its
 * own menu because that is the menu a click is applied to.
 *
 * <p>Whole class is excluded from coverage until CI launches a server: see #167.
 */
@GrugGenerated(
        "a dedicated server, which only the dispatch-only multiplayer job of #167 launches, and the"
                + " coverage gate does not count that job")
public class ServerForgeAdapter extends ForgeAdapter {

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
     * The screen calls this server makes, which its joined client performs: a server has no screen
     * of its own, so every one of them is a round trip to the client of the player who started the
     * run (#1094). A failure is a host function error, which fails the test.
     */
    private final GrugScreenCalls.Forwarded screens =
            new GrugScreenCalls.Forwarded(
                    GrugControlForge::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The camera reads this server makes, which its joined client answers with its own camera: a
     * dedicated server has none, so a test that asserts where the client draws from asks there
     * (#1161). A failure is a host function error, which fails the test.
     */
    private final GrugTestCamera.Forwarded camera =
            new GrugTestCamera.Forwarded(
                    GrugControlForge::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The saved-number reads this server makes, which its joined client answers from its own copy
     * of the block entity: a dedicated server holds the simulated copy, not the one the client's
     * frame pass reads (#1162).
     */
    private final GrugClientSavedNumbers.Forwarded savedNumbers =
            new GrugClientSavedNumbers.Forwarded(
                    GrugControlForge::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * The overlay reads this server makes, which its joined client answers about the frame it
     * draws: an overlay belongs to a client's frame pass, so a server never records one (#1099). A
     * failure is a host function error, which fails the test.
     */
    private final GrugOverlayCalls.Forwarded overlays =
            new GrugOverlayCalls.Forwarded(
                    GrugControlForge::server,
                    message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message));

    /**
     * A dedicated server has no client, no integrated server and no save text to draw, and the
     * inherited call reaches for the client's {@code Minecraft}. A test whose name asks for the
     * forced save therefore gets none here, which is right: the indicator it exists for is drawn by
     * the client that runs the test, not by this process.
     */
    @Override
    public void requestAutosaveForTest() {}

    /**
     * The first player who joined.
     *
     * <p>A server has no local player, so a test that wants one to build fixtures around and to
     * right-click with has to be given one of the connected players. Which one does not matter: the
     * tests share one world and build in bands above the origin, so the choice only decides whose
     * position {@code Test.get_origin} reports. The inherited {@code testPlayerEye} and {@code
     * getTestOrigin} both go through this answer, so choosing the player here is all a server has
     * to do.
     */
    @Override
    public Object testPlayer() {
        var server = ServerLifecycleHooks.getCurrentServer();
        // The player who started the run, when one has: it is their screen the forwarded calls use.
        Object starter = GrugControlForge.server().starter();
        if (starter != null) {
            ServerPlayer player = server.getPlayerList().getPlayer((UUID) starter);
            if (player != null) return player;
        }
        var players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? null : (ServerPlayer) players.get(0);
    }

    /**
     * Ready once the server's world around the player is loaded, the player is out of any block and
     * the player has landed, the same rule every other dedicated-server loader asks.
     *
     * <p>The inherited answer only proves the chunks arrived, which says nothing about a player
     * still falling when the first test starts, and the test origin is read from the player's
     * position, so that origin would differ between runs (#1052). {@code onGround} alone is not
     * enough either: the server copies it from the client's movement packets, and a client that
     * fell through the terrain while its chunks were arriving reports a position a fraction of a
     * block inside the ground, which the server accepts because the box its collision check tests
     * is contracted by 0.0625. From there the client's own collision never reports support, so the
     * wait timed out with the client in the world and the run already started (#1136, the rule
     * #1095 added to b1.7.3-ornithe). {@link GrugPlayerFloor#mayStart} is that rule, so the
     * server's own ground is enough and a player inside a block is never accepted.
     */
    @Override
    public boolean isWorldReady(Object playerObj) {
        Player player = (Player) playerObj;
        return super.isWorldReady(playerObj)
                && GrugPlayerFloor.mayStart(
                        solidBlocks(getLevel()),
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        player.onGround());
    }

    /**
     * The collision shapes of a level's blocks, as {@link GrugPlayerFloor} reads them: the top of
     * the highest box that covers the player's own point, which is what the game's own collision
     * meets there. A slab's floor is half a block below its block top, a stair's lower step is not
     * its upper one, and a block with no collision box at all, such as a torch or a plant, is not a
     * floor (#1102, #1136).
     *
     * <p>The shape a {@code BlockState} answers is relative to its block position and may hold more
     * than one box, as a stair's two steps do, so each box is filtered by the player's own point
     * rather than the block's whole footprint being taken. The boxes are the game's own collision
     * shapes, so a block with no collision such as water or a plant answers none.
     */
    public static GrugPlayerFloor.BlockSource solidBlocks(Object levelObj) {
        Level level = (Level) levelObj;
        return (x, y, z, pointX, pointZ) -> {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                return GrugPlayerFloor.BlockSource.NO_COLLISION;
            }
            double localX = pointX - x;
            double localZ = pointZ - z;
            double top = GrugPlayerFloor.BlockSource.NO_COLLISION;
            for (AABB box : shape.toAabbs()) {
                if (localX >= box.minX - POINT_EDGE
                        && localX <= box.maxX + POINT_EDGE
                        && localZ >= box.minZ - POINT_EDGE
                        && localZ <= box.maxZ + POINT_EDGE) {
                    top = Math.max(top, box.maxY);
                }
            }
            return top;
        };
    }

    /**
     * Right-clicks a block on this server's own world, so a server-run test's click lands in the
     * world the test reads (#1090).
     *
     * <p>A forwarded click would deadlock: the client's {@code MultiPlayerGameMode} sends a use
     * packet the server thread is blocked inside this call and so cannot process. So the click is
     * made here instead, through the server player's own {@code ServerPlayerGameMode.useItemOn},
     * which is what {@code ServerGamePacketListenerImpl.handleUseItemOn} reaches for a client's use
     * packet: the block's use first, and the held item's use against it when the block declines.
     *
     * <p>The face is the one a ray from the test player's eye to the block's centre enters, which
     * is the face the player sees of it: the top of a block below the eye, the bottom of one above
     * it, and the side facing the player of one at its level. The client path asks {@link
     * GrugUseBlockFace} with the same eye, so both sides click the same face for the same fixture
     * (#1106).
     */
    @Override
    public void useBlockForTest(Object levelObj, double x, double y, double z) {
        Object playerObj = testPlayer();
        if (!(playerObj instanceof ServerPlayer)) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.use_block: There is no player to right-click with.");
            return;
        }
        ServerPlayer player = (ServerPlayer) playerObj;
        ServerLevel level = (ServerLevel) levelObj;
        BlockPos pos = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        Direction face =
                Direction.values()[
                        GrugUseBlockFace.face(
                                testPlayerEye(player), pos.getX(), pos.getY(), pos.getZ())];
        BlockHitResult hit =
                new BlockHitResult(
                        new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5, z + 0.5),
                        face,
                        pos,
                        false);
        player.gameMode.useItemOn(
                player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }

    /**
     * Played by the client of the player who started the run, which owns the sound engine (#1040).
     *
     * <p>The sound registry is in both processes on this version, so {@code hasSound} stays the
     * inherited registry answer and a server needs no client to probe a sound. The engine a sound
     * plays on is the client's, so this call is forwarded like the four other loaders' servers
     * forward it (#1032).
     */
    @Override
    public void playUiSound(String path, double volume, double pitch) {
        try {
            GrugControlForge.server().playUiSound(path, volume, pitch);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "play_ui_sound: " + e.getMessage());
        }
    }

    /**
     * Forwards the capture to the joined client of the player who started the run and waits for the
     * answer with the world frozen (#1040, option A). The request names the eye the test's room was
     * built above, so the client draws from the same place a run it drove would have (#1161). A
     * failure, including a timeout, is a host function error, which fails the test.
     */
    @Override
    public BufferedImage captureScreenshot(double x1, double y1, double x2, double y2) {
        // Arguments first, as on a client, then forward: a bad rectangle never reaches the client.
        String bad = GrugScreenshots.rectangleProblem(x1, y1, x2, y2);
        if (bad != null) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.capture: " + bad);
            return null;
        }
        try {
            return GrugControlForge.server()
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
     * Closes the menu the test player has open, which this server's half of closing the joined
     * client's screen: {@code ServerPlayer.closeContainer} sends the packet that client closes it
     * with. The client is then asked to close its own screen too (#1094), because the screen call
     * is the client's and a client that closed its screen itself (Escape, say) has already sent
     * this server the packet that empties the menu. One client serves the whole run, so a screen a
     * test opened outlives it, and a capture test that follows a GUI test would otherwise take a
     * frame of the panel rather than of the world (#418). The inherited answer reaches for {@code
     * Minecraft.getInstance()}, a client class the server refuses to load, and the runner calls
     * this before the run's first test. See #167.
     */
    @Override
    public void closeOpenScreenForTest() {
        Object playerObj = testPlayer();
        if (playerObj instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) playerObj;
            if (player.containerMenu != player.inventoryMenu) player.closeContainer();
        }
        screens.closeScreen();
    }

    /**
     * Whether the joined client of the player who started the run has a screen open (#1094).
     *
     * <p>This is the client's own screen, which is what a test that captures a panel waits for. It
     * used to answer from this server's container menu, which is the menu that client's screen is
     * built from: that answer turns true when {@code ServerPlayer.openMenu} stores the menu, before
     * the open-screen packet has reached the client, so it did not say the client had drawn
     * anything. The container menu is still this server's half of a click in item 2 of #1094.
     */
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
     * Pressed on the client's screen, by the client of the player who started the run (#1094).
     *
     * <p>The container half of the click is this server's: {@code GUI.open} reaches {@code
     * ServerPlayer.openMenu}, so this server's menu is the one that client's screen shows, and the
     * click packet the client's press sends lands in this server's own slots. It is applied by
     * {@link GrugControlServer#applyClientWork} on a later tick, never inside this call, because
     * the server thread is frozen here and cannot read the packet.
     */
    @Override
    public boolean clickScreen(double x, double y, double button) {
        return screens.clickScreen(x, y, (int) button);
    }

    /** Opened by the client of the player who started the run (#1094). */
    @Override
    public void openInventory(Object playerObj) {
        screens.openInventory();
    }

    /**
     * The tests listed in {@code forge-1.20.6-server-unsupported-tests.txt}, which says why. The
     * name carries the loader because this layer is at the universal jar's root, and a root file of
     * the bare name would shadow the nested layers' own lists on the class path.
     */
    @Override
    public Set<String> serverUnsupportedTests() {
        String name = "/forge-1.20.6-server-unsupported-tests.txt";
        try (InputStream in = ServerForgeAdapter.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is not in the jar.");
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
            throw new IllegalStateException("Could not read " + name + ".", e);
        }
    }

    /**
     * The item on the mouse cursor of this server's own menu, which is the menu the joined client's
     * screen is built from (#1094). The inherited answer reads {@code Minecraft.getInstance()} and
     * the client's screen, which a dedicated server refuses to load.
     *
     * <p>The menu is the one the click is applied to: {@code AbstractContainerMenu.doClick} uses
     * the carried stack this answer reads, so a carried item set here and a click afterwards agree
     * even though the click packet carries the client's own copy of it.
     */
    @Override
    public Object carriedItem() {
        AbstractContainerMenu menu = serverMenu();
        if (menu == null) return null;
        ItemStack carried = menu.getCarried();
        return carried.isEmpty() ? null : carried.getItem();
    }

    /** Sets the cursor of this server's own menu, which a click then applies (#1094). */
    @Override
    public void setCarriedItem(Object itemObj) {
        AbstractContainerMenu menu = serverMenu();
        if (menu == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr, "Test.set_carried_item: There is no player with a menu open.");
            return;
        }
        menu.setCarried(itemObj == null ? ItemStack.EMPTY : new ItemStack((Item) itemObj, 1));
        // The cursor is not a slot the container's own update carries, so the client's screen would
        // keep whatever it had while this server's menu carries the item the click applies. The
        // game syncs it as window -1 slot -1, which is what broadcastChanges sends once the carried
        // differs from the last copy it sent (#1094).
        menu.broadcastChanges();
    }

    /** Clears the cursor of this server's own menu, and the client's copy of it (#1094). */
    @Override
    public void deleteCarriedItem() {
        AbstractContainerMenu menu = serverMenu();
        if (menu == null) {
            Grug.hostFunctionErrorHappened(
                    Grug.statePtr,
                    "OverlayClickContext.delete_carried_item: There is no player with a menu"
                            + " open.");
            return;
        }
        menu.setCarried(ItemStack.EMPTY);
        menu.broadcastChanges();
    }

    /** The menu of this server's test player, or null when no player has joined. */
    @GrugGenerated("the no-player arm: a run's cursor work happens while the player is joined")
    private AbstractContainerMenu serverMenu() {
        Object playerObj = testPlayer();
        return playerObj instanceof ServerPlayer ? ((ServerPlayer) playerObj).containerMenu : null;
    }

    /**
     * A dedicated server has no client copy of any chunk, so no placement owes an update to wait
     * for. The inherited answer reads {@code Minecraft.getInstance().level}, which is null here.
     * See #167.
     */
    @Override
    protected boolean clientHasChunk(int x, int z) {
        return false;
    }

    /**
     * Asks the client of the player who started the run whether it has drawn the server's world,
     * with the world frozen, and answers the same to the test (#1086). A server on its own is
     * settled as soon as a test asks, because no client frame, light copy or block packet is owed
     * to anyone; that is also the answer when no client has started a run. The inherited answer
     * compares the client's copy of the room with the server's, and there is no client here. See
     * #167.
     */
    @Override
    public boolean isWorldSettled() {
        if (!GrugControlForge.server().hasStarter()) {
            return true;
        }
        try {
            return GrugControlForge.server().worldSettled();
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.world_settled: " + e.getMessage());
            return true;
        }
    }

    /**
     * Whether the joined client's copy holds the light this server's world has in the section the
     * position falls in, asked of the client with the world frozen (#1207).
     *
     * <p>The client's light is the copy its frame pass draws, and this server cannot read it, so
     * the answer is the client's own section compared with the server's. A section's bytes rather
     * than one position's light, both so this answers the same thing as a client-only run and
     * because the capture shows the room, not one block: a section whose light has arrived in part
     * is one the next frame can still draw with the older light elsewhere. A server with no starter
     * has no frame to protect and answers true, as {@link #isWorldSettled} does. The inherited
     * answer reads a null client level here. See #167.
     *
     * <p>The server's light is sampled after the settled check and again after the joined client
     * answers, so a light batch that the engine had already scheduled and that publishes during the
     * client round trip makes the samples differ rather than passing the pre-batch light for the
     * room's, on the joined client's answer too, and the engine is asked once more after both. A
     * client answer of {@link GrugClientLight#CANNOT_VERIFY} is true, because an answer that is not
     * the client's fault must not hold a run until its deadline, while {@link
     * GrugClientLight#NOT_RECEIVED} is false, because a section the client was never sent cannot
     * hold the world's light. See {@link GrugLightSync#lightApplied}, #1240 and #1248.
     */
    @Override
    public boolean lightApplied(double x, double y, double z) {
        long simulated = sectionLightFingerprint(getLevel(), x, y, z);
        if (simulated < 0 || !GrugControlForge.server().hasStarter()) {
            return true;
        }
        if (!lightEngineSettled(getLevel())) {
            return false;
        }
        try {
            long client = (long) GrugControlForge.server().clientLightFingerprint(x, y, z);
            long settled = sectionLightFingerprint(getLevel(), x, y, z);
            return GrugLightSync.lightApplied(
                    lightEngineSettled(getLevel()), simulated, settled, client);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Test.light_applied: " + e.getMessage());
            return true;
        }
    }

    /**
     * Never reloading: the client's resource reload is the only thing the flag tracks, and a server
     * has no client resources. The inherited answer stays true once a test touches a watched file,
     * because nothing on a server runs the reload that would lower it, and the runner then waits
     * out its deadline and fails the next test. Found by the whole-suite server run of #167.
     */
    @Override
    public boolean isLoaderReloading() {
        return false;
    }

    /**
     * Drops a give the joined client sent for the test that just ended, so the next test starts
     * with the inventory its reset cleared rather than an item that give added (#1144). The queue
     * lives in core's server, and only this loader holds that server.
     */
    @Override
    public void forgetQueuedGivesForTest() {
        GrugControlForge.server().forgetQueuedGives();
    }
}
