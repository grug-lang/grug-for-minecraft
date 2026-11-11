package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The give path from a script to the player: the local give singleplayer does, and the item the
 * client of a server-driven run names to the server instead (#1099).
 */
class GrugGiveCallsTest {
    /**
     * The parts of the adapter a give uses, and nothing else: a list of named items by index, the
     * player a give goes to and the give itself. Any other call fails the test, which is how a give
     * that started asking the adapter something else is noticed.
     */
    private static final class Items {
        final List<Object> list = new ArrayList<>();
        final Map<Object, String> names = new IdentityHashMap<>();
        final List<String> given = new ArrayList<>();
        Object player = "the player";

        Object add(String name) {
            Object item = new Object();
            list.add(item);
            names.put(item, name);
            return item;
        }

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
                                    case "itemName":
                                        return names.get(args[0]);
                                    case "testPlayer":
                                        return player;
                                    case "giveItemCountToPlayer":
                                        given.add(
                                                args[0] + " " + names.get(args[1]) + "x" + args[2]);
                                        return null;
                                    default:
                                        throw new AssertionError(
                                                "a give does not ask " + method.getName());
                                }
                            });
        }
    }

    @Test
    void anItemIsNamedByTheNameItsLoaderGivesIt() {
        Items items = new Items();
        Object stone = items.add("minecraft:stone");
        Object dirt = items.add("minecraft:dirt");
        ModLoaderAdapter adapter = items.adapter();

        assertEquals("minecraft:stone", adapter.itemName(stone));
        assertEquals("minecraft:dirt", adapter.itemName(dirt));
        assertNull(adapter.itemName(new Object()), "an item no loader made has no name");
    }

    @Test
    void aProcessWithNoRunGivesThroughItsOwnAdapter() {
        Items items = new Items();
        Object stone = items.add("minecraft:stone");
        // A client that never started a run is the singleplayer case: this process holds the
        // player, so the give is its own.
        new GrugControlClient(
                noLink(), noCapture(), GrugControlClient.adapterCaller(items::adapter), -1, -1);

        GrugGiveCalls.give(items.adapter(), items.player, stone, 1, "give_item");

        assertEquals(List.of("the player minecraft:stonex1"), items.given);
    }

    @Test
    void aJoinedClientNamesTheItemToTheServerAndGivesNothingItself() {
        Items items = new Items();
        items.add("minecraft:stone");
        Object dirt = items.add("minecraft:dirt");
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
        assertEquals("minecraft:dirt", give.text);
        assertEquals(64, give.x1);
        assertTrue(items.given.isEmpty(), "the client's own copy is not written");
    }

    @Test
    void anItemTheClientCannotNameIsReportedAndNothingIsGiven() {
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

        // No name identifies the item, so no give is sent and nothing is written to a copy the
        // server would overwrite; the call is an err instead, which is what the printed report is.
        assertTrue(sent.isEmpty());
        assertTrue(items.given.isEmpty());
        assertTrue(
                out.toString(StandardCharsets.UTF_8).contains("cannot name the item"),
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theServerGivesTheItemItsOwnListNames() {
        Items items = new Items();
        items.add("minecraft:stone");
        Object dirt = items.add("minecraft:dirt");

        GrugGiveCalls.giveOnServer(items.adapter(), "minecraft:dirt", 64);

        assertEquals(List.of("the player minecraft:dirtx64"), items.given);
        assertNull(GrugGiveCalls.itemNamed(items.adapter(), "minecraft:gravel"));
        assertEquals(dirt, GrugGiveCalls.itemNamed(items.adapter(), "minecraft:dirt"));
    }

    @Test
    void theServerGiveMovesTheWorldRevisionSoARememberedSettleAnswerCannotOutliveIt() {
        Items items = new Items();
        items.add("minecraft:stone");
        long before = GrugWorldRevision.current();

        // The give changes the player's inventory on the server, which is a world change like the
        // local give's. A settle answer the server remembered for the old revision must not hold
        // past it, or a capture after an overlay click takes a frame from before the sync.
        GrugGiveCalls.giveOnServer(items.adapter(), "minecraft:stone", 1);
        assertEquals(before + 1, GrugWorldRevision.current());

        // A give that was dropped changed nothing, so it moves nothing either.
        items.player = null;
        GrugGiveCalls.giveOnServer(items.adapter(), "minecraft:stone", 1);
        assertEquals(before + 1, GrugWorldRevision.current());
    }

    @Test
    void aGiveWithNoPlayerOrNoSuchItemIsDropped() {
        Items items = new Items();
        items.add("minecraft:stone");

        // No player has joined yet, and a name the list does not hold, each drop the give rather
        // than fail a test that has already asserted on something else.
        items.player = null;
        GrugGiveCalls.giveOnServer(items.adapter(), "minecraft:stone", 1);
        items.player = "the player";
        GrugGiveCalls.giveOnServer(items.adapter(), "minecraft:gravel", 1);

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
