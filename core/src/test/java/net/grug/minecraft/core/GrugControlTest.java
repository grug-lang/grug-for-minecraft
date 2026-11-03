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
        boolean flushed = false;
        final List<byte[]> toServer = new ArrayList<>();

        @Override
        public boolean sendToStarter(Object starter, byte[] message) {
            if (!connected) return false;
            client.onMessage(message);
            return true;
        }

        @Override
        public void flushWorld() {
            flushed = true;
        }

        @Override
        public void send(byte[] message) {
            toServer.add(message);
            server.onMessage(PLAYER, message);
        }
    }

    private static Loopback wire(long timeoutMillis, int dropWidth, GrugControlClient.Capturer c) {
        Loopback loop = new Loopback();
        loop.server = new GrugControlServer(loop, timeoutMillis);
        loop.client = new GrugControlClient(loop, c, dropWidth);
        return loop;
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
    void onlyTheStarterMayAnswerAndALateAnswerIsDropped() {
        Loopback loop = wire(1000, -1, solid(0));
        loop.client.beginRun();

        String printed =
                capture(
                        () -> {
                            loop.server.onMessage("mallory", GrugControlWire.answerError(1, "x"));
                            loop.server.onMessage(PLAYER, GrugControlWire.answerError(99, "x"));
                            loop.server.onMessage("mallory", GrugControlWire.runStart());
                            loop.server.onMessage(PLAYER, new byte[] {99});
                            loop.server.onMessage(
                                    PLAYER, GrugControlWire.captureRequest(1, 0, 0, 1, 1));
                        });

        assertTrue(printed.contains("dropped an answer from mallory, who did not start"), printed);
        assertTrue(printed.contains("request 99, which is not waiting"), printed);
        assertTrue(printed.contains("ignored a run start from mallory"), printed);
        assertTrue(printed.contains("does not decode"), printed);
        assertTrue(printed.contains("dropped a message of type 2"), printed);
        assertEquals(PLAYER, loop.server.starter());
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
            {chunk(1, 5, 2, one)},
            {chunk(1, -1, 2, one)},
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
    void aTruncatedPngIsAnErrorAndSoIsOneThatIsNotAnImage() throws Exception {
        for (byte[] bad : new byte[][] {new byte[] {1, 2, 3}, truncatedPng()}) {
            GrugControlServer server =
                    lyingServer(GrugControlWire.answerChunks(1, bad).toArray(new byte[0][]));

            GrugControlServer.ControlException e =
                    assertThrows(
                            GrugControlServer.ControlException.class,
                            () -> server.capture(0, 0, 1, 1));

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
    void theClientDropsMessagesItCannotUse() {
        Loopback loop = wire(1000, -1, solid(0));

        String printed =
                capture(
                        () -> {
                            loop.client.onMessage(new byte[] {});
                            loop.client.onMessage(GrugControlWire.runStart());
                        });

        assertTrue(printed.contains("does not decode"), printed);
        assertTrue(printed.contains("dropped a server message of type 1"), printed);
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
}
