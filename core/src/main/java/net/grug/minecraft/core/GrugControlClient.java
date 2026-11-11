package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.Vec3;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

        /** Whether this client has drawn the world it has, as {@code Test.world_settled} asks. */
        boolean isWorldSettled();

        /** Forgets the frames the settle answer counts, so it waits for frames drawn from now. */
        void forgetSettledFrames();

        /** Whether a screen is open on this client, as {@code Test.screen_open} asks (#1094). */
        boolean screenOpen();

        /**
         * One of this client's screen measurements, named as {@link GrugScreenCalls} names them.
         */
        double screenRead(String read);

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

        /** Opens the player's own inventory screen, as {@code Test.open_inventory} asks. */
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
    }

    /** The calls performed by the game's adapter, looked up when a call arrives. */
    public static Caller adapterCaller(Supplier<ModLoaderAdapter> adapter) {
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
            public boolean isWorldSettled() {
                return adapter.get().isWorldSettled();
            }

            @Override
            public void forgetSettledFrames() {
                adapter.get().forgetSettledFrames();
            }

            @Override
            public boolean screenOpen() {
                return GrugScreenCalls.screenOpen(adapter.get());
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
        };
    }

    private final Link link;
    private final Capturer capturer;
    private final Caller caller;
    private final int dropWidth;
    private final int lateWidth;
    private final AtomicReference<Runnable> held = new AtomicReference<>();
    private volatile boolean runActive = false;

    /**
     * The world revision this client last answered a settle ask for (#1086). A revision it has not
     * answered is never settled, so a test that changed the world since the last answer is told the
     * client has not drawn the change yet.
     */
    private long seenRevision = Long.MIN_VALUE;

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
    }

    public boolean runActive() {
        return runActive;
    }

    /** The player started a run: the flag first, then the message that tells the server. */
    public void beginRun() {
        runActive = true;
        seenRevision = Long.MIN_VALUE;
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
        held.set(null);
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
                        message.call, message.text, message.volume, message.pitch, message.third);
        if (problem != null) {
            link.send(GrugControlWire.answerError(message.id, "refused: " + problem));
            return;
        }
        try {
            if (message.call == GrugControlWire.CALL_SCREEN_READ) {
                link.send(
                        GrugControlWire.answerNumber(message.id, caller.screenRead(message.text)));
            } else if (message.call == GrugControlWire.CALL_HAS_SOUND) {
                boolean has = caller.hasSound(message.text);
                link.send(GrugControlWire.answerValue(message.id, (byte) (has ? 1 : 0)));
            } else if (message.call == GrugControlWire.CALL_PLAY_UI_SOUND) {
                caller.playUiSound(message.text, message.volume, message.pitch);
                link.send(GrugControlWire.answerValue(message.id, (byte) 1));
            } else if (message.call == GrugControlWire.CALL_SCREEN_OPEN) {
                boolean open = caller.screenOpen();
                link.send(GrugControlWire.answerValue(message.id, (byte) (open ? 1 : 0)));
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

    /**
     * Answers whether this client has drawn the world the server's revision describes (#1086).
     *
     * <p>The ask is what makes the client draw, as a settle wait on a singleplayer client does, so
     * a test that never captures still gets the frames its wait is about. A revision this client
     * has not answered is never settled: the ask is ordered after the block changes the server
     * flushed before it, and forgetting the frames counted so far makes the answer wait for frames
     * drawn after those changes arrived. A revision it has already answered is asked of the
     * adapter, so the wait ends once its light and its frames are idle.
     */
    private void worldSettled(GrugControlWire.Message message) {
        GrugFrameRequest.request();
        // The revision travels in the volume slot; see GrugControlWire.worldSettledRequest.
        long revision = (long) message.volume;
        if (revision != seenRevision) {
            seenRevision = revision;
            caller.forgetSettledFrames();
            link.send(GrugControlWire.answerValue(message.id, (byte) 0));
            return;
        }
        boolean settled = caller.isWorldSettled();
        link.send(GrugControlWire.answerValue(message.id, (byte) (settled ? 1 : 0)));
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
