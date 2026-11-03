package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;

/** Both ends of the test-control channel, wired to each other the way a loader's packets do. */
class GrugControlTest {
    private static final String PLAYER = "alice";

    /** A client whose frame is a solid colour, and that answers on the calling thread. */
    private static class Loopback implements GrugControlServer.Link, GrugControlClient.Link {
        GrugControlServer server;
        GrugControlClient client;
        boolean connected = true;
        boolean mayStart = true;
        final List<byte[]> toClient = new ArrayList<>();
        boolean flushed = false;
        final List<byte[]> toServer = new ArrayList<>();

        @Override
        public boolean sendToStarter(Object starter, byte[] message) {
            if (!connected) return false;
            toClient.add(message);
            client.onMessage(message);
            return true;
        }

        @Override
        public void flushWorld() {
            flushed = true;
        }

        @Override
        public boolean mayStartRun(Object sender) {
            return mayStart;
        }

        @Override
        public void send(byte[] message) {
            toServer.add(message);
            server.onMessage(PLAYER, message);
        }
    }

    private static Loopback wire(long timeoutMillis, int dropWidth, GrugControlClient.Capturer c) {
        return wire(timeoutMillis, dropWidth, -1, c);
    }

    private static Loopback wire(
            long timeoutMillis, int dropWidth, int lateWidth, GrugControlClient.Capturer c) {
        Loopback loop = new Loopback();
        loop.server = new GrugControlServer(loop, timeoutMillis);
        loop.client = new GrugControlClient(loop, c, NO_CALLS, dropWidth, lateWidth);
        return loop;
    }

    /** A caller that fails the test if a call reaches it. */
    private static final GrugControlClient.Caller NO_CALLS =
            new GrugControlClient.Caller() {
                @Override
                public boolean hasSound(String path) {
                    throw new AssertionError("no call was expected");
                }

                @Override
                public void playUiSound(String path, double volume, double pitch) {
                    throw new AssertionError("no call was expected");
                }
            };

    private static Loopback wireCalls(long timeoutMillis, GrugControlClient.Caller caller) {
        Loopback loop = new Loopback();
        loop.server = new GrugControlServer(loop, timeoutMillis);
        loop.client = new GrugControlClient(loop, solid(0), caller, -1, -1);
        return loop;
    }

    /** A caller that knows the sound "random.click" and records what it was asked to play. */
    private static final class Sounds implements GrugControlClient.Caller {
        final List<String> played = new ArrayList<>();

        @Override
        public boolean hasSound(String path) {
            return path.equals("random.click");
        }

        @Override
        public void playUiSound(String path, double volume, double pitch) {
            played.add(path + " " + volume + " " + pitch);
        }
    }

    private static GrugControlClient.Capturer solid(int rgb) {
        return (x1, y1, x2, y2, done, failed) -> {
            BufferedImage image = new BufferedImage(x2 - x1, y2 - y1, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, rgb);
                }
            }
            done.accept(image);
        };
    }

    private static String capture(Runnable body) {
        PrintStream original = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return bytes.toString();
    }

    @Test
    void aCaptureRoundTripsAcrossTheFreezeAndSaysSo() throws Exception {
        Loopback loop = wire(1000, -1, solid(0x123456));
        loop.client.beginRun();
        assertTrue(loop.server.hasStarter());
        assertEquals(PLAYER, loop.server.starter());

        BufferedImage[] got = new BufferedImage[1];
        String printed =
                capture(
                        () -> {
                            try {
                                got[0] = loop.server.capture(10, 20, 30, 50);
                            } catch (GrugControlServer.ControlException e) {
                                throw new AssertionError(e);
                            }
                        });

        assertEquals(20, got[0].getWidth());
        assertEquals(30, got[0].getHeight());
        assertEquals(0x123456, got[0].getRGB(3, 3) & 0xFFFFFF);
        assertTrue(loop.flushed);
        assertTrue(printed.contains("request 1: capture (10,20,30,50) sent to alice"), printed);
        assertTrue(printed.contains("request 1 received, capturing"), printed);
        assertTrue(printed.contains("answered with a 20x30 capture in 1 packet(s)"), printed);
        assertTrue(printed.contains("the world was frozen for about"), printed);
        assertTrue(printed.contains("is running again"), printed);
    }

    @Test
    void aLargeCaptureTravelsInSeveralPacketsAndComesBackWhole() throws Exception {
        // Noise does not compress, so the PNG is larger than one chunk.
        Random random = new Random(1);
        GrugControlClient.Capturer noise =
                (x1, y1, x2, y2, done, failed) -> {
                    BufferedImage image =
                            new BufferedImage(x2 - x1, y2 - y1, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            image.setRGB(x, y, random.nextInt(0x1000000));
                        }
                    }
                    done.accept(image);
                };
        Loopback loop = wire(1000, -1, noise);
        loop.client.beginRun();
        loop.toServer.clear();

        BufferedImage image = loop.server.capture(0, 0, 100, 100);

        assertEquals(100, image.getWidth());
        assertTrue(loop.toServer.size() > 1, "chunks: " + loop.toServer.size());
        for (byte[] packet : loop.toServer) {
            assertTrue(packet.length < 32767);
        }
    }

    @Test
    void aClientThatNeverStartedARunRefusesAndNothingIsCaptured() {
        boolean[] captured = {false};
        Loopback loop =
                wire(
                        1000,
                        -1,
                        (x1, y1, x2, y2, done, failed) -> {
                            captured[0] = true;
                        });
        // The server has a starter, as if a malicious client had sent the start for it, but this
        // client's own player did not start anything.
        loop.server.onMessage(PLAYER, GrugControlWire.runStart());

        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.capture(0, 0, 10, 10));

        assertTrue(e.getMessage().contains("refused"), e.getMessage());
        assertTrue(e.getMessage().contains("no run was started by this player"), e.getMessage());
        assertFalse(captured[0]);
        assertFalse(loop.client.runActive());
    }

    @Test
    void aRunThatEndedIsNoLongerObeyed() {
        Loopback loop = wire(1000, -1, solid(0));
        loop.client.beginRun();
        assertTrue(loop.client.runActive());
        loop.client.endRun();

        assertThrows(
                GrugControlServer.ControlException.class, () -> loop.server.capture(0, 0, 10, 10));
    }

    @Test
    void withoutAStarterThereIsNoScreenToCapture() {
        Loopback loop = wire(1000, -1, solid(0));

        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.capture(0, 0, 10, 10));

        assertTrue(e.getMessage().contains("No client has started a run"), e.getMessage());
        assertFalse(loop.server.hasStarter());
        assertNull(loop.server.starter());
    }

    @Test
    void aStarterWhoLeftIsReported() {
        Loopback loop = wire(1000, -1, solid(0));
        loop.client.beginRun();
        loop.connected = false;

        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.capture(0, 0, 10, 10));

        assertTrue(e.getMessage().contains("not connected any more"), e.getMessage());
    }

    @Test
    void anUnansweredRequestTimesOutWithAClearError() {
        Loopback loop = wire(50, 7, solid(0));
        loop.client.beginRun();

        String[] printed = new String[1];
        GrugControlServer.ControlException[] error = new GrugControlServer.ControlException[1];
        printed[0] =
                capture(
                        () ->
                                error[0] =
                                        assertThrows(
                                                GrugControlServer.ControlException.class,
                                                () -> loop.server.capture(0, 0, 7, 10)));

        assertTrue(error[0].getMessage().contains("did not answer request 1 within 50 ms"));
        assertTrue(printed[0].contains("fault injection: request 1 is never answered"), printed[0]);
        assertTrue(printed[0].contains("is running again"), printed[0]);
    }

    @Test
    void aStaleAnswerLandingWhileTheNextRequestWaitsIsIgnoredAndTheNextIsAnsweredByItsOwn() {
        // The 9 wide request is answered only when the next request reaches the client, so the
        // stale 9x10 red answer is sent after the server gave up and while request 2 is pending.
        Loopback loop = wire(50, -1, 9, solid(0xFF0000));
        loop.client.beginRun();

        assertThrows(
                GrugControlServer.ControlException.class, () -> loop.server.capture(0, 0, 9, 10));
        int before = loop.toServer.size();
        BufferedImage next = captureOrFail(loop.server, 0, 0, 10, 10);

        assertEquals(10, next.getWidth());
        assertEquals(0xFF0000, next.getRGB(0, 0) & 0xFFFFFF);
        // The stale answer was sent (before the new one), and the server did not take it.
        GrugControlWire.Message first = GrugControlWire.decode(loop.toServer.get(before));
        assertEquals(1, first.id);
        assertEquals(2, GrugControlWire.decode(loop.toServer.get(before + 1)).id);
    }

    @Test
    void ifAStaleAnswerWereAcceptedTheNextRequestWouldGetTheWrongImage() {
        // The control for the test above: a server that took any answer while one request waits
        // would complete request 2 with request 1's 9x10 image, which the size check refuses.
        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> answeredWithAPngOf(9, 10).capture(0, 0, 10, 10));
        assertTrue(e.getMessage().contains("9x10, but 10x10 was asked for"), e.getMessage());
    }

    @Test
    void aCaptureWithTheRightWidthButTheWrongHeightIsRefused() {
        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> answeredWithAPngOf(10, 9).capture(0, 0, 10, 10));
        assertTrue(e.getMessage().contains("10x9, but 10x10 was asked for"), e.getMessage());
    }

    /** A server whose request 1 is answered with a PNG of the given size. */
    private static GrugControlServer answeredWithAPngOf(int w, int h) {
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", png);
            return lyingServer(
                    GrugControlWire.answerChunks(1, png.toByteArray()).toArray(new byte[0][]));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static BufferedImage captureOrFail(
            GrugControlServer server, int x1, int y1, int x2, int y2) {
        try {
            return server.capture(x1, y1, x2, y2);
        } catch (GrugControlServer.ControlException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aCaptureTheClientCannotTakeIsAnErrorWithItsReason() {
        Loopback loop =
                wire(
                        1000,
                        -1,
                        (x1, y1, x2, y2, done, failed) -> failed.accept("the window is 800x600"));
        loop.client.beginRun();

        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.capture(0, 0, 10, 10));

        assertTrue(e.getMessage().contains("the window is 800x600"), e.getMessage());
    }

    @Test
    void messagesFromSomeoneElseOrForAFinishedRequestAreSilentlyIgnored() {
        Loopback loop = wire(1000, -1, solid(0));
        loop.client.beginRun();

        String printed =
                capture(
                        () -> {
                            loop.server.onMessage("mallory", GrugControlWire.answerError(1, "x"));
                            loop.server.onMessage("mallory", new byte[] {99});
                            loop.server.onMessage(PLAYER, GrugControlWire.answerError(99, "x"));
                            loop.server.onMessage("mallory", GrugControlWire.runStart());
                        });

        assertEquals("", printed);
        assertEquals(PLAYER, loop.server.starter());
    }

    @Test
    void aStartIsAcceptedOnlyFromAnAuthorisedSenderAndOnlyOnce() {
        Loopback loop = wire(1000, -1, solid(0));
        loop.mayStart = false;
        loop.server.onMessage("mallory", GrugControlWire.runStart());
        assertFalse(loop.server.hasStarter());

        loop.mayStart = true;
        loop.server.onMessage("alice", GrugControlWire.runStart());
        loop.server.onMessage("bob", GrugControlWire.runStart());
        assertEquals("alice", loop.server.starter());
    }

    @Test
    void twoSimultaneousStartsLeaveExactlyOneStarter() throws Exception {
        for (int round = 0; round < 50; round++) {
            Loopback loop = wire(1000, -1, solid(0));
            Thread[] threads = new Thread[8];
            for (int i = 0; i < threads.length; i++) {
                String name = "p" + i;
                threads[i] =
                        new Thread(() -> loop.server.onMessage(name, GrugControlWire.runStart()));
            }
            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join();
            Object winner = loop.server.starter();
            assertNotNull(winner);
            loop.server.onMessage("late", GrugControlWire.runStart());
            assertEquals(winner, loop.server.starter());
        }
    }

    @Test
    void anUnattributableSenderFailsTheWaitingCall() {
        GrugControlServer.ControlException e =
                failFrom((server) -> server.onMessage(null, GrugControlWire.answerError(1, "x")));
        assertTrue(e.getMessage().contains("could not identify"), e.getMessage());
    }

    @Test
    void aMalformedMessageFromTheStarterFailsTheCallButOneFromSomeoneElseDoesNot() {
        GrugControlServer.ControlException e =
                failFrom((server) -> server.onMessage(PLAYER, new byte[] {99}));
        assertTrue(e.getMessage().contains("malformed control message"), e.getMessage());
        e =
                failFrom(
                        (server) ->
                                server.onMessage(
                                        PLAYER, GrugControlWire.captureRequest(1, 0, 0, 1, 1)));
        assertTrue(e.getMessage().contains("control message of type 2"), e.getMessage());
    }

    @Test
    void theRunEndingOrTheStarterLeavingFailsTheCallAndClearsTheStarter() {
        Loopback ended = silentLoop();
        GrugControlServer.ControlException e = waitingCallFails(ended, () -> ended.server.endRun());
        assertTrue(e.getMessage().contains("run ended"), e.getMessage());
        assertFalse(ended.server.hasStarter());
        assertEquals(
                GrugControlWire.RUN_END,
                GrugControlWire.decode(ended.toClient.get(ended.toClient.size() - 1)).type);

        Loopback left = silentLoop();
        e = waitingCallFails(left, () -> left.server.onDisconnect(PLAYER));
        assertTrue(e.getMessage().contains("disconnected"), e.getMessage());
        assertFalse(left.server.hasStarter());

        Loopback other = silentLoop();
        other.server.onDisconnect("mallory");
        other.server.onDisconnect(null);
        assertTrue(other.server.hasStarter());

        Loopback idle = wire(1000, -1, solid(0));
        idle.server.endRun();
        assertTrue(idle.toClient.isEmpty());
    }

    @Test
    void theClientStopsObeyingWhenTheServerEndsTheRunOrItDisconnects() {
        Loopback loop = wire(1000, -1, solid(0));
        loop.client.beginRun();
        loop.server.endRun();
        assertFalse(loop.client.runActive());
        assertThrows(
                GrugControlServer.ControlException.class, () -> loop.server.capture(0, 0, 10, 10));
    }

    private static Loopback silentLoop() {
        Loopback loop = wire(60_000, -1, (x1, y1, x2, y2, done, failed) -> {});
        loop.client.beginRun();
        return loop;
    }

    private static GrugControlServer.ControlException waitingCallFails(
            Loopback loop, Runnable interruption) {
        GrugControlServer.ControlException[] error = new GrugControlServer.ControlException[1];
        Thread caller =
                new Thread(
                        () ->
                                error[0] =
                                        assertThrows(
                                                GrugControlServer.ControlException.class,
                                                () -> loop.server.capture(0, 0, 10, 10)));
        caller.start();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (loop.toClient.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            Thread.sleep(20);
            interruption.run();
            caller.join(5000);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        assertFalse(caller.isAlive());
        return error[0];
    }

    private static GrugControlServer.ControlException failFrom(
            java.util.function.Consumer<GrugControlServer> hostile) {
        Loopback loop = silentLoop();
        return waitingCallFails(loop, () -> hostile.accept(loop.server));
    }

    /** A server whose client, instead of capturing, sends these messages as the answer. */
    private static GrugControlServer lyingServer(byte[]... answers) {
        Loopback sender =
                new Loopback() {
                    @Override
                    public boolean sendToStarter(Object starter, byte[] message) {
                        for (byte[] answer : answers) {
                            server.onMessage(PLAYER, answer);
                        }
                        return true;
                    }
                };
        GrugControlServer server = new GrugControlServer(sender, 1000);
        sender.server = server;
        server.onMessage(PLAYER, GrugControlWire.runStart());
        return server;
    }

    @Test
    void inconsistentChunksAreErrorsNotHangs() {
        byte[] one = {1};
        byte[][][] scenarios = {
            {chunk(1, 0, 2, one), chunk(1, 0, 2, one)},
            {chunk(1, 0, 2, one), chunk(1, 1, 3, one)},
        };
        for (byte[][] answers : scenarios) {
            GrugControlServer server = lyingServer(answers);
            GrugControlServer.ControlException e =
                    assertThrows(
                            GrugControlServer.ControlException.class,
                            () -> server.capture(0, 0, 1, 1));
            assertTrue(e.getMessage().contains("inconsistent chunk"), e.getMessage());
        }
    }

    @Test
    void aChunkOutsideItsCountIsMalformedAndFailsTheCall() {
        byte[] one = {1};
        for (byte[] bad :
                new byte[][] {
                    chunk(1, 5, 2, one),
                    chunk(1, -1, 2, one),
                    chunk(1, 0, 0, one),
                    chunk(1, 0, -3, one),
                    chunk(1, 0, GrugControlWire.MAX_CHUNKS + 1, one),
                }) {
            GrugControlServer server = lyingServer(bad);
            GrugControlServer.ControlException e =
                    assertThrows(
                            GrugControlServer.ControlException.class,
                            () -> server.capture(0, 0, 1, 1));
            assertTrue(e.getMessage().contains("malformed control message"), e.getMessage());
        }
    }

    @Test
    void anAnswerWhoseChunksAddUpToMoreThanTheCapIsRefusedBeforeItIsJoined() {
        byte[] full = new byte[GrugControlWire.MAX_CHUNK];
        int total = GrugControlWire.MAX_CHUNKS;
        byte[][] answers = new byte[total][];
        for (int i = 0; i < total; i++) {
            answers[i] = chunk(1, i, total, full);
        }
        // MAX_CHUNKS full chunks is just over MAX_PNG_BYTES.
        assertTrue((long) total * full.length > GrugControlWire.MAX_PNG_BYTES);
        GrugControlServer server = lyingServer(answers);
        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class, () -> server.capture(0, 0, 1, 1));
        assertTrue(e.getMessage().contains("bytes of PNG"), e.getMessage());
    }

    @Test
    void aCaptureOfAnotherSizeThanAskedForIsRefusedBeforeItIsDecoded() throws Exception {
        // A PNG header can claim any size: this one claims 60000x60000 and holds no pixels.
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", png);
        byte[] bytes = png.toByteArray();
        java.nio.ByteBuffer.wrap(bytes).putInt(16, 60000).putInt(20, 60000);
        GrugControlServer server =
                lyingServer(GrugControlWire.answerChunks(1, bytes).toArray(new byte[0][]));
        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class, () -> server.capture(0, 0, 8, 8));
        assertTrue(e.getMessage().contains("60000x60000, but 8x8 was asked for"), e.getMessage());
    }

    @Test
    void aTruncatedPngIsAnErrorAndSoIsOneThatIsNotAnImage() throws Exception {
        for (byte[] bad : new byte[][] {new byte[] {1, 2, 3}, truncatedPng()}) {
            GrugControlServer server =
                    lyingServer(GrugControlWire.answerChunks(1, bad).toArray(new byte[0][]));

            GrugControlServer.ControlException e =
                    assertThrows(
                            GrugControlServer.ControlException.class,
                            () -> server.capture(0, 0, 40, 40));

            assertTrue(e.getMessage().contains("is not a PNG"), e.getMessage());
        }
    }

    @Test
    void anInterruptedWaitIsAnError() {
        Loopback loop = wire(60_000, -1, (x1, y1, x2, y2, done, failed) -> {});
        loop.client.beginRun();
        Thread.currentThread().interrupt();
        try {
            GrugControlServer.ControlException e =
                    assertThrows(
                            GrugControlServer.ControlException.class,
                            () -> loop.server.capture(0, 0, 1, 1));
            assertTrue(e.getMessage().contains("interrupted"), e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theClientIgnoresMessagesItCannotUseSilently() {
        Loopback loop = wire(1000, -1, solid(0));

        String printed =
                capture(
                        () -> {
                            loop.client.onMessage(new byte[] {});
                            loop.client.onMessage(GrugControlWire.runStart());
                        });

        assertEquals("", printed);
        assertTrue(loop.toServer.isEmpty());
    }

    @Test
    void anAnswerOverTheCapIsAnErrorTheServerReports() {
        Loopback loop =
                wire(
                        1000,
                        -1,
                        (x1, y1, x2, y2, done, failed) -> {
                            // Noise does not compress, so this PNG is far over the cap.
                            int side = 2400;
                            BufferedImage big =
                                    new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
                            Random random = new Random(1);
                            for (int y = 0; y < side; y++) {
                                for (int x = 0; x < side; x++) {
                                    big.setRGB(x, y, random.nextInt());
                                }
                            }
                            done.accept(big);
                        });
        loop.client.beginRun();
        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.capture(0, 0, 2400, 2400));
        assertTrue(e.getMessage().contains("a control answer may hold"), e.getMessage());
    }

    @Test
    void theWireRoundTripsEveryMessage() {
        GrugControlWire.Message request =
                GrugControlWire.decode(GrugControlWire.captureRequest(7, 1, 2, 3, 4));
        assertEquals(GrugControlWire.CAPTURE_REQUEST, request.type);
        assertEquals(7, request.id);
        assertEquals(4, request.y2);

        GrugControlWire.Message error =
                GrugControlWire.decode(GrugControlWire.answerError(8, "no é"));
        assertEquals("no é", error.text);

        List<byte[]> none = GrugControlWire.answerChunks(9, new byte[0]);
        assertEquals(1, none.size());
        GrugControlWire.Message empty = GrugControlWire.decode(none.get(0));
        assertEquals(1, empty.total);
        assertArrayEquals(new byte[0], empty.data);

        assertEquals(
                GrugControlWire.RUN_START, GrugControlWire.decode(GrugControlWire.runStart()).type);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(new byte[] {42}));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugControlWire.decode(new byte[] {GrugControlWire.CAPTURE_REQUEST, 1}));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        GrugControlWire.decode(
                                java.nio.ByteBuffer.allocate(13)
                                        .put(GrugControlWire.ANSWER_ERROR)
                                        .putLong(1)
                                        .putInt(-5)
                                        .array()));
        assertNotNull(request);
    }

    @Test
    void theWireRefusesEveryLengthThatLiesBeforeAllocatingFromIt() {
        java.nio.ByteBuffer hugeText =
                java.nio.ByteBuffer.allocate(13)
                        .put(GrugControlWire.ANSWER_ERROR)
                        .putLong(1)
                        .putInt(Integer.MAX_VALUE);
        assertThrows(
                IllegalArgumentException.class, () -> GrugControlWire.decode(hugeText.array()));
        byte[] overText =
                java.nio.ByteBuffer.allocate(13 + GrugControlWire.MAX_TEXT + 1)
                        .put(GrugControlWire.ANSWER_ERROR)
                        .putLong(1)
                        .putInt(GrugControlWire.MAX_TEXT + 1)
                        .array();
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(overText));
        // A length that fits the cap but not the bytes that came.
        byte[] shortText =
                java.nio.ByteBuffer.allocate(13 + 2)
                        .put(GrugControlWire.ANSWER_ERROR)
                        .putLong(1)
                        .putInt(10)
                        .array();
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(shortText));
        byte[] overChunk = chunk(1, 0, 1, new byte[GrugControlWire.MAX_CHUNK + 1]);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(overChunk));
        byte[] shortChunk = chunk(1, 0, 1, new byte[5]);
        byte[] cut = java.util.Arrays.copyOf(shortChunk, shortChunk.length - 1);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(cut));
        byte[] negative = chunk(1, 0, 1, new byte[5]);
        java.nio.ByteBuffer.wrap(negative).putInt(1 + 8 + 8, -1);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(negative));
        byte[] trailing = java.util.Arrays.copyOf(GrugControlWire.runStart(), 3);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(trailing));
        assertEquals(
                GrugControlWire.RUN_END, GrugControlWire.decode(GrugControlWire.runEnd()).type);
        // The largest chunk the writer produces is accepted.
        assertEquals(
                GrugControlWire.MAX_CHUNK,
                GrugControlWire.decode(chunk(1, 0, 1, new byte[GrugControlWire.MAX_CHUNK]))
                        .data
                        .length);
    }

    private static byte[] chunk(long id, int index, int total, byte[] data) {
        return java.nio.ByteBuffer.allocate(1 + 8 + 12 + data.length)
                .put(GrugControlWire.ANSWER_CHUNK)
                .putLong(id)
                .putInt(index)
                .putInt(total)
                .putInt(data.length)
                .put(data)
                .array();
    }

    private static byte[] truncatedPng() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB), "png", png);
        byte[] all = png.toByteArray();
        byte[] cut = new byte[all.length / 2];
        System.arraycopy(all, 0, cut, 0, cut.length);
        return cut;
    }

    @Test
    void theMeanColourOfAnImageIsLoggedAsHex() {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x102030);
        image.setRGB(1, 0, 0x304050);
        org.junit.jupiter.api.Assertions.assertEquals(
                "#203040", GrugControlServer.meanColour(image));
    }

    @Test
    void aForwardedSoundCallReturnsTheClientsAnswerAndAnAck() throws Exception {
        Sounds sounds = new Sounds();
        Loopback loop = wireCalls(1000, sounds);
        loop.client.beginRun();

        String printed =
                capture(
                        () -> {
                            try {
                                assertTrue(loop.server.hasSound("random.click"));
                                assertFalse(loop.server.hasSound("no_such"));
                                loop.server.playUiSound("random.click", 0.5, 2);
                            } catch (GrugControlServer.ControlException e) {
                                throw new AssertionError(e);
                            }
                        });

        assertEquals(List.of("random.click 0.5 2.0"), sounds.played);
        assertTrue(loop.flushed);
        assertTrue(printed.contains("the world is frozen until the client answers"), printed);
        assertTrue(printed.contains("and the world is running again"), printed);
    }

    @Test
    void aCallWithoutAStarterOrFromAClientThatDidNotStartIsAClearError() {
        Loopback loop = wireCalls(1000, new Sounds());
        GrugControlServer.ControlException none =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.hasSound("random.click"));
        assertTrue(none.getMessage().contains("No client has started a run"), none.getMessage());

        loop.server.onMessage(PLAYER, GrugControlWire.runStart());
        GrugControlServer.ControlException refused =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.playUiSound("random.click", 1, 1));
        assertTrue(refused.getMessage().contains("refused"), refused.getMessage());
        assertTrue(refused.getMessage().contains("no run was started"), refused.getMessage());

        loop.connected = false;
        GrugControlServer.ControlException gone =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.hasSound("random.click"));
        assertTrue(gone.getMessage().contains("not connected"), gone.getMessage());
    }

    @Test
    void aCallTheWireCannotCarryNeverLeavesTheServer() {
        Loopback loop = wireCalls(1000, new Sounds());
        loop.client.beginRun();
        String longName = "a".repeat(GrugControlWire.MAX_NAME + 1);
        assertThrows(
                GrugControlServer.ControlException.class, () -> loop.server.hasSound(longName));
        assertThrows(
                GrugControlServer.ControlException.class,
                () -> loop.server.playUiSound("x", Double.NaN, 1));
        assertThrows(
                GrugControlServer.ControlException.class,
                () -> loop.server.playUiSound("x", 1, Double.NaN));
        assertThrows(
                GrugControlServer.ControlException.class,
                () -> loop.server.playUiSound("x", Double.POSITIVE_INFINITY, 1));
        assertThrows(
                GrugControlServer.ControlException.class,
                () -> loop.server.playUiSound("x", 1.5, 1));
        assertThrows(
                GrugControlServer.ControlException.class,
                () -> loop.server.playUiSound("x", 1, 0.25));
        assertThrows(
                GrugControlServer.ControlException.class,
                () -> loop.server.playUiSound("x", 1, Double.NEGATIVE_INFINITY));
        assertThrows(
                GrugControlServer.ControlException.class, () -> loop.server.hasSound("Not A Name"));
        assertTrue(loop.toClient.isEmpty());
        assertTrue(GrugControlServer.failed());
    }

    @Test
    void anUnansweredCallTimesOutAndTheWrongKindOfAnswerIsRefused() {
        List<byte[]> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        GrugControlServer.Link link =
                new GrugControlServer.Link() {
                    @Override
                    public boolean sendToStarter(Object starter, byte[] message) {
                        sent.add(message);
                        return true;
                    }

                    @Override
                    public void flushWorld() {}

                    @Override
                    public boolean mayStartRun(Object sender) {
                        return true;
                    }
                };
        GrugControlServer server = new GrugControlServer(link, 40);
        server.onMessage(PLAYER, GrugControlWire.runStart());

        GrugControlServer.ControlException timeout =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> server.hasSound("random.click"));
        assertTrue(timeout.getMessage().contains("did not answer"), timeout.getMessage());

        // A chunk answering a call, and a value answering a capture, are each the wrong kind.
        GrugControlServer chunky = new GrugControlServer(link, 5000);
        chunky.onMessage(PLAYER, GrugControlWire.runStart());
        int sentBefore = sent.size();
        Thread answerer =
                new Thread(
                        () -> {
                            awaitSent(sent, sentBefore + 1);
                            long id = GrugControlWire.decode(sent.get(sent.size() - 1)).id;
                            chunky.onMessage(
                                    PLAYER, GrugControlWire.answerChunks(id, new byte[1]).get(0));
                        });
        answerer.start();
        GrugControlServer.ControlException wrong =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> chunky.hasSound("random.click"));
        assertTrue(wrong.getMessage().contains("wrong kind of answer"), wrong.getMessage());

        GrugControlServer valued = new GrugControlServer(link, 5000);
        valued.onMessage(PLAYER, GrugControlWire.runStart());
        int before = sent.size();
        Thread valuer =
                new Thread(
                        () -> {
                            awaitSent(sent, before + 1);
                            long id = GrugControlWire.decode(sent.get(sent.size() - 1)).id;
                            valued.onMessage(PLAYER, GrugControlWire.answerValue(id, (byte) 1));
                        });
        valuer.start();
        GrugControlServer.ControlException wrongToo =
                assertThrows(
                        GrugControlServer.ControlException.class, () -> valued.capture(0, 0, 4, 4));
        assertTrue(wrongToo.getMessage().contains("wrong kind of answer"), wrongToo.getMessage());
    }

    private static void awaitSent(List<byte[]> sent, int count) {
        long until = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < until) {
            if (sent.size() >= count) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("nothing was sent");
    }

    @Test
    void theWireRoundTripsACallAndItsValueAndRefusesWhatLies() {
        GrugControlWire.Message call =
                GrugControlWire.decode(
                        GrugControlWire.callRequest(
                                5, GrugControlWire.CALL_PLAY_UI_SOUND, "random.click", 0.25, 1.5));
        assertEquals(GrugControlWire.CALL_REQUEST, call.type);
        assertEquals(5, call.id);
        assertEquals(GrugControlWire.CALL_PLAY_UI_SOUND, call.call);
        assertEquals("random.click", call.text);
        assertEquals(0.25, call.volume);
        assertEquals(1.5, call.pitch);

        GrugControlWire.Message value =
                GrugControlWire.decode(GrugControlWire.answerValue(6, (byte) 1));
        assertEquals(GrugControlWire.ANSWER_VALUE, value.type);
        assertEquals(1, value.value);

        byte[] unknown = GrugControlWire.callRequest(1, (byte) 9, "x", 0, 0);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(unknown));
        byte[] nan =
                GrugControlWire.callRequest(1, GrugControlWire.CALL_HAS_SOUND, "x", Double.NaN, 0);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(nan));
        byte[] nanPitch =
                GrugControlWire.callRequest(1, GrugControlWire.CALL_HAS_SOUND, "x", 0, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(nanPitch));
        byte[] longName =
                GrugControlWire.callRequest(
                        1,
                        GrugControlWire.CALL_HAS_SOUND,
                        "a".repeat(GrugControlWire.MAX_NAME + 1),
                        0,
                        0);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(longName));
        byte[] atCap =
                GrugControlWire.callRequest(
                        1,
                        GrugControlWire.CALL_HAS_SOUND,
                        "a".repeat(GrugControlWire.MAX_NAME),
                        0,
                        0);
        assertEquals(GrugControlWire.MAX_NAME, GrugControlWire.decode(atCap).text.length());
        byte[] cut = java.util.Arrays.copyOf(atCap, atCap.length - 1);
        assertThrows(IllegalArgumentException.class, () -> GrugControlWire.decode(cut));
        assertThrows(
                IllegalArgumentException.class,
                () -> GrugControlWire.decode(java.util.Arrays.copyOf(atCap, atCap.length + 1)));
    }

    @Test
    void theAdapterCallerHandsTheCallsToTheAdapterItIsGiven() {
        List<String> seen = new ArrayList<>();
        ModLoaderAdapter adapter =
                (ModLoaderAdapter)
                        java.lang.reflect.Proxy.newProxyInstance(
                                ModLoaderAdapter.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    seen.add(method.getName() + " " + args[0]);
                                    return method.getReturnType() == boolean.class
                                            ? Boolean.TRUE
                                            : null;
                                });
        GrugControlClient.Caller caller = GrugControlClient.adapterCaller(() -> adapter);
        assertTrue(caller.hasSound("a"));
        caller.playUiSound("b", 1, 1);
        assertEquals(List.of("hasSound a", "playUiSound b"), seen);
    }

    @Test
    void aClientRefusesWhatTheHostFunctionWouldAndAnAdapterThatThrowsIsAnError() {
        Loopback loop =
                wireCalls(
                        1000,
                        new GrugControlClient.Caller() {
                            @Override
                            public boolean hasSound(String path) {
                                throw new IllegalStateException("no sound engine");
                            }

                            @Override
                            public void playUiSound(String path, double volume, double pitch) {
                                throw new AssertionError("a refused call must not be performed");
                            }
                        });
        loop.client.beginRun();
        loop.toServer.clear();

        // A server that skipped its own checks still cannot make the client play this.
        for (byte[] bad :
                new byte[][] {
                    GrugControlWire.callRequest(
                            3,
                            GrugControlWire.CALL_PLAY_UI_SOUND,
                            "x",
                            Double.POSITIVE_INFINITY,
                            1),
                    GrugControlWire.callRequest(3, GrugControlWire.CALL_PLAY_UI_SOUND, "x", 2, 1),
                    GrugControlWire.callRequest(3, GrugControlWire.CALL_PLAY_UI_SOUND, "x", 1, 0.1),
                    GrugControlWire.callRequest(3, GrugControlWire.CALL_HAS_SOUND, "Bad Name", 0, 0)
                }) {
            loop.client.onMessage(bad);
            GrugControlWire.Message answer =
                    GrugControlWire.decode(loop.toServer.get(loop.toServer.size() - 1));
            assertEquals(GrugControlWire.ANSWER_ERROR, answer.type);
            assertTrue(answer.text.startsWith("refused: "), answer.text);
        }

        GrugControlServer.ControlException e =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> loop.server.hasSound("random.click"));
        assertTrue(e.getMessage().contains("the client's call failed"), e.getMessage());
        assertTrue(e.getMessage().contains("no sound engine"), e.getMessage());
        assertTrue(GrugControlServer.failed());
    }

    @Test
    void anAnswerValueOutsideTheAgreedOnesFailsTheCall() {
        int[] reply = {0};
        GrugControlServer[] server = new GrugControlServer[1];
        GrugControlServer.Link link =
                new GrugControlServer.Link() {
                    @Override
                    public boolean sendToStarter(Object starter, byte[] message) {
                        long id = GrugControlWire.decode(message).id;
                        server[0].onMessage(
                                PLAYER, GrugControlWire.answerValue(id, (byte) reply[0]));
                        return true;
                    }

                    @Override
                    public void flushWorld() {}

                    @Override
                    public boolean mayStartRun(Object sender) {
                        return true;
                    }
                };
        server[0] = new GrugControlServer(link, 1000);
        server[0].onMessage(PLAYER, GrugControlWire.runStart());

        GrugControlServer.clearFailure();
        reply[0] = 1;
        try {
            assertTrue(server[0].hasSound("random.click"));
            server[0].playUiSound("random.click", 1, 1);
            reply[0] = 0;
            assertFalse(server[0].hasSound("random.click"));
        } catch (GrugControlServer.ControlException e) {
            throw new AssertionError(e);
        }
        assertFalse(GrugControlServer.failed());

        reply[0] = 2;
        GrugControlServer.ControlException has =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> server[0].hasSound("random.click"));
        assertTrue(has.getMessage().contains("not 0 or 1"), has.getMessage());
        assertTrue(GrugControlServer.failed());

        GrugControlServer.clearFailure();
        reply[0] = 0;
        GrugControlServer.ControlException play =
                assertThrows(
                        GrugControlServer.ControlException.class,
                        () -> server[0].playUiSound("random.click", 1, 1));
        assertTrue(play.getMessage().contains("not the ack 1"), play.getMessage());
    }
}
