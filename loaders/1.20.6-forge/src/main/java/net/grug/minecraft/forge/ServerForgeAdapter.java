package net.grug.minecraft.forge;

import net.grug.minecraft.core.GrugControlServer;
import net.grug.minecraft.core.GrugScreenCalls;
import net.grug.minecraft.core.GrugSide;
import net.grug.minecraft.core.GrugUseBlockFace;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugScreenshots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.awt.image.BufferedImage;
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
 * server forwards the play to the player who started the run (#1032).
 *
 * <p>Whole class is excluded from coverage until CI launches a server: see #167.
 */
@GrugGenerated(
        "a dedicated server, which only the dispatch-only multiplayer job of #167 launches, and the"
                + " coverage gate does not count that job")
public class ServerForgeAdapter extends ForgeAdapter {

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
                new BlockHitResult(new Vec3(x + 0.5, y + 0.5, z + 0.5), face, pos, false);
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
     * answer with the world frozen (#1040, option A). A failure, including a timeout, is a host
     * function error, which fails the test.
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
            return GrugControlForge.server().capture((int) x1, (int) y1, (int) x2, (int) y2);
        } catch (GrugControlServer.ControlException e) {
            Grug.hostFunctionErrorHappened(Grug.statePtr, "Screenshot.capture: " + e.getMessage());
            return null;
        }
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
     * Never reloading: the client's resource reload is the only thing the flag tracks, and a server
     * has no client resources. The inherited answer stays true once a test touches a watched file,
     * because nothing on a server runs the reload that would lower it, and the runner then waits
     * out its deadline and fails the next test. Found by the whole-suite server run of #167.
     */
    @Override
    public boolean isLoaderReloading() {
        return false;
    }
}
