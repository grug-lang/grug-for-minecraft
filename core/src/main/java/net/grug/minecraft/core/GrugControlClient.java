package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModFileStates;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

/**
 * The client end of the test-control channel (#1040, option A). It obeys a forwarded call only
 * while a run that its own player started is active: {@link #beginRun} is what the player's start
 * does, and sets the flag before the start message goes out. A server that never received a start
 * from this client, or a run that ended, gets a refusal and nothing is captured, so an ordinary
 * server cannot drive a client that joined it.
 *
 * <p>Taking the capture is the loader's business (it needs the render thread and a settled frame),
 * so it comes in through {@link Capturer}, which answers later through one of two callbacks.
 */
public final class GrugControlClient {
    /** The loader's half: the packet to the server. */
    public interface Link {
        void send(byte[] message);
    }

    /** The loader's capture of a rectangle of the frame, answered later on any thread. */
    public interface Capturer {
        /**
         * Takes the rectangle once this client's own camera is at {@code eye} and a frame has been
         * drawn from there, which is what the loader's tick loop waits for (#1161). A loader that
         * cannot compare its camera against it captures anyway when its wait runs out, so a
         * mismatch leaves a wrong picture and a log line rather than no answer at all.
         */
        void capture(
                Vec3 eye,
                int x1,
                int y1,
                int x2,
                int y2,
                Consumer<BufferedImage> done,
                Consumer<String> failed);
    }

    /** The loader's performing of the client-only calls a server may forward. */
    public interface Caller {
        boolean hasSound(String path);

        void playUiSound(String path, double volume, double pitch);

        /**
         * Earns every achievement for this client's player and drops the toast on screen, before a
         * capture is taken. A joined client draws its own "Taking Inventory" hint and toast, and
         * the runner's per-test marking runs on the dedicated server, which has no such client
         * state, so without this every capture a dedicated run takes can show a toast in its
         * top-right corner (#1576).
         */
        default void markAchievementsEarned() {}

        /**
         * Whether this client has drawn the world it has, as {@code TestTickContext.world_settled}
         * asks.
         */
        boolean isWorldSettled();

        /**
         * Why {@link #isWorldSettled} answered false, named for the report a wait makes when the
         * same answer repeats at one revision (#1695). Empty when nothing is busy or the loader
         * cannot say, which the report reads as unnamed.
         */
        default String settleReason() {
            return "";
        }

        /**
         * Whether this client's own copy of the world holds the block ids the server's checksum
         * describes in the boxes (#1664).
         */
        boolean holdsBlocks(GrugBlockRegion.Expected expected);

        /**
         * Whether this client's own copy of the world holds the light the server describes in the
         * box, on both its block and sky fields (#1695). A null expectation is held.
         */
        boolean holdsRoomLight(GrugLightAsk.Expected expected);

        /** Forgets the frames the settle answer counts, so it waits for frames drawn from now. */
        void forgetSettledFrames();

        /**
         * The identity of the screen this client has open, and 0 when it has none, as a forwarded
         * {@code TestTickContext.screen_open} asks (#1094, #1289). A screen replaced by another one
         * while the panel stayed open answers a different identity, so a server that remembered the
         * earlier screen's measurements drops them.
         */
        long screenIdentity();

        /**
         * One of this client's screen measurements, named as {@link GrugScreenCalls} names them.
         */
        double screenRead(String read);

        /**
         * The measurements of this client's screen that one {@link
         * GrugControlWire#CALL_SCREEN_GEOMETRY} answers, read through {@link #screenRead} so a
         * batch and a single read make the same adapter reads and cannot disagree (#1277).
         */
        default double[] screenGeometry() {
            return GrugScreenCalls.geometry(this::screenRead);
        }

        /** Presses a key on the screen that is open, and answers whether an overlay took it. */
        boolean pressScreenKey(int key, boolean ctrl, boolean release);

        /** Turns the wheel at a screen position, and answers whether an overlay took it. */
        boolean scrollScreen(double x, double y, double delta);

        /** Closes whatever screen is open. */
        void closeScreen();

        /**
         * One component of this client's own render camera, which is the eye of its test player: 0
         * for x, 1 for y and 2 for z (#1161).
         */
        double camera(int axis);

        /** Presses a mouse button on the open screen, and answers whether an overlay took it. */
        boolean clickScreen(double x, double y, int button);

        /**
         * Opens the player's own inventory screen, as {@code TestTickContext.open_inventory} asks.
         */
        void openInventory();

        /**
         * The number saved under {@code name} by this client's own copy of the block entity at the
         * position, 0 when it holds none or the number was never saved there (#1162). This is the
         * copy a frame pass reads, which is not the one a dedicated server simulates.
         */
        double savedNumber(double x, double y, double z, String name);

        /**
         * The fingerprint of the block light of the section the position falls in, in this client's
         * own copy of the world, which is the copy its mesher bakes the room from (#1207).
         */
        long clientLightFingerprint(double x, double y, double z);

        /**
         * Whether this client draws overlays on the inventory and container screens at all, which a
         * server-run {@code Test.overlay_supported} asks the client that draws them (#1099).
         */
        boolean overlaySupported();

        /**
         * Whether a frame has drawn the overlay of the mod named {@code mod} on the screen this
         * client has open now, which is a server-run {@code TestTickContext.overlay_drawn} (#1099).
         * The mod is the one that owns the running test, which only the server knows.
         */
        boolean overlayDrawn(String mod);

        /**
         * Whether this client asks the overlays' world key, HUD and world exports at all, as a
         * server-run {@code Test.world_overlay_supported} asks (#1040).
         */
        boolean worldOverlaySupported();

        /**
         * Whether a frame has drawn the world overlay of the mod named {@code mod} since the last
         * press, as a server-run {@code TestTickContext.world_overlay_drawn} asks (#1040). The mod
         * is the one that owns the running test, which only the server knows.
         */
        boolean worldOverlayDrawn(String mod);

        /**
         * Presses one letter or digit key in this client's world, as a server-run {@code
         * TestTickContext.press_world_key} asks, counting the frame it causes for the mod that owns
         * the running test (#1040).
         */
        void pressWorldKey(String mod, int key, boolean ctrl);

        /**
         * Whether this client's drawn mark was already clear when the first frame of the screen it
         * has open now began, which is a server-run {@code
         * TestTickContext.overlay_first_frame_cleared} (#1391). The mod is the one that owns the
         * running test, whose overlay sets the mark, which only the server knows.
         */
        boolean overlayFirstFrameCleared(String mod);

        /**
         * Whether this client draws the mining crack over the geometry of the block at the position
         * in its own copy of the world, as a server-run {@code TestTickContext.mining_crack} asks
         * (#1040).
         */
        boolean miningCrack(double x, double y, double z);

        /**
         * Puts the mod file under {@code path} of this client's own mods directory into the state
         * at {@code stateIndex} of {@code GrugModFileStates}, as a forwarded {@code
         * TestTickContext.set_mod_file_state} asks (#1040).
         */
        void modFileState(String path, int stateIndex);

        /**
         * How many changes to {@code path} this client's engine has reported, or when {@code
         * loaderReloads} is set how many reloads its loader has finished for it (#1040).
         */
        double reloadCount(String path, boolean loaderReloads);

        /**
         * One of the normalization questions of {@code GrugControlWire.NORMALIZATION_*}, answered
         * by this client's own GL state, as the runner of a server asks while it runs a test whose
         * file name ends with {@code _forced_normalization} (#1040).
         */
        boolean normalization(int operation);

        /**
         * Whether the box this client's game targets and outlines for the block at the position
         * contains the block-local point, which is a server-run {@code
         * TestTickContext.block_selection} (#1441). The box belongs to the client's outline, so a
         * dedicated server has none to ask.
         */
        boolean blockSelectionContains(
                double x, double y, double z, double pointX, double pointY, double pointZ);
    }

    /** The calls performed by the game's adapter, looked up when a call arrives. */
    public static Caller adapterCaller(Supplier<ModLoaderAdapter> adapter) {
        // What the capabilities were at the moment the force finished, which is what the server's
        // separate question has to be answered from: a frame drawn between the two requests can
        // reset GL state the force set, and the question is whether the force took, not what a
        // later frame left (#1144).
        AtomicBoolean enabledRightAfterForce = new AtomicBoolean();
        return new Caller() {
            @Override
            public boolean hasSound(String path) {
                return adapter.get().hasSound(path);
            }

            @Override
            public void playUiSound(String path, double volume, double pitch) {
                adapter.get().playUiSound(path, volume, pitch);
            }

            @Override
            public void markAchievementsEarned() {
                adapter.get().markAchievementsEarnedForTest();
            }

            @Override
            public boolean isWorldSettled() {
                return adapter.get().isWorldSettled();
            }

            @Override
            public String settleReason() {
                return adapter.get().settleReason();
            }

            @Override
            public boolean holdsBlocks(GrugBlockRegion.Expected expected) {
                return adapter.get().holdsBlockRegion(expected);
            }

            @Override
            public boolean holdsRoomLight(GrugLightAsk.Expected expected) {
                return adapter.get().holdsRoomLight(expected);
            }

            @Override
            public void forgetSettledFrames() {
                adapter.get().forgetSettledFrames();
            }

            @Override
            public long screenIdentity() {
                return GrugScreenCalls.screenIdentity(adapter.get());
            }

            @Override
            public double screenRead(String read) {
                return GrugScreenCalls.read(adapter.get(), read);
            }

            @Override
            public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
                return adapter.get().pressScreenKey(key, ctrl, release);
            }

            @Override
            public boolean scrollScreen(double x, double y, double delta) {
                return adapter.get().scrollScreen(x, y, delta);
            }

            @Override
            public void closeScreen() {
                GrugScreenCalls.closeScreen(adapter.get());
            }

            @Override
            public double camera(int axis) {
                return GrugTestCamera.axis(adapter.get().testCamera(), axis);
            }

            @Override
            public boolean clickScreen(double x, double y, int button) {
                return adapter.get().clickScreen(x, y, button);
            }

            /**
             * Opens this client's own inventory. The server's call names the player it is about,
             * but this client is that player's, so the screen is this client's own player's.
             */
            @Override
            public void openInventory() {
                adapter.get().openInventory(adapter.get().testPlayer());
            }

            @Override
            public double savedNumber(double x, double y, double z, String name) {
                return adapter.get().savedNumberOnClient(x, y, z, name);
            }

            @Override
            public long clientLightFingerprint(double x, double y, double z) {
                return adapter.get().clientLightFingerprint(x, y, z);
            }

            @Override
            public boolean overlaySupported() {
                return adapter.get().overlaySupportedForTest();
            }

            @Override
            public boolean overlayDrawn(String mod) {
                return adapter.get().overlayDrawnForTest(mod);
            }

            @Override
            public boolean worldOverlaySupported() {
                return adapter.get().worldOverlaySupportedForTest();
            }

            /** The mark is this client's, for the mod the server says owns the running test. */
            @Override
            public boolean worldOverlayDrawn(String mod) {
                GrugTestState.currentTestMod = mod;
                return adapter.get().worldOverlayDrawnForTest();
            }

            @Override
            public void pressWorldKey(String mod, int key, boolean ctrl) {
                GrugTestState.currentTestMod = mod;
                adapter.get().pressWorldKey(key, ctrl);
            }

            /** The record is this client's own, taken when the screen's first frame began. */
            @Override
            public boolean overlayFirstFrameCleared(String mod) {
                return adapter.get().overlayFirstFrameClearedForTest(mod);
            }

            /** The block is in this client's own copy of the world, which is not the server's. */
            @Override
            public boolean miningCrack(double x, double y, double z) {
                return adapter.get().miningCrackForTest(adapter.get().clientLevel(), x, y, z);
            }

            /**
             * The selection box a block is outlined by belongs to this client's own copy of the
             * world, and to the block class this client's game has, which is what a 1.2.5 dedicated
             * server has none of (#1441).
             */
            @Override
            public boolean blockSelectionContains(
                    double x, double y, double z, double pointX, double pointY, double pointZ) {
                return adapter.get()
                        .blockSelectionContains(
                                adapter.get().clientLevel(), x, y, z, pointX, pointY, pointZ);
            }

            @Override
            public boolean normalization(int operation) {
                ModLoaderAdapter game = adapter.get();
                switch (operation) {
                    case GrugControlWire.NORMALIZATION_CAN_FORCE:
                        return game.canForceNormalizationForTest();
                    case GrugControlWire.NORMALIZATION_FORCE:
                        boolean forced = game.forceNormalizationForTest();
                        enabledRightAfterForce.set(game.normalizationEnabledForTest());
                        return forced;
                    case GrugControlWire.NORMALIZATION_RESTORE:
                        return game.restoreNormalizationAfterTest();
                    default:
                        return enabledRightAfterForce.get();
                }
            }

            @Override
            public void modFileState(String path, int stateIndex) {
                GrugModFileStates.setUnchecked(
                        adapter.get().getGrugModsDirectory(),
                        path,
                        GrugModFileStates.STATES[stateIndex]);
            }

            @Override
            public double reloadCount(String path, boolean loaderReloads) {
                String problem = GrugModFileStates.clientPathProblem(path);
                if (problem != null) {
                    throw new IllegalArgumentException(problem);
                }
                return loaderReloads
                        ? GrugModsWatcher.loaderReloadCount(path)
                        : GrugModsWatcher.reportedChangeCount(path);
            }
        };
    }

    private final Link link;
    private final Capturer capturer;
    private final Caller caller;
    private final int dropWidth;
    private final int lateWidth;
    private final AtomicReference<Runnable> held = new AtomicReference<>();
    private final ConcurrentLinkedQueue<byte[]> pendingMessages = new ConcurrentLinkedQueue<>();
    private volatile boolean runActive = false;

    /**
     * The control client of this process, or null when this process is not one: a dedicated server
     * never makes one, and a client makes one when it joins a server or answers one (#1099).
     *
     * <p>It is static because a give a host function performs has to know whether the player it
     * must reach is the server's, and the host functions are core code with no client of their own
     * to ask. One client serves a process's run, so this is the only one there is.
     */
    private static volatile GrugControlClient current;

    /**
     * The world revision this client last answered a settle ask for (#1086). A revision it has not
     * answered is never settled, so a test that changed the world since the last answer is told the
     * client has not drawn the change yet.
     */
    private long seenRevision = Long.MIN_VALUE;

    /** The blocks the server's test placed that this client has not yet been seen to hold. */
    private GrugBlockRegion.Expected owedRegion;

    /**
     * The light of the box a dedicated server last sent this client that it has not yet been seen
     * to hold, or null when the ask named none (#1695).
     */
    private GrugLightAsk.Expected owedLight;

    /**
     * How many not-settled answers this client has given at one revision with one reason, so a wait
     * that cannot end reports instead of spending the test's tick budget (#1695).
     */
    private final GrugSettleLoop settleLoop = new GrugSettleLoop();

    /**
     * The revision whose not-settled reason this client last wrote to the log, once per revision.
     */
    private long loggedUnsettledRevision = Long.MIN_VALUE;

    /**
     * @param dropWidth fault injection for CI: a request whose rectangle is this wide is never
     *     answered, which is how a run makes the server time out. Negative disables it.
     * @param lateWidth fault injection for CI: the answer to a request whose rectangle is this wide
     *     is held back and sent just before the client handles the next request, which is after the
     *     server gave up on the first and while it waits for the second, so a run delivers a stale
     *     answer at the moment it could do harm. Negative disables it.
     */
    public GrugControlClient(
            Link link, Capturer capturer, Caller caller, int dropWidth, int lateWidth) {
        this.link = link;
        this.capturer = capturer;
        this.caller = caller;
        this.dropWidth = dropWidth;
        this.lateWidth = lateWidth;
        current = this;
    }

    public boolean runActive() {
        return runActive;
    }

    /**
     * Whether this process is a client that has started a run, so a host function that changes the
     * player has to reach the server that holds the player rather than its own copy (#1099).
     */
    public static boolean runActiveHere() {
        GrugControlClient client = current;
        return client != null && client.runActive;
    }

    /**
     * Sends a give of {@code count} of the item named {@code item} to the server that drives this
     * client's run (#1099), which gives the item its own list names that way to its own copy of the
     * test player. Only legal while {@link #runActiveHere}, which its caller checks.
     */
    public static void giveToServer(String item, int count) {
        GrugControlClient client = current;
        if (!runActiveHere()) {
            throw new IllegalStateException(
                    "a give named an item to the server with no run started on this client");
        }
        client.link.send(GrugControlWire.giveRequest(item, count));
    }

    /**
     * Asks the server that drives this client's run to clear the cursor of its own copy of the test
     * player's menu (#1389): the overlay's delete clears the client's mirror, and a test reads the
     * server's. Only legal while {@link #runActiveHere}, which its caller checks.
     */
    public static void deleteCarriedOnServer() {
        GrugControlClient client = current;
        if (!runActiveHere()) {
            throw new IllegalStateException(
                    "a delete named the server's cursor with no run started on this client");
        }
        client.link.send(GrugControlWire.deleteCarriedRequest());
    }

    /** The player started a run: the flag first, then the message that tells the server. */
    public void beginRun() {
        runActive = true;
        seenRevision = Long.MIN_VALUE;
        owedRegion = null;
        owedLight = null;
        settleLoop.forget();
        loggedUnsettledRevision = Long.MIN_VALUE;
        System.out.println("[GRUG CONTROL] this client started a run, and sent the start");
        link.send(GrugControlWire.runStart());
    }

    /**
     * The run is over, or the connection it belonged to is gone: the client stops obeying, so it
     * never serves a later server that did not get its player's start.
     */
    public void endRun() {
        runActive = false;
        seenRevision = Long.MIN_VALUE;
        owedRegion = null;
        owedLight = null;
        settleLoop.forget();
        loggedUnsettledRevision = Long.MIN_VALUE;
        held.set(null);
    }

    /**
     * Queues a message to be handled on the game thread by {@link #drainPendingMessages}, so a
     * loader whose packet reader is not the game thread can still process server requests on the
     * thread that owns the GL context and the frame (#1465).
     *
     * <p>When this process is not a client there is no frame to defer to, so the message is handled
     * immediately through {@code immediate} (a server's packet handler, for example). A single call
     * per packet prevents a loader from choosing the wrong branch.
     */
    public static void handleOrQueue(byte[] bytes, Runnable immediate) {
        GrugControlClient client = current;
        if (client != null) {
            client.pendingMessages.add(bytes);
        } else {
            immediate.run();
        }
    }

    /**
     * Queues a message to be handled on the game thread by {@link #drainPendingMessages}.
     *
     * <p>This is a convenience for callers that already know they are on a client and have no
     * server-side work to do.
     */
    public static void queueMessage(byte[] bytes) {
        GrugControlClient client = current;
        if (client != null) {
            client.pendingMessages.add(bytes);
        }
    }

    /**
     * Drains messages queued by {@link #queueMessage} and handles them. A loader calls this from
     * its game tick so requests that need the frame (such as the forced-normalization call) run
     * before the frame they affect.
     */
    public static void drainPendingMessages() {
        GrugControlClient client = current;
        if (client == null) return;
        byte[] bytes;
        while ((bytes = client.pendingMessages.poll()) != null) {
            client.onMessage(bytes);
        }
    }

    /**
     * Handles a message from the server. Anything that is not a well-formed request or the end of
     * the run is ignored: this client has no call to fail, and the server's wait for the answer
     * that never comes is the error the test sees.
     */
    public void onMessage(byte[] bytes) {
        GrugControlWire.Message message;
        try {
            message = GrugControlWire.decode(bytes);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (message.type == GrugControlWire.RUN_END) {
            endRun();
            return;
        }
        if (message.type != GrugControlWire.CAPTURE_REQUEST
                && message.type != GrugControlWire.CALL_REQUEST) {
            return;
        }
        long id = message.id;
        if (!runActive) {
            link.send(
                    GrugControlWire.answerError(
                            id, "refused: no run was started by this player on this client"));
            return;
        }
        if (message.type == GrugControlWire.CALL_REQUEST) {
            perform(message);
            return;
        }
        Runnable stale = held.getAndSet(null);
        if (stale != null) {
            stale.run();
        }
        if (message.x2 - message.x1 == dropWidth) {
            System.out.println(
                    "[GRUG CONTROL] fault injection: request " + id + " is never answered");
            return;
        }
        System.out.println("[GRUG CONTROL] request " + id + " received, capturing");
        boolean late = message.x2 - message.x1 == lateWidth;
        // A capture request names the eye the test's room was built above, and the loader's wait
        // holds the capture until this client's own camera is there (#1161).
        Vec3 eye = new Vec3(message.volume, message.pitch, message.third);
        caller.markAchievementsEarned();
        capturer.capture(
                eye,
                message.x1,
                message.y1,
                message.x2,
                message.y2,
                image -> {
                    if (late) {
                        System.out.println(
                                "[GRUG CONTROL] fault injection: the answer to request "
                                        + id
                                        + " is held until the next request");
                        held.set(() -> answer(id, image));
                    } else {
                        answer(id, image);
                    }
                },
                text -> link.send(GrugControlWire.answerError(id, text)));
    }

    /**
     * Performs a call on this thread, which the loader delivers on the game thread. An argument the
     * host function would refuse, or an adapter that throws, is answered as an error, so the server
     * reports a real cause instead of waiting out its timeout.
     *
     * <p>Package-private so a unit test can drive a call code the wire does not yet accept, which
     * is the only way to reach the unknown-call arm.
     */
    void perform(GrugControlWire.Message message) {
        System.out.println("[GRUG CONTROL] request " + message.id + ": call " + message.call);
        if (message.call == GrugControlWire.CALL_WORLD_SETTLED) {
            worldSettled(message);
            return;
        }
        String problem =
                GrugControlServer.callProblem(
                        message.call,
                        message.text,
                        message.volume,
                        message.pitch,
                        message.third,
                        message.fourth,
                        message.fifth,
                        message.sixth);
        if (problem != null) {
            link.send(GrugControlWire.answerError(message.id, "refused: " + problem));
            return;
        }
        try {
            if (message.call == GrugControlWire.CALL_SCREEN_GEOMETRY) {
                link.send(GrugControlWire.answerNumbers(message.id, caller.screenGeometry()));
            } else if (message.call == GrugControlWire.CALL_SCREEN_READ) {
                link.send(
                        GrugControlWire.answerNumber(message.id, caller.screenRead(message.text)));
            } else if (message.call == GrugControlWire.CALL_HAS_SOUND) {
                boolean has = caller.hasSound(message.text);
                link.send(GrugControlWire.answerValue(message.id, (byte) (has ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_PLAY_UI_SOUND) {
                caller.playUiSound(message.text, message.volume, message.pitch);
                link.send(GrugControlWire.answerValue(message.id, (byte) 1));
            } else if (message.call == GrugControlWire.CALL_SCREEN_OPEN) {
                link.send(GrugControlWire.answerNumber(message.id, caller.screenIdentity()));
            } else if (message.call == GrugControlWire.CALL_PRESS_SCREEN_KEY) {
                boolean taken =
                        caller.pressScreenKey(
                                (int) message.volume, message.pitch != 0, message.third != 0);
                link.send(GrugControlWire.answerValue(message.id, (byte) (taken ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_SCROLL_SCREEN) {
                boolean taken = caller.scrollScreen(message.volume, message.pitch, message.third);
                link.send(GrugControlWire.answerValue(message.id, (byte) (taken ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_CLOSE_SCREEN) {
                caller.closeScreen();
                link.send(GrugControlWire.answerValue(message.id, (byte) 1));
            } else if (message.call == GrugControlWire.CALL_CAMERA) {
                link.send(
                        GrugControlWire.answerNumber(
                                message.id, caller.camera((int) message.volume)));
            } else if (message.call == GrugControlWire.CALL_CLICK_SCREEN) {
                boolean taken =
                        caller.clickScreen(message.volume, message.pitch, (int) message.third);
                // The press has written the game's own click packet on this client's connection,
                // so this barrier follows it on the same stream and the server holds its next
                // runner tick until the barrier's own queue has handled both (#1393). It goes
                // before the answer for the same reason: the answer completes the call on the
                // server's reader thread, and only the barrier says the game thread applied the
                // click.
                link.send(GrugControlWire.clickWritten());
                link.send(GrugControlWire.answerValue(message.id, (byte) (taken ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_OPEN_INVENTORY) {
                caller.openInventory();
                link.send(GrugControlWire.answerValue(message.id, (byte) 1));
            } else if (message.call == GrugControlWire.CALL_SAVED_NUMBER) {
                link.send(
                        GrugControlWire.answerNumber(
                                message.id,
                                caller.savedNumber(
                                        message.volume,
                                        message.pitch,
                                        message.third,
                                        message.text)));
            } else if (message.call == GrugControlWire.CALL_CLIENT_LIGHT) {
                link.send(
                        GrugControlWire.answerNumber(
                                message.id,
                                (double)
                                        caller.clientLightFingerprint(
                                                message.volume, message.pitch, message.third)));
            } else if (message.call == GrugControlWire.CALL_OVERLAY_SUPPORTED) {
                boolean supported = caller.overlaySupported();
                link.send(GrugControlWire.answerValue(message.id, (byte) (supported ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_OVERLAY_DRAWN) {
                boolean drawn = caller.overlayDrawn(message.text);
                link.send(GrugControlWire.answerValue(message.id, (byte) (drawn ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_OVERLAY_FIRST_FRAME) {
                boolean cleared = caller.overlayFirstFrameCleared(message.text);
                link.send(GrugControlWire.answerValue(message.id, (byte) (cleared ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_WORLD_OVERLAY) {
                link.send(GrugControlWire.answerValue(message.id, worldOverlay(message)));
            } else if (message.call == GrugControlWire.CALL_MINING_CRACK) {
                boolean cracked = caller.miningCrack(message.volume, message.pitch, message.third);
                link.send(GrugControlWire.answerValue(message.id, (byte) (cracked ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_BLOCK_SELECTION) {
                boolean contains =
                        caller.blockSelectionContains(
                                message.volume,
                                message.pitch,
                                message.third,
                                message.fourth,
                                message.fifth,
                                message.sixth);
                link.send(GrugControlWire.answerValue(message.id, (byte) (contains ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_MOD_FILE_STATE) {
                caller.modFileState(message.text, (int) message.volume);
                link.send(GrugControlWire.answerValue(message.id, (byte) 1));
            } else if (message.call == GrugControlWire.CALL_NORMALIZATION) {
                boolean answer = caller.normalization((int) message.volume);
                link.send(GrugControlWire.answerValue(message.id, (byte) (answer ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_RELOAD_COUNT) {
                link.send(
                        GrugControlWire.answerNumber(
                                message.id, caller.reloadCount(message.text, message.volume == 1)));
            } else {
                // A call this client has no arm for, which is what a call code added to the wire
                // before its arm becomes. Answering it as an error keeps the server's mistake
                // visible instead of performing another call's effect.
                link.send(GrugControlWire.answerError(message.id, "unknown call " + message.call));
            }
        } catch (RuntimeException e) {
            link.send(
                    GrugControlWire.answerError(
                            message.id,
                            "the client's call failed: "
                                    + String.valueOf(e)
                                            .substring(
                                                    0,
                                                    Math.min(String.valueOf(e).length(), 1000))));
        }
    }

    /** The 1 or 0 a world overlay call answers: a press always answers 1. */
    private byte worldOverlay(GrugControlWire.Message message) {
        int operation = (int) message.volume;
        if (operation == GrugControlWire.WORLD_OVERLAY_SUPPORTED) {
            return (byte) (caller.worldOverlaySupported() ? 1 : 0);
        }
        if (operation == GrugControlWire.WORLD_OVERLAY_DRAWN) {
            return (byte) (caller.worldOverlayDrawn(message.text) ? 1 : 0);
        }
        caller.pressWorldKey(message.text, (int) message.pitch, message.third != 0);
        return 1;
    }

    /**
     * Answers whether this client has drawn the world the server's revision describes (#1086).
     *
     * <p>The ask is what makes the client draw, as a settle wait on a singleplayer client does, so
     * a test that never captures still gets the frames its wait is about. A revision this client
     * has not answered is never settled: the ask is ordered after the block changes the server
     * flushed before it, and forgetting the frames counted so far makes the answer wait for frames
     * drawn after those changes arrived. A revision it has already answered is asked of the
     * adapter, so the wait ends once its light and its frames are idle.
     *
     * <p>A client that keeps answering not settled at one revision with one reason is a wait that
     * cannot end, because the server is frozen while it asks, so it reports after {@link
     * GrugSettleLoop#LIMIT} asks instead of spending the test's tick budget (#1695).
     */
    private void worldSettled(GrugControlWire.Message message) {
        GrugFrameRequest.request();
        // The revision travels in the volume slot; see GrugControlWire.worldSettledRequest.
        long revision = (long) message.volume;
        // Each ask restates what this client has to hold as the server sees it now, so a block the
        // game changed after the test placed it does not leave the client waiting for the old
        // checksum, and a box the server sent again is compared against the light it now holds.
        // Every ask replaces it, an empty one included: the server restates the blocks and the
        // light until the client answers settled, so an expectation a later test did not repeat is
        // not kept.
        owedLight = GrugLightAsk.decode(message.text);
        owedRegion = owedLight == null ? GrugBlockRegion.decode(message.text) : null;
        if (revision != seenRevision) {
            seenRevision = revision;
            caller.forgetSettledFrames();
            link.send(GrugControlWire.answerValue(message.id, GrugControlWire.SETTLE_NO));
            return;
        }
        boolean holds = caller.holdsBlocks(owedRegion);
        if (holds) {
            owedRegion = null;
        }
        boolean settled = caller.isWorldSettled() && holds;
        if (!settled) {
            // The client says what it is still busy with, so a wait that repeats one answer at one
            // world revision reports instead of asking until its tick budget is gone (#1695).
            String reason =
                    holds
                            ? caller.settleReason()
                            : "the blocks a test placed have not all reached this client";
            String stuck = settleLoop.answered(revision, reason);
            if (revision != loggedUnsettledRevision) {
                loggedUnsettledRevision = revision;
                System.out.println(
                        "[GRUG CONTROL] world revision "
                                + revision
                                + " is not settled: "
                                + settleLoop.named());
            }
            if (stuck != null) {
                link.send(GrugControlWire.answerError(message.id, stuck));
                return;
            }
        }
        // After the settle answer, so the light this reads is the one the frame the answer is
        // about was drawn from: the loader's own answer drains its light queue first (#1695).
        boolean light = !settled || caller.holdsRoomLight(owedLight);
        if (settled && light) {
            owedLight = null;
        }
        link.send(
                GrugControlWire.answerValue(
                        message.id,
                        !settled
                                ? GrugControlWire.SETTLE_NO
                                : light
                                        ? GrugControlWire.SETTLE_YES
                                        : GrugControlWire.SETTLE_LIGHT_DIVERGED));
    }

    private void answer(long id, BufferedImage image) {
        byte[] png = encode(image);
        if (png.length > GrugControlWire.MAX_PNG_BYTES) {
            link.send(
                    GrugControlWire.answerError(
                            id,
                            "the capture is "
                                    + png.length
                                    + " bytes of PNG, over the "
                                    + GrugControlWire.MAX_PNG_BYTES
                                    + " a control answer may hold"));
            return;
        }
        int count = 0;
        for (byte[] chunk : GrugControlWire.answerChunks(id, png)) {
            link.send(chunk);
            count++;
        }
        System.out.println(
                "[GRUG CONTROL] request "
                        + id
                        + " answered with a "
                        + image.getWidth()
                        + "x"
                        + image.getHeight()
                        + " capture in "
                        + count
                        + " packet(s)");
    }

    @GrugGenerated("ImageIO has a PNG writer, and writing to a byte array cannot fail")
    private static byte[] encode(BufferedImage image) {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", png);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return png.toByteArray();
    }
}
