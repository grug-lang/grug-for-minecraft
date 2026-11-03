package net.grug.minecraft.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

/**
 * The server end of the test-control channel (#1040, option A): a client-only host function that a
 * server-run test calls is forwarded to the client of the player who started the run, and this
 * class waits for the answer.
 *
 * <p>The wait is the freeze. {@link #capture} blocks the thread that called it, which is the server
 * thread inside a test's tick, so no world ticks for any player until the client answers or the
 * timeout passes. The answer arrives on the network thread through {@link #onMessage}, which only
 * completes a future, so nothing needs the blocked server thread to be delivered, and the wait does
 * not run the server's queued tasks (a nested drain would run other players' packets in the middle
 * of a test's tick). A timeout is an error the host function reports, which fails the test with the
 * usual {@code [GRUG CI] FAIL} line.
 *
 * <p>Only the player who sent {@link GrugControlWire#RUN_START} may answer: any other sender's
 * message is dropped and logged. That is the server's half of the consent rule; the client's half
 * is in {@link GrugControlClient}.
 */
public final class GrugControlServer {
    /** The loader's half: how to reach the starting player, and how to make the world current. */
    public interface Link {
        /** Sends to the player who started the run; false when they are no longer connected. */
        boolean sendToStarter(Object starter, byte[] message);

        /**
         * Sends the world changes the server owes its clients. A server only does that at the end
         * of its own tick, which a frozen tick never reaches, so without this a test's block
         * placements would still be queued when the client captures.
         */
        void flushWorld();
    }

    /** A request that cannot be answered; its message is what the test's error says. */
    public static final class ControlException extends Exception {
        private static final long serialVersionUID = 1L;

        public ControlException(String message) {
            super(message);
        }
    }

    private static final class Pending {
        final CompletableFuture<byte[]> png = new CompletableFuture<>();
        byte[][] chunks = null;
        int received = 0;
    }

    private final Link link;
    private final long timeoutMillis;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private volatile Object starter = null;

    public GrugControlServer(Link link, long timeoutMillis) {
        this.link = link;
        this.timeoutMillis = timeoutMillis;
    }

    /** The player who started the run, or null when none has. */
    public Object starter() {
        return starter;
    }

    /** Whether a player has started a run. */
    public boolean hasStarter() {
        return starter != null;
    }

    /**
     * Handles a message from {@code sender}, on the network thread. A start sets the starter when
     * there is none; anything else counts only from the starter.
     */
    public void onMessage(Object sender, byte[] bytes) {
        GrugControlWire.Message message;
        try {
            message = GrugControlWire.decode(bytes);
        } catch (IllegalArgumentException e) {
            System.out.println("[GRUG CONTROL] dropped a message that does not decode: " + e);
            return;
        }
        if (message.type == GrugControlWire.RUN_START) {
            if (starter == null) {
                starter = sender;
                System.out.println("[GRUG CONTROL] " + sender + " started the run");
            } else {
                System.out.println(
                        "[GRUG CONTROL] ignored a run start from "
                                + sender
                                + ", the run was started by "
                                + starter);
            }
            return;
        }
        if (!sender.equals(starter)) {
            System.out.println(
                    "[GRUG CONTROL] dropped an answer from "
                            + sender
                            + ", who did not start the run");
            return;
        }
        if (message.type != GrugControlWire.ANSWER_ERROR
                && message.type != GrugControlWire.ANSWER_CHUNK) {
            System.out.println(
                    "[GRUG CONTROL] dropped a message of type "
                            + message.type
                            + " from the client");
            return;
        }
        Pending request = pending.get(message.id);
        if (request == null) {
            System.out.println(
                    "[GRUG CONTROL] dropped an answer to request "
                            + message.id
                            + ", which is not waiting (late or repeated)");
            return;
        }
        if (message.type == GrugControlWire.ANSWER_ERROR) {
            request.png.completeExceptionally(new ControlException(message.text));
        } else {
            accept(request, message);
        }
    }

    private static void accept(Pending request, GrugControlWire.Message chunk) {
        synchronized (request) {
            if (request.chunks == null) {
                request.chunks = new byte[chunk.total][];
            }
            if (chunk.total != request.chunks.length
                    || chunk.index < 0
                    || chunk.index >= chunk.total
                    || request.chunks[chunk.index] != null) {
                request.png.completeExceptionally(
                        new ControlException(
                                "The client's answer has an inconsistent chunk "
                                        + chunk.index
                                        + " of "
                                        + chunk.total
                                        + "."));
                return;
            }
            request.chunks[chunk.index] = chunk.data;
            request.received++;
            if (request.received < request.chunks.length) {
                return;
            }
            int length = 0;
            for (byte[] piece : request.chunks) {
                length += piece.length;
            }
            byte[] whole = new byte[length];
            int at = 0;
            for (byte[] piece : request.chunks) {
                System.arraycopy(piece, 0, whole, at, piece.length);
                at += piece.length;
            }
            request.png.complete(whole);
        }
    }

    /**
     * Asks the starter's client for the rectangle of its frame and waits for it, with the world
     * frozen. Called from the server thread.
     */
    public BufferedImage capture(int x1, int y1, int x2, int y2) throws ControlException {
        Object to = starter;
        if (to == null) {
            throw new ControlException(
                    "No client has started a run, so there is no screen to capture. A client"
                            + " starts a run when its player starts one.");
        }
        long id = nextId.getAndIncrement();
        Pending request = new Pending();
        pending.put(id, request);
        long startNanos = System.nanoTime();
        try {
            link.flushWorld();
            System.out.println(
                    "[GRUG CONTROL] request "
                            + id
                            + ": capture ("
                            + x1
                            + ","
                            + y1
                            + ","
                            + x2
                            + ","
                            + y2
                            + ") sent to "
                            + to
                            + ", the world is frozen until the client answers");
            if (!link.sendToStarter(to, GrugControlWire.captureRequest(id, x1, y1, x2, y2))) {
                throw new ControlException(
                        "The player who started the run is not connected any more.");
            }
            byte[] png;
            try {
                png = request.png.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new ControlException(
                        "The client did not answer request "
                                + id
                                + " within "
                                + timeoutMillis
                                + " ms, so the run gave up waiting and the world is running"
                                + " again.");
            } catch (ExecutionException e) {
                throw new ControlException(
                        "The client refused or failed request "
                                + id
                                + ": "
                                + e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ControlException("The wait for the client's answer was interrupted.");
            }
            BufferedImage image;
            try {
                image = ImageIO.read(new ByteArrayInputStream(png));
            } catch (IOException e) {
                image = null;
            }
            if (image == null) {
                throw new ControlException(
                        "The client's answer to request " + id + " is not a PNG.");
            }
            return image;
        } finally {
            pending.remove(id);
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            System.out.println(
                    "[GRUG CONTROL] request "
                            + id
                            + " finished after "
                            + millis
                            + " ms: the world was frozen for about "
                            + (millis / 50)
                            + " ticks and is running again");
        }
    }
}
