package net.grug.minecraft.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

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
 * <p>Only the player who sent {@link GrugControlWire#RUN_START} may answer, and only a player the
 * loader says has finished login ({@link Link#mayStartRun}) can become the starter. That is the
 * server's half of the consent rule; the client's half is in {@link GrugControlClient}. What a
 * message from someone else does is nothing (an expected race is silent), and what a malformed or
 * oversized message from the starter does is fail the calls waiting on it with a {@link
 * ControlException}, which the host function reports as the test's error.
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

        /**
         * Whether {@code sender} may start a run: any player who finished login. Asked before a
         * start is accepted.
         */
        boolean mayStartRun(Object sender);
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
        final int width;
        final int height;

        /** A call, answered by a value; a capture is answered by chunks. */
        final boolean call;

        byte[][] chunks = null;
        int received = 0;
        long bytes = 0;

        Pending(int width, int height, boolean call) {
            this.width = width;
            this.height = height;
            this.call = call;
        }
    }

    private final Link link;
    private final long timeoutMillis;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicReference<Object> starter = new AtomicReference<>();
    private final GrugSettledRevision settled = new GrugSettledRevision();

    public GrugControlServer(Link link, long timeoutMillis) {
        this.link = link;
        this.timeoutMillis = timeoutMillis;
    }

    /** The player who started the run, or null when none has. */
    public Object starter() {
        return starter.get();
    }

    /** Whether a player has started a run. */
    public boolean hasStarter() {
        return starter.get() != null;
    }

    /**
     * Handles a message from {@code sender}, on the network thread. A start sets the starter when
     * there is none and the loader allows the sender; anything else counts only from the starter.
     */
    public void onMessage(Object sender, byte[] bytes) {
        if (sender == null) {
            // The loader could not say who sent it, so nobody can be held to it: whatever waits
            // fails instead of waiting on an answer that cannot be attributed.
            failAll("A control message arrived from a sender the server could not identify.");
            return;
        }
        boolean fromStarter = sender.equals(starter.get());
        GrugControlWire.Message message;
        try {
            message = GrugControlWire.decode(bytes);
        } catch (IllegalArgumentException e) {
            if (fromStarter) {
                failAll("The client sent a malformed control message: " + e.getMessage());
            }
            return;
        }
        if (message.type == GrugControlWire.RUN_START) {
            if (link.mayStartRun(sender) && starter.compareAndSet(null, sender)) {
                System.out.println("[GRUG CONTROL] " + sender + " started the run");
            }
            return;
        }
        if (!fromStarter) {
            return;
        }
        if (message.type != GrugControlWire.ANSWER_ERROR
                && message.type != GrugControlWire.ANSWER_CHUNK
                && message.type != GrugControlWire.ANSWER_VALUE) {
            failAll("The client sent a control message of type " + message.type + ".");
            return;
        }
        Pending request = pending.get(message.id);
        if (request == null) {
            // Late or repeated: the request finished already, which is an expected race.
            return;
        }
        if (message.type == GrugControlWire.ANSWER_ERROR) {
            request.png.completeExceptionally(new ControlException(message.text));
        } else if (request.call != (message.type == GrugControlWire.ANSWER_VALUE)) {
            request.png.completeExceptionally(
                    new ControlException(
                            "The client answered request "
                                    + message.id
                                    + " with the wrong kind of answer."));
        } else if (request.call) {
            request.png.complete(new byte[] {message.value});
        } else {
            accept(request, message);
        }
    }

    /** Fails every request that is waiting, as the starter's call error. */
    private void failAll(String reason) {
        for (Pending request : pending.values()) {
            request.png.completeExceptionally(new ControlException(reason));
        }
    }

    /** The run is over: tells the starter's client, forgets it, and fails what still waits. */
    public void endRun() {
        Object to = starter.getAndSet(null);
        if (to != null) {
            link.sendToStarter(to, GrugControlWire.runEnd());
        }
        settled.forget();
        failAll("The run ended while the call was waiting.");
    }

    /** {@code sender} is gone: if it started the run, the run has no screen any more. */
    public void onDisconnect(Object sender) {
        if (sender != null && starter.compareAndSet(sender, null)) {
            failAll("The player who started the run disconnected.");
        }
    }

    private static void accept(Pending request, GrugControlWire.Message chunk) {
        synchronized (request) {
            if (request.chunks == null) {
                request.chunks = new byte[chunk.total][];
            }
            // decode already guarantees 0 <= index < total.
            if (chunk.total != request.chunks.length || request.chunks[chunk.index] != null) {
                request.png.completeExceptionally(
                        new ControlException(
                                "The client's answer has an inconsistent chunk "
                                        + chunk.index
                                        + " of "
                                        + chunk.total
                                        + "."));
                return;
            }
            // Each chunk is at most MAX_CHUNK and the sum is checked against the cap on every
            // chunk, so this cannot overflow.
            request.bytes = Math.addExact(request.bytes, (long) chunk.data.length);
            if (request.bytes > GrugControlWire.MAX_PNG_BYTES) {
                request.png.completeExceptionally(
                        new ControlException(
                                "The client's answer is over "
                                        + GrugControlWire.MAX_PNG_BYTES
                                        + " bytes of PNG."));
                return;
            }
            request.chunks[chunk.index] = chunk.data;
            request.received++;
            if (request.received < request.chunks.length) {
                return;
            }
            byte[] whole = new byte[(int) request.bytes];
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
        Object to = starter.get();
        if (to == null) {
            throw new ControlException(
                    "No client has started a run, so there is no screen to capture. A client"
                            + " starts a run when its player starts one.");
        }
        long id = nextId.getAndIncrement();
        Pending request = new Pending(x2 - x1, y2 - y1, false);
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
            byte[] png = await(request, id);
            BufferedImage image = decodeChecked(png, request, id);
            System.out.println(
                    "[GRUG CONTROL] request "
                            + id
                            + " answered: a "
                            + image.getWidth()
                            + "x"
                            + image.getHeight()
                            + " image, mean colour "
                            + meanColour(image));
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

    /** Waits for the answer to request {@code id}, or throws why there is none. */
    private byte[] await(Pending request, long id) throws ControlException {
        try {
            return request.png.get(timeoutMillis, TimeUnit.MILLISECONDS);
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
    }

    private static final AtomicBoolean FAILED = new AtomicBoolean();

    /** Forgets that a forwarded call failed; the server thread calls it before a sequence. */
    public static void clearFailure() {
        FAILED.set(false);
    }

    /**
     * Whether a forwarded call has failed since {@link #clearFailure}, so a host function that made
     * more than one call reports the first cause and not a second one it led to.
     */
    public static boolean failed() {
        return FAILED.get();
    }

    /** Asks the starter's client whether the game has the sound under {@code path}. */
    public boolean hasSound(String path) throws ControlException {
        byte result = call(GrugControlWire.CALL_HAS_SOUND, path, 0, 0);
        if (result != 0 && result != 1) {
            throw failure("The client answered has_sound with " + result + ", not 0 or 1.");
        }
        return result == 1;
    }

    /** Asks the starter's client to play the sound under {@code path}, and waits for its ack. */
    public void playUiSound(String path, double volume, double pitch) throws ControlException {
        byte result = call(GrugControlWire.CALL_PLAY_UI_SOUND, path, volume, pitch);
        if (result != 1) {
            throw failure("The client answered play_ui_sound with " + result + ", not the ack 1.");
        }
    }

    /**
     * Asks the starter's client whether it has drawn the world the server has, and waits for the
     * answer with the world frozen as in {@link #capture} (#1086).
     *
     * <p>The server's own answer is about frames and block packets it owes nobody, so a server-run
     * test that places blocks and waits for the client to draw them has to ask the client. The
     * changes the test made are flushed first, as a capture's are, because the client would
     * otherwise answer about a world without them.
     *
     * <p>A settled answer is remembered for the revision it was given at, which is what {@link
     * GrugWorldRevision} bumps on every change, so a wait that asks once per tick freezes the world
     * only when a host function has changed it since the last settled answer. A client that has not
     * seen the revision yet answers unsettled, so a test that places a block and asks in the same
     * tick is told the client has not drawn it.
     */
    public boolean worldSettled() throws ControlException {
        Object to = starter.get();
        if (to == null) {
            throw new ControlException(
                    "No client has started a run, so there is no client to ask whether the world is"
                            + " settled. A client starts a run when its player starts one.");
        }
        long revision = GrugWorldRevision.current();
        if (settled.answeredSettled(revision)) {
            return true;
        }
        link.flushWorld();
        byte result;
        try {
            result = forward(GrugControlWire.CALL_WORLD_SETTLED, "", 0, 0, revision);
        } catch (ControlException e) {
            FAILED.set(true);
            throw e;
        }
        if (result != 0 && result != 1) {
            throw failure("The client answered world_settled with " + result + ", not 0 or 1.");
        }
        settled.record(revision, result == 1);
        return result == 1;
    }

    private static ControlException failure(String message) {
        FAILED.set(true);
        return new ControlException(message);
    }

    /** Why a call's arguments cannot be sent, or null: the same checks the client makes. */
    static String callProblem(byte call, String name, double volume, double pitch) {
        String problem = GrugSoundArgs.pathProblem(name);
        if (problem == null && call == GrugControlWire.CALL_PLAY_UI_SOUND) {
            problem = GrugSoundArgs.levelsProblem(volume, pitch);
        }
        return problem;
    }

    /**
     * Forwards one client-only call to the starter's client and waits for its one-byte result, with
     * the world frozen as in {@link #capture}. The arguments are checked here as well as by the
     * host function, so a caller that skipped it still cannot send what the client would refuse.
     */
    private byte call(byte call, String name, double volume, double pitch) throws ControlException {
        String problem = callProblem(call, name, volume, pitch);
        if (problem != null) {
            throw failure("The call was not sent: " + problem);
        }
        try {
            return forward(call, name, volume, pitch, 0);
        } catch (ControlException e) {
            FAILED.set(true);
            throw e;
        }
    }

    /**
     * Forwards one client-only call to the starter's client and waits for its one-byte result, with
     * the world frozen as in {@link #capture}. {@code revision} is the world revision a {@link
     * GrugControlWire#CALL_WORLD_SETTLED} asks about, and is unused by the sound calls.
     */
    private byte forward(byte call, String name, double volume, double pitch, long revision)
            throws ControlException {
        Object to = starter.get();
        if (to == null) {
            throw new ControlException(
                    "No client has started a run, so there is no client to perform the call. A"
                            + " client starts a run when its player starts one.");
        }
        long id = nextId.getAndIncrement();
        Pending request = new Pending(0, 0, true);
        pending.put(id, request);
        long startNanos = System.nanoTime();
        try {
            // No flushWorld here: a sound call depends on no world state, and flushing sends the
            // queued block changes to the client early, which changed what a later test's client
            // saw (a chest removed by an explosion before its block entity arrived, on b1.7.3). A
            // settle ask flushes before it reaches here, because its answer is about those changes.
            byte[] message =
                    call == GrugControlWire.CALL_WORLD_SETTLED
                            ? GrugControlWire.worldSettledRequest(id, revision)
                            : GrugControlWire.callRequest(id, call, name, volume, pitch);
            System.out.println(
                    "[GRUG CONTROL] request "
                            + id
                            + ": "
                            + (call == GrugControlWire.CALL_WORLD_SETTLED
                                    ? "world_settled at revision " + revision
                                    : "call " + call + " \"" + name + "\"")
                            + " sent to "
                            + to
                            + ", the world is frozen until the client answers");
            if (!link.sendToStarter(to, message)) {
                throw new ControlException(
                        "The player who started the run is not connected any more.");
            }
            byte result = await(request, id)[0];
            System.out.println("[GRUG CONTROL] request " + id + " answered: " + result);
            return result;
        } finally {
            pending.remove(id);
            System.out.println(
                    "[GRUG CONTROL] request "
                            + id
                            + " finished after "
                            + (System.nanoTime() - startNanos) / 1_000_000L
                            + " ms and the world is running again");
        }
    }

    /**
     * Decodes the PNG after reading its size from the header, so a small file that claims a huge
     * image is refused before any pixel buffer exists, and after checking the size is the rectangle
     * that was asked for.
     */
    private static BufferedImage decodeChecked(byte[] png, Pending request, long id)
            throws ControlException {
        String notPng = "The client's answer to request " + id + " is not a PNG.";
        try (ImageInputStream stream =
                ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new ControlException(notPng);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width != request.width || height != request.height) {
                    throw new ControlException(
                            "The client's answer to request "
                                    + id
                                    + " is "
                                    + width
                                    + "x"
                                    + height
                                    + ", but "
                                    + request.width
                                    + "x"
                                    + request.height
                                    + " was asked for.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            throw new ControlException(notPng);
        }
    }

    /** The average of the pixels as {@code #rrggbb}, so a log shows what the answer looked like. */
    static String meanColour(BufferedImage image) {
        long red = 0;
        long green = 0;
        long blue = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                red += (rgb >> 16) & 0xFF;
                green += (rgb >> 8) & 0xFF;
                blue += rgb & 0xFF;
            }
        }
        long count = (long) image.getWidth() * image.getHeight();
        return String.format("#%02x%02x%02x", red / count, green / count, blue / count);
    }
}
