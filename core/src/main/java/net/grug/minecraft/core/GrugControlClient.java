package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;

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
        void capture(
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
        System.out.println("[GRUG CONTROL] this client started a run, and sent the start");
        link.send(GrugControlWire.runStart());
    }

    /**
     * The run is over, or the connection it belonged to is gone: the client stops obeying, so it
     * never serves a later server that did not get its player's start.
     */
    public void endRun() {
        runActive = false;
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
        capturer.capture(
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
     */
    private void perform(GrugControlWire.Message message) {
        System.out.println("[GRUG CONTROL] request " + message.id + ": call " + message.call);
        String problem =
                GrugControlServer.callProblem(
                        message.call, message.text, message.volume, message.pitch);
        if (problem != null) {
            link.send(GrugControlWire.answerError(message.id, "refused: " + problem));
            return;
        }
        try {
            if (message.call == GrugControlWire.CALL_HAS_SOUND) {
                boolean has = caller.hasSound(message.text);
                link.send(GrugControlWire.answerValue(message.id, (byte) (has ? 1 : 0)));
            } else {
                caller.playUiSound(message.text, message.volume, message.pitch);
                link.send(GrugControlWire.answerValue(message.id, (byte) 1));
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
