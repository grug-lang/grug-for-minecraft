package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.Consumer;

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

    private final Link link;
    private final Capturer capturer;
    private final int dropWidth;
    private final int lateWidth;
    private final long lateMillis;
    private volatile boolean runActive = false;

    /**
     * @param dropWidth fault injection for CI: a request whose rectangle is this wide is never
     *     answered, which is how a run makes the server time out. Negative disables it.
     * @param lateWidth fault injection for CI: a request whose rectangle is this wide is answered
     *     {@code lateMillis} later, after the server gave up, which is how a run delivers an answer
     *     to a request that is no longer waiting. Negative disables it.
     */
    public GrugControlClient(
            Link link, Capturer capturer, int dropWidth, int lateWidth, long lateMillis) {
        this.link = link;
        this.capturer = capturer;
        this.dropWidth = dropWidth;
        this.lateWidth = lateWidth;
        this.lateMillis = lateMillis;
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

    public void endRun() {
        runActive = false;
    }

    /** Handles a message from the server. */
    public void onMessage(byte[] bytes) {
        GrugControlWire.Message message;
        try {
            message = GrugControlWire.decode(bytes);
        } catch (IllegalArgumentException e) {
            System.out.println(
                    "[GRUG CONTROL] dropped a server message that does not decode: " + e);
            return;
        }
        if (message.type != GrugControlWire.CAPTURE_REQUEST) {
            System.out.println("[GRUG CONTROL] dropped a server message of type " + message.type);
            return;
        }
        long id = message.id;
        if (!runActive) {
            System.out.println(
                    "[GRUG CONTROL] refused request " + id + ": no run was started by this player");
            link.send(
                    GrugControlWire.answerError(
                            id, "refused: no run was started by this player on this client"));
            return;
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
                        answerLater(id, image);
                    } else {
                        answer(id, image);
                    }
                },
                text -> link.send(GrugControlWire.answerError(id, text)));
    }

    /** The fault injection of a late answer: sleeps off the game's thread, then answers. */
    private void answerLater(long id, BufferedImage image) {
        System.out.println(
                "[GRUG CONTROL] fault injection: request "
                        + id
                        + " is answered "
                        + lateMillis
                        + " ms late");
        Thread thread =
                new Thread(
                        () -> {
                            try {
                                Thread.sleep(lateMillis);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            answer(id, image);
                        },
                        "grug-control-late-answer");
        thread.setDaemon(true);
        thread.start();
    }

    private void answer(long id, BufferedImage image) {
        int count = 0;
        for (byte[] chunk : GrugControlWire.answerChunks(id, encode(image))) {
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
