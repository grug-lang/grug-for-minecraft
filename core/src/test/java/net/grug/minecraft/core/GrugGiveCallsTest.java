package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugTestState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
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

        /** Whether the player is an operator. */
        boolean operator = true;

        /** Whether this process is a client joined to a server, which the give names itself to. */
        boolean joined = false;

        final List<String> asked = new ArrayList<>();

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
                                    case "isOperator":
                                        return operator;
                                    case "askServerToGive":
                                        if (joined) asked.add(args[0] + "x" + args[1]);
                                        return joined;
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

    /**
     * Whether a run was active before this test, put back afterwards. A dropped give is reported as
     * the running test's error, so the tests here choose the run state instead of inheriting
     * whatever the last test class left.
     */
    private boolean savedRunActive;

    @BeforeEach
    void startWithNoRunActive() {
        savedRunActive = GrugTestState.testRunActive;
        GrugTestState.testRunActive = false;
        GrugErrors.takeHostFunctionException();
    }

    @AfterEach
    void restoreTheRunAndWhatWasReported() {
        GrugTestState.testRunActive = savedRunActive;
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
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
    void aGiveQueuedWhileItsTestRunsAppliesOnce() {
        List<String> applied = new ArrayList<>();

        // The give captured the reset count 3 and nothing has reset since, so this give is the
        // test's own and lands exactly once, which is the arm a normal give takes.
        GrugGiveCalls.applyQueuedGiveIfCurrent(3, 3, () -> applied.add("minecraft:stone"));

        assertEquals(List.of("minecraft:stone"), applied);
    }

    @Test
    void aGiveQueuedAsItsTestEndedIsDroppedByTheNextTestsReset() {
        List<String> applied = new ArrayList<>();

        // The give captured 3 but the runner reset in between, which clears the inventory for the
        // next test: applying it now would leak the item into a test that never asked for it, so it
        // never runs (#1144). This is the arm the game run reaches only when the give-queue tests
        // land in one order, and the unit test is its deterministic proof.
        GrugGiveCalls.applyQueuedGiveIfCurrent(3, 4, () -> applied.add("minecraft:stone"));

        assertTrue(applied.isEmpty(), "a give the next test's reset dropped is not applied");
    }

    @Test
    void anItemTheClientCannotNameIsReportedAndNothingIsGiven() {
        Items items = new Items();
        List<byte[]> sent = joinedClient(items);
        Object nameless = new Object();

        String report = giveAndReadReport(items, nameless);

        // No name identifies the item, so no give is sent and nothing is written to a copy the
        // server would overwrite; the call is an err instead, which is what the printed report is.
        assertTrue(sent.isEmpty());
        assertTrue(items.given.isEmpty());
        assertTrue(report.contains("cannot name the item"), report);
    }

    @Test
    void anItemTheClientNamesAsTheEmptyStringIsReportedAndNothingIsGiven() {
        Items items = new Items();
        List<byte[]> sent = joinedClient(items);
        Object emptyNamed = items.add("");

        String report = giveAndReadReport(items, emptyNamed);

        // A loader whose item names itself as nothing has not named it either: the server would
        // find no item under the empty name, so this is the same report as an item with no name.
        assertTrue(sent.isEmpty());
        assertTrue(items.given.isEmpty());
        assertTrue(report.contains("cannot name the item"), report);
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
    void theServerScanSkipsASlotItsListHoldsNoItemIn() {
        Items items = new Items();
        items.list.add(null);
        Object dirt = items.add("minecraft:dirt");

        // A list can hold an empty slot, which names nothing: the scan goes past it rather than
        // treating it as an item or stopping at it, so the give still finds the item after it.
        assertEquals(dirt, GrugGiveCalls.itemNamed(items.adapter(), "minecraft:dirt"));
    }

    @Test
    void theDefaultLinkGiveGivesTheNamedItemToTheServersOwnPlayer() throws Exception {
        Items items = new Items();
        items.add("minecraft:stone");
        items.add("minecraft:dirt");

        // Every loader's link leaves applyGive inherited, so this is the give a joined client's
        // click performs: the server finds the item its own list names and gives it to its own
        // copy of the test player, in the count the client sent.
        GrugControlServer.Link link =
                new GrugControlServer.Link() {
                    @Override
                    public boolean sendToStarter(Object starter, byte[] message) {
                        throw new AssertionError("a give does not send");
                    }

                    @Override
                    public void flushWorld() {
                        throw new AssertionError("a give does not flush");
                    }

                    @Override
                    public boolean mayStartRun(Object sender) {
                        throw new AssertionError("a give does not start a run");
                    }

                    @Override
                    public void applyClientWork() {
                        throw new AssertionError("a give does not drain");
                    }
                };

        Field adapter = GrugCore.class.getDeclaredField("adapter");
        adapter.setAccessible(true);
        ModLoaderAdapter saved = (ModLoaderAdapter) adapter.get(null);
        adapter.set(null, items.adapter());
        try {
            link.applyGive("minecraft:dirt", 64);
        } finally {
            adapter.set(null, saved);
        }

        assertEquals(List.of("the player minecraft:dirtx64"), items.given);
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
        String dropped = giveOnServerAndReadReport(items, "minecraft:stone", 1);
        assertTrue(dropped.contains("no player has joined yet"), dropped);
        assertEquals(before + 1, GrugWorldRevision.current());
    }

    @Test
    void aGiveWithNoPlayerOrNoSuchItemIsReportedAsTheAskingTestsError() {
        Items items = new Items();
        items.add("minecraft:stone");

        // No player has joined yet, and a name the list does not hold, each drop the give rather
        // than give some other item. A test that clicked for an item would only see an empty hand,
        // so each drop is the error of that test, which is the bounded defect that fails its scope.
        items.player = null;
        String noPlayer = giveOnServerAndReadReport(items, "minecraft:stone", 1);
        items.player = "the player";
        String noItem = giveOnServerAndReadReport(items, "minecraft:gravel", 1);

        assertTrue(items.given.isEmpty());
        assertTrue(noPlayer.contains("no player has joined yet"), noPlayer);
        assertTrue(noItem.contains("minecraft:gravel"), noItem);
        assertTrue(noItem.contains("item list has no item"), noItem);
    }

    /**
     * Runs a server give as a test that is asking for it, and answers the error it reported. The
     * run has to be active for the record path: this drain runs between two of the test's calls,
     * with no call of its own to fail.
     */
    private static String giveOnServerAndReadReport(Items items, String name, int count) {
        GrugTestState.testRunActive = true;
        try {
            GrugGiveCalls.giveOnServer(items.adapter(), name, count);
        } finally {
            GrugTestState.testRunActive = false;
        }
        return GrugErrors.takeHostFunctionException();
    }

    /**
     * Makes this test's process a client that started a run, so a give names its item to the
     * server, and answers the list its messages are recorded in.
     */
    private static List<byte[]> joinedClient(Items items) {
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
        return sent;
    }

    /**
     * Runs a give with {@code System.out} captured, and answers the report it printed. The err a
     * reported give queues is dropped afterwards, so it cannot fail the test after this one.
     */
    private static String giveAndReadReport(Items items, Object item) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            GrugGiveCalls.give(items.adapter(), items.player, item, 1, "give_item");
        } finally {
            System.setOut(saved);
            synchronized (GrugErrors.runtimeErrorQueue) {
                GrugErrors.runtimeErrorQueue.clear();
            }
        }
        return out.toString(StandardCharsets.UTF_8);
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

    @Test
    void anOperatorsOwnGiveIsAppliedToThatPlayer() {
        Items items = new Items();
        items.add("minecraft:stone");
        items.add("minecraft:dirt");

        String refusal = GrugGiveCalls.giveToPlayer(items.adapter(), "bob", "minecraft:dirt", 3);

        assertNull(refusal);
        assertEquals(List.of("bob minecraft:dirtx3"), items.given);
    }

    @Test
    void aPlayerWhoIsNotAnOperatorIsRefusedWithAnErrorAndGivenNothing() {
        Items items = new Items();
        items.add("minecraft:stone");
        items.operator = false;

        String refusal = GrugGiveCalls.giveToPlayer(items.adapter(), "bob", "minecraft:stone", 1);

        assertEquals("You must be an operator to give yourself items.", refusal);
        assertTrue(items.given.isEmpty());
    }

    @Test
    void anItemTheServerDoesNotKnowIsRefusedByName() {
        Items items = new Items();
        items.add("minecraft:stone");

        String refusal = GrugGiveCalls.giveToPlayer(items.adapter(), "bob", "minecraft:gravel", 1);

        assertEquals("The server has no item named minecraft:gravel.", refusal);
        assertTrue(items.given.isEmpty());
    }

    @Test
    void aClientJoinedWithNoRunAsksItsServerAndGivesNothingItself() {
        Items items = new Items();
        Object stone = items.add("minecraft:stone");
        items.joined = true;
        // A client of no run, so a run an earlier test left active is not this process's.
        new GrugControlClient(
                noLink(), noCapture(), GrugControlClient.adapterCaller(items::adapter), -1, -1);

        GrugGiveCalls.give(items.adapter(), items.player, stone, 2, "give_item");

        assertEquals(List.of("minecraft:stonex2"), items.asked);
        assertTrue(items.given.isEmpty(), "the client's own copy is not written");
    }

    @Test
    void anItemWithNoNameIsGivenLocallyWithoutAskingAServer() {
        Items items = new Items();
        Object unnamed = new Object();
        Object emptyNamed = items.add("");
        items.joined = true;
        // A client of no run, so a run an earlier test left active is not this process's.
        new GrugControlClient(
                noLink(), noCapture(), GrugControlClient.adapterCaller(items::adapter), -1, -1);

        GrugGiveCalls.give(items.adapter(), items.player, unnamed, 1, "give_item");
        GrugGiveCalls.give(items.adapter(), items.player, emptyNamed, 1, "give_item");

        assertTrue(items.asked.isEmpty());
        assertEquals(2, items.given.size());
    }
}
