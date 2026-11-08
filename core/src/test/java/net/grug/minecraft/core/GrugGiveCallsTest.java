package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The give path from a script to the player: the local give singleplayer does, and the item the
 * client of a server-driven run names to the server instead (#1099).
 */
class GrugGiveCallsTest {
    /**
     * The parts of the adapter a give uses, and nothing else: a list of items by index, the player
     * a give goes to and the give itself. Any other call fails the test, which is how a give that
     * started asking the adapter something else is noticed.
     */
    private static final class Items {
        final List<Object> list = new ArrayList<>();
        final List<String> given = new ArrayList<>();
        Object player = "the player";

        ModLoaderAdapter adapter() {
            return (ModLoaderAdapter)
                    java.lang.reflect.Proxy.newProxyInstance(
                            ModLoaderAdapter.class.getClassLoader(),
                            new Class<?>[] {ModLoaderAdapter.class},
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "itemCount":
                                        return list.size();
                                    case "itemByIndex":
                                        int index = (Integer) args[0];
                                        return index >= 0 && index < list.size()
                                                ? list.get(index)
                                                : null;
                                    case "testPlayer":
                                        return player;
                                    case "giveItemCountToPlayer":
                                        given.add(
                                                args[0]
                                                        + " "
                                                        + list.indexOf(args[1])
                                                        + "x"
                                                        + args[2]);
                                        return null;
                                    default:
                                        throw new AssertionError(
                                                "a give does not ask " + method.getName());
                                }
                            });
        }
    }

    @Test
    void anItemIsNamedByItsIndexInTheLoadersList() {
        Items items = new Items();
        Object stone = new Object();
        Object dirt = new Object();
        items.list.add(stone);
        items.list.add(dirt);
        ModLoaderAdapter adapter = items.adapter();

        assertEquals(0, GrugGiveCalls.indexOf(adapter, stone));
        assertEquals(1, GrugGiveCalls.indexOf(adapter, dirt));
        assertEquals(-1, GrugGiveCalls.indexOf(adapter, new Object()));
        items.list.clear();
        assertEquals(-1, GrugGiveCalls.indexOf(adapter, stone), "no list has no index");
    }

    @Test
    void aProcessWithNoRunGivesThroughItsOwnAdapter() {
        Items items = new Items();
        Object stone = new Object();
        items.list.add(stone);
        // A client that never started a run is the singleplayer case: this process holds the
        // player, so the give is its own.
        new GrugControlClient(
                noLink(), noCapture(), GrugControlClient.adapterCaller(items::adapter), -1, -1);

        GrugGiveCalls.give(items.adapter(), items.player, stone, 1, "give_item");

        assertEquals(List.of("the player 0x1"), items.given);
    }

    @Test
    void aJoinedClientNamesTheItemToTheServerAndGivesNothingItself() {
        Items items = new Items();
        Object stone = new Object();
        Object dirt = new Object();
        items.list.add(stone);
        items.list.add(dirt);
        List<byte[]> sent = new ArrayList<>();
        GrugControlClient client =
                new GrugControlClient(
                        sent::add,
                        noCapture(),
                        GrugControlClient.adapterCaller(items::adapter),
                        -1,
                        -1);
        client.beginRun();
        sent.clear();

        GrugGiveCalls.give(items.adapter(), items.player, dirt, 64, "give_item_stack");

        assertEquals(1, sent.size());
        GrugControlWire.Message give = GrugControlWire.decode(sent.get(0));
        assertEquals(GrugControlWire.GIVE_REQUEST, give.type);
        assertEquals(1, give.x1);
        assertEquals(64, give.y1);
        assertTrue(items.given.isEmpty(), "the client's own copy is not written");
    }

    @Test
    void anItemTheListCannotNameIsReportedAndNothingIsGiven() {
        Items items = new Items();
        List<byte[]> sent = new ArrayList<>();
        GrugControlClient client =
                new GrugControlClient(
                        sent::add,
                        noCapture(),
                        GrugControlClient.adapterCaller(items::adapter),
                        -1,
                        -1);
        client.beginRun();
        sent.clear();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            GrugGiveCalls.give(items.adapter(), items.player, new Object(), 1, "give_item");
        } finally {
            System.setOut(saved);
            synchronized (GrugErrors.runtimeErrorQueue) {
                GrugErrors.runtimeErrorQueue.clear();
            }
        }

        // No index names the item, so no give is sent and nothing is written to a copy the server
        // would overwrite; the call is an err instead, which is what the printed report is.
        assertTrue(sent.isEmpty());
        assertTrue(items.given.isEmpty());
        assertTrue(
                out.toString(StandardCharsets.UTF_8).contains("is not in this loader's item list"));
    }

    @Test
    void theServerGivesWhatTheClientNamedToItsOwnTestPlayer() {
        Items items = new Items();
        items.list.add(new Object());

        GrugGiveCalls.giveOnServer(items.adapter(), 0, 64);

        assertEquals(List.of("the player 0x64"), items.given);
    }

    @Test
    void theServerGiveMovesTheWorldRevisionSoARememberedSettleAnswerCannotOutliveIt() {
        Items items = new Items();
        items.list.add(new Object());
        long before = GrugWorldRevision.current();

        // The give changes the player's inventory on the server, which is a world change like the
        // local give's. A settle answer the server remembered for the old revision must not hold
        // past it, or a capture after an overlay click takes a frame from before the sync.
        GrugGiveCalls.giveOnServer(items.adapter(), 0, 1);
        assertEquals(before + 1, GrugWorldRevision.current());

        // A give that was dropped changed nothing, so it moves nothing either.
        items.player = null;
        GrugGiveCalls.giveOnServer(items.adapter(), 0, 1);
        assertEquals(before + 1, GrugWorldRevision.current());
    }

    @Test
    void aGiveWithNoPlayerOrNoSuchItemIsDropped() {
        Items items = new Items();
        items.list.add(new Object());

        // No player has joined yet, and an index the list does not hold, each drop the give rather
        // than fail a test that has already asserted on something else.
        items.player = null;
        GrugGiveCalls.giveOnServer(items.adapter(), 0, 1);
        items.player = "the player";
        GrugGiveCalls.giveOnServer(items.adapter(), 7, 1);

        assertTrue(items.given.isEmpty());
    }

    private static GrugControlClient.Link noLink() {
        return message -> {
            throw new AssertionError("nothing was expected to be sent");
        };
    }

    private static GrugControlClient.Capturer noCapture() {
        return (eye, x1, y1, x2, y2, done, failed) -> {
            throw new AssertionError("no capture was expected");
        };
    }
}
