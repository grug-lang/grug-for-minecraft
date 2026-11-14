package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugModFileStates;
import net.grug.minecraft.grug.Vec3;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * <p>A call whose effect is the server's, such as a container click, is applied by the game's own
 * packet handler rather than by the call: the packet is queued while the server thread is frozen,
 * and {@link #applyClientWork}, which a loader calls between ticks, reads it (#1094).
 *
 * <p>Only the player who sent {@link GrugControlWire#RUN_START} may answer a call or ask for a
 * give, and only a player the loader says has finished login ({@link Link#mayStartRun}) can become
 * the starter. That is the server's half of the consent rule; the client's half is in {@link
 * GrugControlClient}. What a message from someone else does is nothing (an expected race is
 * silent), and what a malformed or oversized message from the starter does is fail the calls
 * waiting on it with a {@link ControlException}, which the host function reports as the test's
 * error. An answer is what a call waits on; a give waits for nothing and only joins the queue the
 * server thread drains before its next tick (#1280).
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

        /**
         * Applies what the starter's client has already sent this server and the frozen server
         * thread has not read yet (#1094).
         *
         * <p>A forwarded call that makes the client send a packet, such as a container click, has
         * its effect applied by the game's own packet handler, which runs on the server thread.
         * That thread is the one frozen inside the call, so the client's click packet is only
         * queued when the call returns; the game's own next tick would read it too late for a test
         * that ticks before it. This is the loader's one way to read that queue from the test's own
         * tick loop, and the caller ({@link GrugControlServer#applyClientWork}) calls it after a
         * call that could have sent a packet, and after a give the client asked for, which marks
         * the same drain (#1280).
         */
        void applyClientWork();

        /**
         * Gives {@code count} of the item named {@code item} to this server's own copy of the test
         * player, which is the give the starter's client sent (#1099). The name is how the server
         * finds its own item, so a client's item list that differs from this server's cannot make
         * it give a different item. The default hands it to the game's adapter, which every
         * loader's link shares, and a unit test covers it while another overrides it to see what
         * was queued.
         */
        default void applyGive(String item, int count) {
            GrugGiveCalls.giveOnServer(GrugCore.getAdapter(), item, count);
        }

        /**
         * Whether this loader gives to the player who asked, whoever they are, after an operator
         * check (#882), instead of only to the run's starter with the older {@link #applyGive}. A
         * loader that answers true implements {@link #playerOfSender}, {@link #tell} and {@link
         * #runOnServerThread}. The default is false, which is the older loaders' path until each
         * one has its own.
         */
        default boolean givesToSender() {
            return false;
        }

        /**
         * The server's player the sender is, or null when that player has left. Called on the
         * server thread, only when {@link #givesToSender}.
         */
        default Object playerOfSender(Object sender) {
            return null;
        }

        /**
         * Tells the sender's player a one-line error, shown the way the game shows a refused
         * command. Called on the server thread, only when {@link #givesToSender}.
         */
        default void tell(Object sender, String message) {}

        /**
         * Runs {@code task} on the server thread at its next opportunity, from any thread. A
         * player's give arrives on the network thread and changes the world, so it has to be
         * applied there, run or no run. Only when {@link #givesToSender}.
         */
        default void runOnServerThread(Runnable task) {
            task.run();
        }

        /**
         * Called once, on the network thread, after {@code starter} became the run's starter. The
         * harness's CI servers make that player an operator here, so the gives a run's tests make
         * pass the same permission check a player's would (#882). The default does nothing.
         */
        default void onRunStarted(Object starter) {}
    }

    /** A request that cannot be answered; its message is what the test's error says. */
    public static final class ControlException extends Exception {
        private static final long serialVersionUID = 1L;

        public ControlException(String message) {
            super(message);
        }
    }

    /** What kind of answer a request waits for, and the wire message that carries it. */
    private enum Answer {
        /** A PNG, cut into {@link GrugControlWire#ANSWER_CHUNK}s. */
        CHUNKS(GrugControlWire.ANSWER_CHUNK),
        /** One byte, from an {@link GrugControlWire#ANSWER_VALUE}. */
        VALUE(GrugControlWire.ANSWER_VALUE),
        /** One measurement, from an {@link GrugControlWire#ANSWER_NUMBER}. */
        NUMBER(GrugControlWire.ANSWER_NUMBER),
        /** A whole screen's measurements, from an {@link GrugControlWire#ANSWER_NUMBERS}. */
        NUMBERS(GrugControlWire.ANSWER_NUMBERS);

        /**
         * The wire type this kind of answer arrives as, declared by the constant itself, so a kind
         * added later cannot be mistaken for another one, which a {@code default:} arm that meant
         * the last of them would have let it be (#1289).
         */
        final byte wireType;

        Answer(byte wireType) {
            this.wireType = wireType;
        }
    }

    private static final class Pending {
        final CompletableFuture<byte[]> png = new CompletableFuture<>();
        final int width;
        final int height;

        /** A capture is answered by chunks, a call by a value or a number. */
        final Answer answer;

        byte[][] chunks = null;
        int received = 0;
        long bytes = 0;

        Pending(int width, int height, Answer answer) {
            this.width = width;
            this.height = height;
            this.answer = answer;
        }
    }

    private final Link link;
    private final long timeoutMillis;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicReference<Object> starter = new AtomicReference<>();
    private final GrugSettledRevision settled = new GrugSettledRevision();

    /**
     * Whether the server thread owes itself a drain: a forwarded call can have left the server a
     * packet to read (#1094), or the starter's client asked for a give (#1280). It is set from two
     * threads and cleared on one. The server thread sets it in {@link #forward}, inside a test's
     * tick, because that call can have made the client send a packet. The connection's network
     * thread sets it in {@link #onMessage} when a give arrives, because a give changes the world
     * and only the server thread may apply it, so the give has to bring that thread here. The
     * server thread clears it in {@link #applyClientWork}, between ticks, which is the side that
     * pays the drain. It is volatile for the network thread's write.
     */
    private volatile boolean clientWorkPending;

    /**
     * The gives the starter's client sent and this server has not applied yet (#1099), in the order
     * they arrived. Written on the network thread, drained by {@link #applyClientWork} on the
     * server thread, because a give changes the world and only that thread may.
     */
    private final Queue<Give> gives;

    /** One give a joined client asked for: the item's name and how many of it. */
    private static final class Give {
        /**
         * Who asked, or null for the older starter-only path, whose give goes to the test player.
         */
        final Object sender;

        final String item;
        final int count;

        Give(Object sender, String item, int count) {
            this.sender = sender;
            this.item = item;
            this.count = count;
        }
    }

    /**
     * The measurements the last {@link GrugControlWire#CALL_SCREEN_GEOMETRY} answered, and the two
     * revisions it was answered at, or null when nothing is known (#1277). A screen's geometry
     * moves only when the screen or the window changes, so one batch answers every read the test
     * makes until something can have moved it: see {@link #screenRead}.
     */
    private volatile double[] geometry;

    private long geometryWorldRevision = Long.MIN_VALUE;
    private long geometryScreenRevision = Long.MIN_VALUE;

    /**
     * The identity the last {@link #screenOpen} answer named, 0 when it said no screen was open, so
     * a screen that opened, closed or was replaced by another one is noticed (#1094, #1289).
     */
    private volatile long screenIdentity;

    /**
     * Counts the screen calls and disconnects since the last batch answer; the network thread's
     * {@link #onDisconnect} also bumps it, so it is atomic.
     */
    private final AtomicLong screenRevision = new AtomicLong();

    /** The screen can have moved or been replaced, so no batch answer may be trusted again. */
    private void forgetGeometry() {
        screenRevision.incrementAndGet();
    }

    public GrugControlServer(Link link, long timeoutMillis) {
        this(link, timeoutMillis, new ConcurrentLinkedQueue<Give>());
    }

    /**
     * The constructor {@link #GrugControlServer(Link, long)} uses, with the give queue injected so
     * a test can hold the network thread inside the queue's {@code add} and run a drain in that
     * window. {@link #onMessage} queues a give before it marks the drain, and a test proves that
     * order is what keeps a drain that runs meanwhile from taking the mark and finding no give;
     * without the injection there is no way to stand between the two. Production passes a {@link
     * ConcurrentLinkedQueue}, and only this class ever adds to it or polls it.
     */
    @SuppressWarnings("unchecked")
    GrugControlServer(Link link, long timeoutMillis, Queue<?> gives) {
        this.link = link;
        this.timeoutMillis = timeoutMillis;
        this.gives = (Queue<Give>) gives;
    }

    /**
     * Applies the packets the starter's client sent during the calls made since the last drain
     * (#1094). A loader calls this once before each runner tick, outside any grug call.
     *
     * <p>A test clicks, returns with {@code ctx.not_done()}, and asserts on a later tick, because
     * the click's effect is the server's: the client sends the game's own click packet before it
     * sends its control answer, TCP order puts it in the server's queue first, and the frozen
     * server thread cannot read it until the call returns. Draining here, before the next grug
     * call, is what applies the click, and it applies it to the server's own menu, so a slot change
     * reports an {@code item_inserted} error to the test's assertion rather than folding it into
     * the call that clicked (#583, #836).
     *
     * <p>A drain is paid by a forwarded call that can have sent a packet and by a give the client
     * asked for: a give marks the flag from the network thread, and the drain it pays is the
     * loader's own drain as well as this queue's, even though only the give is owed (#1280). A tick
     * that only reads a screen's geometry pays nothing, because a read cannot make the client send
     * anything; a call the wire says can send a packet still marks the flag when it happens to send
     * nothing, which costs one empty drain and keeps the rule simple.
     */
    public void applyClientWork() {
        if (!clientWorkPending) {
            return;
        }
        clientWorkPending = false;
        link.applyClientWork();
        // After the game's own packets, so a give sees the inventory a click of the same tick
        // already changed: the client sends both in that order, and this is the order it sees.
        drainGives();
    }

    /**
     * Applies the queued gives, on the server thread: the older starter-only ones to the test
     * player, and a player's own to that player after the operator check (#882).
     */
    private void drainGives() {
        Give give;
        while ((give = gives.poll()) != null) {
            if (give.sender == null) {
                link.applyGive(give.item, give.count);
                continue;
            }
            Object player = link.playerOfSender(give.sender);
            if (player == null) {
                // The player left before the give was applied: nobody is left to tell.
                continue;
            }
            String refusal =
                    GrugGiveCalls.giveToPlayer(
                            GrugCore.getAdapter(), player, give.item, give.count);
            if (refusal != null) {
                link.tell(give.sender, refusal);
            }
        }
    }

    /**
     * Drops the gives the starter's client sent that this server has not applied yet (#1144).
     *
     * <p>The runner calls this when it resets the state a test starts from, between two tests. A
     * give sent as one test ended is applied by the drain that follows it, which is the next test's
     * first drain: without this drop, the give lands after the reset and the new test starts with
     * an item it never asked for, which is the leak the promoted tests exist to catch.
     */
    public void forgetQueuedGives() {
        gives.clear();
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
     * Whether a message of {@code type} may be handled on the connection's network thread right
     * now: as {@link GrugControlWire#handledOnRead} says, an answer always and a give once a
     * starter exists (#1280).
     *
     * <p>A give is taken there because the frozen server thread only reaches its connection's drain
     * between two calls, so a give left to the game thread can arrive many ticks late, or never.
     * But a give that arrives before the start of its own connection has been handled has no
     * starter to be attributed to yet, while that start is still queued for the game thread, so
     * taking the give on the network thread would drop it (#1288). Left to the game thread, the
     * give is read after the start it followed, which is the stream order a server keeps on its
     * own.
     */
    public boolean handlesOnRead(byte type) {
        if (!GrugControlWire.handledOnRead(type)) {
            return false;
        }
        // An answer is the other message taken there, and it can only arrive while a call waits,
        // which needs a starter already.
        return type != GrugControlWire.GIVE_REQUEST || hasStarter();
    }

    /**
     * Handles a message from {@code sender}, on the network thread. A start sets the starter when
     * there is none and the loader allows the sender; anything else counts only from the starter,
     * except a give on a loader that gives to its sender (#882), which any player may ask for.
     */
    public void onMessage(Object sender, byte[] bytes) {
        GrugControlWire.Message message;
        try {
            message = GrugControlWire.decode(bytes);
        } catch (IllegalArgumentException e) {
            if (sender == null || sender.equals(starter.get())) {
                failAll("The client sent a malformed control message: " + e.getMessage());
            }
            return;
        }
        if (sender == null) {
            // The loader could not say who sent this. Only an answer is worth failing for: it is
            // the only message something waits on, so an unattributable one fails what waits
            // instead of letting it time out. A give waits for nothing, so an unattributable one is
            // dropped: failing the calls in flight would blame them for a message they never sent
            // (#1288).
            if (GrugControlWire.isAnswer(message.type)) {
                failAll("An answer arrived from a sender the server could not identify.");
            }
            return;
        }
        boolean fromStarter = sender.equals(starter.get());
        if (message.type == GrugControlWire.RUN_START) {
            if (link.mayStartRun(sender) && starter.compareAndSet(null, sender)) {
                System.out.println("[GRUG CONTROL] " + sender + " started the run");
                link.onRunStarted(sender);
            } else {
                // The first start owns the run, so a second player's start, or a reconnected
                // client's, is ignored rather than moving the run to it (#1027). The client says it
                // sent its start either way, so this line is what run-multiplayer.sh reads to know
                // that two real starts reached the server and only one was accepted.
                System.out.println("[GRUG CONTROL] " + sender + " sent a start, which was ignored");
            }
            return;
        }
        if (message.type == GrugControlWire.GIVE_REQUEST && link.givesToSender()) {
            // A player's own give (#882): any player may ask, none of it is the run's business, and
            // the operator check happens where the give is applied, on the server thread, so the
            // operator list is read where it is written. The order of the add and the mark is the
            // one explained below, and the scheduled drain covers a server with no run, whose
            // runner never calls applyClientWork.
            gives.add(new Give(sender, message.text, message.x1));
            clientWorkPending = true;
            link.runOnServerThread(this::drainGives);
            return;
        }
        if (!fromStarter) {
            return;
        }
        if (message.type == GrugControlWire.GIVE_REQUEST) {
            // The client's half of a give its overlay's click performed (#1099): the client has the
            // item list and this server has the player, so the client names the item and this
            // server gives its own item of that name to its own copy. Applied by the next
            // applyClientWork, on the server thread, because it changes the world.
            //
            // The give joins the queue before the flag is marked, and that order is load-bearing:
            // a drain that reads the flag then finds the give, so this thread cannot mark a drain
            // that has already polled, which would leave the give unapplied until an unrelated call
            // marked the flag again. The two-thread test that holds the network thread inside the
            // queue's add pins this.
            gives.add(new Give(null, message.text, message.x1));
            clientWorkPending = true;
            return;
        }
        if (!GrugControlWire.isAnswer(message.type)) {
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
        } else if (wrongKind(request.answer, message.type)) {
            request.png.completeExceptionally(
                    new ControlException(
                            "The client answered request "
                                    + message.id
                                    + " with the wrong kind of answer."));
        } else if (request.answer == Answer.VALUE) {
            request.png.complete(new byte[] {message.value});
        } else if (request.answer == Answer.NUMBER) {
            request.png.complete(java.nio.ByteBuffer.allocate(8).putDouble(message.number).array());
        } else if (request.answer == Answer.NUMBERS) {
            java.nio.ByteBuffer numbers = java.nio.ByteBuffer.allocate(8 * message.numbers.length);
            for (double number : message.numbers) {
                numbers.putDouble(number);
            }
            request.png.complete(numbers.array());
        } else {
            accept(request, message);
        }
    }

    /** Whether a message of type {@code type} is not the answer {@code answer} waits for. */
    private static boolean wrongKind(Answer answer, byte type) {
        // Each kind names its own wire type, so a kind added later cannot be answered as this one.
        return type != answer.wireType;
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
        gives.clear();
        forgetGeometry();
        geometry = null;
        screenIdentity = 0;
        failAll("The run ended while the call was waiting.");
    }

    /** {@code sender} is gone: if it started the run, the run has no screen any more. */
    public void onDisconnect(Object sender) {
        if (sender != null && starter.compareAndSet(sender, null)) {
            // The client that answered is gone, so an answer remembered for its revision says
            // nothing about the client a later starter uses.
            settled.forget();
            gives.clear();
            // The revision before the cache: a batch answer from the call in flight may still be
            // stored after this returned, and the bumped revision is what says it is not this
            // client's (#1289).
            forgetGeometry();
            geometry = null;
            screenIdentity = 0;
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
     * frozen. {@code eye} is the eye the test's room was built above, which the client waits for
     * its own camera to reach before it captures (#1161). Called from the server thread.
     */
    public BufferedImage capture(Vec3 eye, int x1, int y1, int x2, int y2) throws ControlException {
        Object to = starter.get();
        if (to == null) {
            throw new ControlException(
                    "No client has started a run, so there is no screen to capture. A client"
                            + " starts a run when its player starts one.");
        }
        if (eye == null) {
            throw new ControlException(
                    "There is no test camera to take a capture from, so the client would not know"
                            + " which view to draw.");
        }
        long id = nextId.getAndIncrement();
        Pending request = new Pending(x2 - x1, y2 - y1, Answer.CHUNKS);
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
                            + ") from the eye ("
                            + eye.x()
                            + ","
                            + eye.y()
                            + ","
                            + eye.z()
                            + ") sent to "
                            + to
                            + ", the world is frozen until the client answers");
            if (!link.sendToStarter(
                    to,
                    GrugControlWire.captureRequest(
                            id, x1, y1, x2, y2, eye.x(), eye.y(), eye.z()))) {
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
        byte result = call(GrugControlWire.CALL_HAS_SOUND, path, 0, 0, 0);
        return yesOrNo(result, "has_sound");
    }

    /** Asks the starter's client to play the sound under {@code path}, and waits for its ack. */
    public void playUiSound(String path, double volume, double pitch) throws ControlException {
        byte result = call(GrugControlWire.CALL_PLAY_UI_SOUND, path, volume, pitch, 0);
        if (result != 1) {
            throw failure("The client answered play_ui_sound with " + result + ", not the ack 1.");
        }
    }

    /**
     * Asks the starter's client which screen it has open (#1094), and answers whether it has one.
     * The answer is the screen's identity rather than a yes or no, so a screen replaced by another
     * one while the panel stayed open is not the screen the batch measured (#1289).
     */
    public boolean screenOpen() throws ControlException {
        double answer = number(GrugControlWire.CALL_SCREEN_OPEN, "", 0, 0, 0);
        if (!Double.isFinite(answer) || answer < 0 || answer != Math.floor(answer)) {
            throw failure(
                    "The client answered screen_open with "
                            + answer
                            + ", not the identity of a screen.");
        }
        // A screen that opened or closed since the last ask, or one that replaced another while
        // it stayed open, is not the screen the batch measured, so a read after an observed change
        // asks again even when nothing here announced it.
        if ((long) answer != screenIdentity) {
            screenIdentity = (long) answer;
            forgetGeometry();
        }
        return screenIdentity != 0;
    }

    /**
     * Asks the starter's client for one of its screen's measurements, named as {@link
     * GrugScreenCalls} names them (#1094). A client with no screen open answers 0.
     *
     * <p>The first read forwards one {@link GrugControlWire#CALL_SCREEN_GEOMETRY} for all {@link
     * GrugControlWire#GEOMETRY_COUNT} measurements and answers the rest from it (#1277), because a
     * test works a screen's geometry out through helpers that read several of them in one tick, and
     * on a client that reads its packets once per frame every round trip costs that frame. The name
     * is checked here, exactly as a single read checked it, so a name with no measurement is
     * refused with the same error, and a read after a screen that moved can never answer from the
     * old one: every call that can move or replace the screen drops the answer first, so does every
     * world change, because a block the test bends can open or close the screen the client shows,
     * and so does a screen {@link #screenOpen} saw opened, closed or replaced since the last
     * answer. The runner rebuilds the room before each test, which is a world change too, so no
     * test reads the screen a previous one left.
     */
    public double screenRead(String read) throws ControlException {
        String problem = callProblem(GrugControlWire.CALL_SCREEN_READ, read, 0, 0, 0);
        if (problem != null) {
            throw failure("The call was not sent: " + problem);
        }
        return GrugScreenCalls.fromGeometry(geometry(), read);
    }

    /**
     * The measurements of the starter's client's screen, from the answer the last batch got when
     * nothing can have moved it since, or from one forward now.
     */
    private double[] geometry() throws ControlException {
        long world = GrugWorldRevision.current();
        long screen = screenRevision.get();
        double[] known = geometry;
        if (known != null && geometryWorldRevision == world && geometryScreenRevision == screen) {
            return known;
        }
        Object asked = starter.get();
        double[] values = numbers(GrugControlWire.CALL_SCREEN_GEOMETRY, "", 0, 0, 0);
        rememberGeometry(asked, values, world, screen);
        return values;
    }

    /**
     * Remembers a batch answer for the revisions it was asked at, unless the client it was asked of
     * is no longer the starter. The network thread's {@link #onDisconnect} clears the cache and
     * bumps the revision while an answer can be in flight, so storing after it returned would put
     * the screen of a client that left back (#1289). An answer that arrives then is still this
     * read's, because the client did answer, and no later read's.
     *
     * <p>Package-private so a unit test can reach the store with a client that has gone, which is
     * the order the two threads cannot be made to take in a test otherwise, and can see that the
     * answer was refused and that one from the client that is still there was kept.
     */
    boolean rememberGeometry(Object asked, double[] values, long world, long screen) {
        if (asked == null || asked != starter.get()) {
            return false;
        }
        geometry = values;
        geometryWorldRevision = world;
        geometryScreenRevision = screen;
        return true;
    }

    /**
     * Asks the starter's client to press a key on its open screen, and answers whether an overlay
     * took it (#1094).
     */
    public boolean pressScreenKey(int key, boolean ctrl, boolean release) throws ControlException {
        forgetGeometry();
        byte result =
                call(GrugControlWire.CALL_PRESS_SCREEN_KEY, "", key, ctrl ? 1 : 0, release ? 1 : 0);
        return yesOrNo(result, "press_screen_key");
    }

    /**
     * Asks the starter's client to turn its mouse wheel on its open screen, and answers whether an
     * overlay took the movement (#1094).
     */
    public boolean scrollScreen(double x, double y, double delta) throws ControlException {
        forgetGeometry();
        byte result = call(GrugControlWire.CALL_SCROLL_SCREEN, "", x, y, delta);
        return yesOrNo(result, "scroll_screen");
    }

    /** Asks the starter's client to close its screen, and waits for its ack (#1094). */
    public void closeScreen() throws ControlException {
        forgetGeometry();
        byte result = call(GrugControlWire.CALL_CLOSE_SCREEN, "", 0, 0, 0);
        if (result != 1) {
            throw failure("The client answered close_screen with " + result + ", not the ack 1.");
        }
    }

    /**
     * Asks the starter's client for one component of its own render camera (#1161), 0 for x, 1 for
     * y and 2 for z.
     */
    public double camera(int axis) throws ControlException {
        return number(GrugControlWire.CALL_CAMERA, "", axis, 0, 0);
    }

    /**
     * Asks the starter's client to press a mouse button on its open screen, and answers whether an
     * overlay took the press (#1094). The press is the client's half of a container click: the
     * click packet it sends is applied by {@link #applyClientWork} on a later tick.
     */
    public boolean clickScreen(double x, double y, int button) throws ControlException {
        forgetGeometry();
        byte result = call(GrugControlWire.CALL_CLICK_SCREEN, "", x, y, button);
        return yesOrNo(result, "click_screen");
    }

    /** Asks the starter's client to open its own inventory, and waits for its ack (#1094). */
    public void openInventory() throws ControlException {
        forgetGeometry();
        byte result = call(GrugControlWire.CALL_OPEN_INVENTORY, "", 0, 0, 0);
        if (result != 1) {
            throw failure("The client answered open_inventory with " + result + ", not the ack 1.");
        }
    }

    /**
     * Asks the starter's client for the number saved under {@code name} by its own copy of the
     * block entity at the position, which is the copy a frame pass reads (#1162). A server's copy
     * is the one it simulates, so a test that wants to know whether a change reached the client's
     * copy has to ask the client.
     */
    public double savedNumber(double x, double y, double z, String name) throws ControlException {
        return number(GrugControlWire.CALL_SAVED_NUMBER, name, x, y, z);
    }

    /**
     * Asks the starter's client for the fingerprint of the block light of the section a position
     * falls in, in its own copy of the world, which is the copy its mesher bakes a room from
     * (#1207). A server's copy is the world it simulates, so a test that wants to know whether a
     * light change reached the client's copy has to ask the client.
     */
    public double clientLightFingerprint(double x, double y, double z) throws ControlException {
        return number(GrugControlWire.CALL_CLIENT_LIGHT, "", x, y, z);
    }

    /**
     * Asks the starter's client whether it draws overlays at all, which is what {@code
     * Test.overlay_supported} answers (#1099): a server's own answer is always false, because the
     * loader marks support when it registers the screen hooks it only has on a client.
     */
    public boolean overlaySupported() throws ControlException {
        byte result = call(GrugControlWire.CALL_OVERLAY_SUPPORTED, "", 0, 0, 0);
        return yesOrNo(result, "overlay_supported");
    }

    /**
     * Asks the starter's client whether a frame has drawn the overlay of the mod named {@code mod},
     * which is the mod that owns the running test, on the screen it has open now (#1099). The
     * client keeps the mark per mod, so a later test of another mod does not read this one's frame.
     */
    public boolean overlayDrawn(String mod) throws ControlException {
        byte result = call(GrugControlWire.CALL_OVERLAY_DRAWN, mod, 0, 0, 0);
        return yesOrNo(result, "overlay_drawn");
    }

    /**
     * Asks the starter's client whether its own copy of the block at the position shows the mining
     * crack over the block's own geometry (#1040).
     */
    public boolean miningCrack(double x, double y, double z) throws ControlException {
        byte result = call(GrugControlWire.CALL_MINING_CRACK, "", x, y, z);
        return yesOrNo(result, "mining_crack");
    }

    /**
     * Asks the starter's client to put the mod file under {@code path} into the state at {@code
     * stateIndex} of {@code GrugModFileStates}, in its own mods directory (#1040), and waits for
     * its ack.
     */
    public void modFileState(String path, int stateIndex) throws ControlException {
        // The answer is only the ack: a client that could not set the state answers an error,
        // which the call reports.
        call(GrugControlWire.CALL_MOD_FILE_STATE, path, stateIndex, 0, 0);
    }

    /**
     * How many changes to {@code path} the starter's engine has reported, or, when {@code
     * loaderReloads} is set, how many reloads its loader has finished for it (#1040).
     */
    public double reloadCount(String path, boolean loaderReloads) throws ControlException {
        return number(GrugControlWire.CALL_RELOAD_COUNT, path, loaderReloads ? 1 : 0, 0, 0);
    }

    /** The 1 or 0 a yes-or-no call answers, or the error that fails the call. */
    private static boolean yesOrNo(byte result, String call) throws ControlException {
        if (result != 0 && result != 1) {
            throw failure("The client answered " + call + " with " + result + ", not 0 or 1.");
        }
        return result == 1;
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
     *
     * <p>The queued block changes are flushed once per revision, not once per ask: a wait that
     * polls while the client catches up would otherwise send every change again on every tick, and
     * an early send is what a later test's client saw (#1078).
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
        if (settled.needsFlush(revision)) {
            link.flushWorld();
            settled.flushed(revision);
        }
        byte result;
        try {
            result =
                    forward(GrugControlWire.CALL_WORLD_SETTLED, "", revision, 0, 0, Answer.VALUE)[
                            0];
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
    static String callProblem(byte call, String name, double volume, double pitch, double third) {
        switch (call) {
            case GrugControlWire.CALL_SCREEN_READ:
                return GrugScreenCalls.readProblem(name);
            case GrugControlWire.CALL_PRESS_SCREEN_KEY:
                String key = screenKeyProblem(volume);
                if (key != null) return key;
                return flagProblem(pitch, third);
            case GrugControlWire.CALL_SCROLL_SCREEN:
                return scrollProblem(volume, pitch, third);
            case GrugControlWire.CALL_CLICK_SCREEN:
                return clickProblem(volume, pitch, third);
            case GrugControlWire.CALL_SCREEN_OPEN:
            case GrugControlWire.CALL_CLOSE_SCREEN:
            case GrugControlWire.CALL_OPEN_INVENTORY:
            case GrugControlWire.CALL_SCREEN_GEOMETRY:
                return null;
            case GrugControlWire.CALL_CAMERA:
                return cameraAxisProblem(volume);
            case GrugControlWire.CALL_SAVED_NUMBER:
                return savedNumberProblem(name, volume, pitch, third);
            case GrugControlWire.CALL_CLIENT_LIGHT:
                return positionProblem(volume, pitch, third);
            case GrugControlWire.CALL_OVERLAY_SUPPORTED:
                return null;
            case GrugControlWire.CALL_OVERLAY_DRAWN:
                return overlayModProblem(name);
            case GrugControlWire.CALL_MINING_CRACK:
                return positionProblem(volume, pitch, third);
            case GrugControlWire.CALL_MOD_FILE_STATE:
                String path = modPathProblem(name);
                if (path != null) return path;
                if (volume != Math.floor(volume)
                        || volume < 0
                        || volume >= GrugModFileStates.STATES.length) {
                    return "The mod file state has to be the index of a known state, and was "
                            + volume
                            + ".";
                }
                return null;
            case GrugControlWire.CALL_RELOAD_COUNT:
                if (volume != 0 && volume != 1) {
                    return "A reload count read asks for 0 or 1, and was " + volume + ".";
                }
                return modPathProblem(name);
            default:
                String problem = GrugSoundArgs.pathProblem(name);
                if (problem == null && call == GrugControlWire.CALL_PLAY_UI_SOUND) {
                    problem = GrugSoundArgs.levelsProblem(volume, pitch);
                }
                return problem;
        }
    }

    /**
     * Why a forwarded mod file path cannot be sent, or null: the wire has to carry it whole, and it
     * has to name a file under a mod's assets directory without leaving it.
     */
    private static String modPathProblem(String path) {
        if (path == null || path.isEmpty()) return "The call names no mod file.";
        if (path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > GrugControlWire.MAX_NAME) {
            return "The mod file's path is over " + GrugControlWire.MAX_NAME + " bytes.";
        }
        return GrugModFileStates.clientPathProblem(path);
    }

    /** Why a forwarded key press carries what it cannot, or null. The key is the first number. */
    private static String screenKeyProblem(double key) {
        if (!Double.isFinite(key) || key != Math.floor(key) || key < 0) {
            return "The key has to be a whole number from 0 up, and was " + key + ".";
        }
        return null;
    }

    /**
     * Why a forwarded camera read carries what it cannot, or null. The axis is the first number.
     */
    private static String cameraAxisProblem(double axis) {
        if (axis != 0 && axis != 1 && axis != 2) {
            return "A camera read asks for the axis 0, 1 or 2, and was " + axis + ".";
        }
        return null;
    }

    /**
     * Why a forwarded saved-number read carries what it cannot, or null. The name is the number's,
     * which the wire has to carry whole, and the three numbers are the block entity's position.
     */
    private static String savedNumberProblem(String name, double x, double y, double z) {
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > GrugControlWire.MAX_NAME) {
            return "The saved number's name is over " + GrugControlWire.MAX_NAME + " bytes.";
        }
        return positionProblem(x, y, z);
    }

    /** Why the three numbers are not a block position, or null. */
    private static String positionProblem(double x, double y, double z) {
        for (double position : new double[] {x, y, z}) {
            if (!Double.isFinite(position)) {
                return "The call names a block position, and one of its numbers was not finite.";
            }
        }
        return null;
    }

    /**
     * Why a forwarded drawn-overlay read carries what it cannot, or null. The name is the mod that
     * owns the running test, which the wire has to carry whole.
     */
    private static String overlayModProblem(String mod) {
        if (mod == null) return "The overlay read names no test mod.";
        if (mod.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > GrugControlWire.MAX_NAME) {
            return "The test mod's name is over " + GrugControlWire.MAX_NAME + " bytes.";
        }
        return null;
    }

    /** Why the two flags of a forwarded key press are not booleans, or null. */
    private static String flagProblem(double ctrl, double release) {
        for (double flag : new double[] {ctrl, release}) {
            if (flag != 0 && flag != 1) {
                return "A key press flag has to be 0 or 1, and was " + flag + ".";
            }
        }
        return null;
    }

    /** Why a forwarded wheel movement cannot be sent, or null. */
    private static String scrollProblem(double x, double y, double delta) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return "The position has to be finite.";
        }
        if (!Double.isFinite(delta)) {
            return "The delta has to be finite.";
        }
        return null;
    }

    /** Why a forwarded screen press cannot be sent, or null. The button is the third number. */
    private static String clickProblem(double x, double y, double button) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return "The position has to be finite.";
        }
        return GrugScreenCalls.buttonProblem(button);
    }

    /**
     * Forwards one client-only call to the starter's client and waits for its one-byte result, with
     * the world frozen as in {@link #capture}. The arguments are checked here as well as by the
     * host function, so a caller that skipped it still cannot send what the client would refuse.
     */
    private byte call(byte call, String name, double volume, double pitch, double third)
            throws ControlException {
        String problem = callProblem(call, name, volume, pitch, third);
        if (problem != null) {
            throw failure("The call was not sent: " + problem);
        }
        try {
            return forward(call, name, volume, pitch, third, Answer.VALUE)[0];
        } catch (ControlException e) {
            FAILED.set(true);
            throw e;
        }
    }

    /** Forwards a call whose answer is a measurement, as {@link #call} forwards one with a byte. */
    private double number(byte call, String name, double volume, double pitch, double third)
            throws ControlException {
        String problem = callProblem(call, name, volume, pitch, third);
        if (problem != null) {
            throw failure("The call was not sent: " + problem);
        }
        try {
            return java.nio.ByteBuffer.wrap(
                            forward(call, name, volume, pitch, third, Answer.NUMBER))
                    .getDouble();
        } catch (ControlException e) {
            FAILED.set(true);
            throw e;
        }
    }

    /**
     * Forwards a call whose answer is a whole screen's measurements, as {@link #number} forwards
     * one that measures a single value. The call carries no argument a client could refuse, so it
     * has no problem to check here: {@link #screenRead} checked the read's name before it came.
     */
    private double[] numbers(byte call, String name, double volume, double pitch, double third)
            throws ControlException {
        try {
            java.nio.ByteBuffer answer =
                    java.nio.ByteBuffer.wrap(
                            forward(call, name, volume, pitch, third, Answer.NUMBERS));
            double[] values = new double[GrugControlWire.GEOMETRY_COUNT];
            for (int at = 0; at < values.length; at++) {
                values[at] = answer.getDouble();
            }
            return values;
        } catch (ControlException e) {
            FAILED.set(true);
            throw e;
        }
    }

    /**
     * Forwards one client-only call to the starter's client and waits for its answer, with the
     * world frozen as in {@link #capture}. The three numbers are the call's own: a settle ask puts
     * its world revision in the first, a sound call its volume and pitch in the first two, and a
     * screen call the arguments {@link GrugControlWire} documents for it.
     */
    private byte[] forward(
            byte call, String name, double volume, double pitch, double third, Answer answer)
            throws ControlException {
        Object to = starter.get();
        if (to == null) {
            throw new ControlException(
                    "No client has started a run, so there is no client to perform the call. A"
                            + " client starts a run when its player starts one.");
        }
        long id = nextId.getAndIncrement();
        Pending request = new Pending(0, 0, answer);
        pending.put(id, request);
        long startNanos = System.nanoTime();
        if (GrugControlWire.sendsPacket(call)) {
            // Marked before the message goes out, so the drain happens even when this call fails
            // or times out: the client may still have sent a packet the server has to apply.
            clientWorkPending = true;
        }
        try {
            // No flushWorld here: a sound or screen call depends on no world state, and flushing
            // sends the queued block changes to the client early, which changed what a later
            // test's client saw (a chest removed by an explosion before its block entity arrived,
            // on b1.7.3). A settle ask flushes before it reaches here, because its answer is about
            // those changes.
            byte[] message =
                    call == GrugControlWire.CALL_WORLD_SETTLED
                            ? GrugControlWire.worldSettledRequest(id, (long) volume)
                            : GrugControlWire.callRequest(id, call, name, volume, pitch, third);
            System.out.println(
                    "[GRUG CONTROL] request "
                            + id
                            + ": "
                            + describe(call, name, volume)
                            + " sent to "
                            + to
                            + ", the world is frozen until the client answers");
            if (!link.sendToStarter(to, message)) {
                throw new ControlException(
                        "The player who started the run is not connected any more.");
            }
            byte[] result = await(request, id);
            System.out.println(
                    "[GRUG CONTROL] request "
                            + id
                            + " answered: "
                            + describeAnswer(answer, result));
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

    /** What a forwarded call answered, for the line that says the world is running again. */
    private static String describeAnswer(Answer answer, byte[] result) {
        if (answer == Answer.NUMBER) {
            return String.valueOf(java.nio.ByteBuffer.wrap(result).getDouble());
        }
        if (answer == Answer.NUMBERS) {
            java.nio.ByteBuffer numbers = java.nio.ByteBuffer.wrap(result);
            StringBuilder text = new StringBuilder();
            for (int at = 0; at < GrugControlWire.GEOMETRY_COUNT; at++) {
                if (at > 0) text.append(", ");
                text.append(numbers.getDouble());
            }
            return text.toString();
        }
        return String.valueOf(result[0]);
    }

    /** What a forwarded call is, for the line that says what the world was frozen for. */
    private static String describe(byte call, String name, double volume) {
        if (call == GrugControlWire.CALL_WORLD_SETTLED) {
            return "world_settled at revision " + (long) volume;
        }
        if (call == GrugControlWire.CALL_SCREEN_GEOMETRY) {
            return "screen geometry";
        }
        if (call == GrugControlWire.CALL_CAMERA) {
            return "camera axis " + (int) volume;
        }
        if (call == GrugControlWire.CALL_SAVED_NUMBER) {
            return "saved number \"" + name + "\"";
        }
        if (call == GrugControlWire.CALL_CLIENT_LIGHT) {
            return "client light section";
        }
        if (call == GrugControlWire.CALL_OVERLAY_DRAWN) {
            return "overlay drawn of \"" + name + "\"";
        }
        if (call == GrugControlWire.CALL_MINING_CRACK) {
            return "mining crack";
        }
        if (call == GrugControlWire.CALL_MOD_FILE_STATE) {
            return "mod file state " + (int) volume + " of \"" + name + "\"";
        }
        if (call == GrugControlWire.CALL_RELOAD_COUNT) {
            return (volume == 1 ? "reload count" : "hot reload count") + " of \"" + name + "\"";
        }
        return "call " + call + " \"" + name + "\"";
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
