package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Covers the one GET grug makes, against a local server. */
class GrugHttpTest {
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/ok",
                exchange -> {
                    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.createContext(
                "/moved",
                exchange -> {
                    exchange.getResponseHeaders().add("Location", base + "ok");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        server.createContext(
                "/missing",
                exchange -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                });
        server.createContext(
                "/trickles",
                exchange -> {
                    exchange.sendResponseHeaders(200, 200);
                    try {
                        for (int i = 0; i < 200; i++) {
                            exchange.getResponseBody().write(1);
                            exchange.getResponseBody().flush();
                            Thread.sleep(25);
                        }
                    } catch (IOException | InterruptedException e) {
                        // The client gave up, which is what the test is for.
                    }
                    exchange.close();
                });
        server.createContext(
                "/stalls",
                exchange -> {
                    exchange.sendResponseHeaders(200, 100);
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void getsABody() throws IOException {
        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8),
                GrugHttp.urlConnection().get(base + "ok", 1000, bytes -> {}));
    }

    @Test
    void followsARedirect() throws IOException {
        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8),
                GrugHttp.urlConnection().get(base + "moved", 1000, bytes -> {}));
    }

    @Test
    void failsOnAStatusThatIsNotOk() {
        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugHttp.urlConnection().get(base + "missing", 1000, bytes -> {}));

        assertTrue(error.getMessage().contains("answered 404"), error.getMessage());
    }

    @Test
    void tellsHowManyBytesHaveArrived() throws IOException {
        java.util.List<Long> seen = new java.util.ArrayList<>();

        GrugHttp.urlConnection().get(base + "ok", 1000, seen::add);

        assertEquals(java.util.List.of(5L), seen);
    }

    @Test
    void theLimitIsTheSmallerOfTheCallersAndItsOwn() {
        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugHttp.urlConnection().get(base + "ok", 3, bytes -> {}));

        assertTrue(error.getMessage().contains("more than 3 bytes"), error.getMessage());
    }

    @Test
    void failsOnABodyOverTheLimit() {
        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugHttp.get(base + "ok", 1000, 1000, 10_000, 3, bytes -> {}));

        assertTrue(error.getMessage().contains("more than 3 bytes"), error.getMessage());
    }

    @Test
    void failsWhenTheServerTricklesPastTheTotalDeadline() {
        IOException error =
                assertThrows(
                        IOException.class,
                        () -> GrugHttp.get(base + "trickles", 1000, 1000, 300, 1000, bytes -> {}));

        assertTrue(error.getMessage().contains("took longer than 0.3 s"), error.getMessage());
    }

    @Test
    void theRealFetcherHasADeadlineOfItsOwn() throws IOException {
        assertTrue(GrugHttp.DEADLINE_MS > 0);
        GrugHttp.urlConnection().get(base + "ok", 1000, bytes -> {});
    }

    @Test
    void failsWhenTheServerStallsPastTheReadTimeout() {
        assertThrows(
                IOException.class,
                () -> GrugHttp.get(base + "stalls", 1000, 200, 10_000, 1000, bytes -> {}));
    }
}
