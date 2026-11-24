package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The race arms of a control link, as pure decisions. */
class GrugLinkArmsTest {
    @Test
    void aSenderWhosePlayerIsFoundIsKnown() {
        assertTrue(GrugLinkArms.known("player"));
        assertFalse(GrugLinkArms.known(null));
    }

    @Test
    void deliverSendsToAPlayerThatIsHere() {
        List<String> sent = new ArrayList<>();

        assertTrue(GrugLinkArms.deliver("a", sent::add));
        assertEquals(Arrays.asList("a"), sent);
    }

    @Test
    void deliverSendsNothingToAPlayerThatLeft() {
        List<String> sent = new ArrayList<>();

        assertFalse(GrugLinkArms.deliver((String) null, sent::add));
        assertTrue(sent.isEmpty());
    }

    @Test
    void deliverIfSendsOnlyWhenHere() {
        List<String> sent = new ArrayList<>();

        assertTrue(GrugLinkArms.deliverIf(true, "t", sent::add));
        assertFalse(GrugLinkArms.deliverIf(false, "u", sent::add));
        assertEquals(Arrays.asList("t"), sent);
    }

    @Test
    void aPlayerIsStillHereOnlyWhenKnownInAnExistingWorldThatHoldsThem() {
        List<String> world = new ArrayList<>(Arrays.asList("a", "b"));

        assertTrue(GrugLinkArms.stillHere("a", world, w -> w));
        assertFalse(GrugLinkArms.stillHere("c", world, w -> w));
        assertFalse(GrugLinkArms.stillHere(null, world, w -> w));
        assertFalse(GrugLinkArms.stillHere("a", (List<String>) null, w -> w));
        assertFalse(GrugLinkArms.stillHere(null, (List<String>) null, w -> w));
    }

    @Test
    void aPlayerIsStillConnectedOnlyWhenKnownAndTheServerHasThem() {
        assertTrue(GrugLinkArms.stillConnected("a", p -> true));
        assertFalse(GrugLinkArms.stillConnected("a", p -> false));
        assertFalse(GrugLinkArms.stillConnected((String) null, p -> true));
    }

    @Test
    void drainPollsUntilTheQueueIsEmpty() {
        AtomicInteger left = new AtomicInteger(3);

        GrugLinkArms.drain("server", () -> left.getAndDecrement() > 0);

        assertEquals(-1, left.get());
    }

    @Test
    void drainWithNoOwnerPollsNothing() {
        AtomicInteger polls = new AtomicInteger();

        GrugLinkArms.drain(
                null,
                () -> {
                    polls.incrementAndGet();
                    return false;
                });

        assertEquals(0, polls.get());
    }

    @Test
    void applyToActsOnlyOnAnInstanceOfTheType() {
        List<String> seen = new ArrayList<>();

        GrugLinkArms.applyTo("s", String.class, seen::add);
        GrugLinkArms.applyTo(5, String.class, seen::add);
        GrugLinkArms.applyTo(null, String.class, seen::add);

        assertEquals(Arrays.asList("s"), seen);
    }

    @Test
    void keyOfFindsAnEntryByTheValueItself() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        Object a = new Object();
        map.put("k", a);
        assertEquals("k", GrugLinkArms.keyOf(map, a));
        assertEquals(null, GrugLinkArms.keyOf(map, new Object()));
        assertEquals(null, GrugLinkArms.keyOf(new java.util.HashMap<String, Object>(), a));
    }

    @Test
    void missingIsTheOtherSideOfKnown() {
        assertFalse(GrugLinkArms.missing("player"));
        assertTrue(GrugLinkArms.missing(null));
    }

    @Test
    void orElseAsksNextOnlyWhenFirstIsUnknown() {
        assertEquals("a", GrugLinkArms.orElse("a", () -> "b"));
        assertEquals("b", GrugLinkArms.orElse(null, () -> "b"));
    }

    @Test
    void learnRecordsOnlyAKnownPair() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        GrugLinkArms.learn(map, null, "v");
        GrugLinkArms.learn(map, "k", null);
        assertTrue(map.isEmpty());
        GrugLinkArms.learn(map, "k", "v");
        assertEquals("v", map.get("k"));
    }

    @Test
    void onDedicatedServerNeedsAnAdapterOfThatSide() {
        assertFalse(GrugLinkArms.onDedicatedServer(null, a -> GrugSide.DEDICATED_SERVER));
        assertFalse(GrugLinkArms.onDedicatedServer("a", a -> GrugSide.values()[0]));
        assertTrue(GrugLinkArms.onDedicatedServer("a", a -> GrugSide.DEDICATED_SERVER));
    }

    @Test
    void ifHereDoesNotAskWhenNotHere() {
        assertFalse(GrugLinkArms.ifHere(false, () -> true));
        assertFalse(GrugLinkArms.ifHere(true, () -> false));
        assertTrue(GrugLinkArms.ifHere(true, () -> true));
    }

    @Test
    void runIfChooseAndRunIfCiPickTheirArm() {
        AtomicInteger n = new AtomicInteger();
        GrugLinkArms.runIf(false, n::incrementAndGet);
        assertEquals(0, n.get());
        GrugLinkArms.runIf(true, n::incrementAndGet);
        assertEquals(1, n.get());
        GrugLinkArms.choose(true, n::incrementAndGet, () -> n.addAndGet(10));
        assertEquals(2, n.get());
        GrugLinkArms.choose(false, n::incrementAndGet, () -> n.addAndGet(10));
        assertEquals(12, n.get());
        GrugLinkArms.runIfCi(k -> "false", () -> n.set(0));
        GrugLinkArms.runIfCi(k -> null, () -> n.set(0));
        assertEquals(12, n.get());
        GrugLinkArms.runIfCi(k -> "GRUG_CI".equals(k) ? "true" : null, () -> n.set(0));
        assertEquals(0, n.get());
    }

    @Test
    void runIfWithRunsTheTaskOnItsThreeValuesOnlyWhenHere() {
        List<String> seen = new ArrayList<>();
        GrugLinkArms.runIfWith(false, "a", 1, 2.0, (a, b, c) -> seen.add(a + b + c));
        GrugLinkArms.runIfWith(true, "a", 1, 2.0, (a, b, c) -> seen.add(a + b + c));
        assertEquals(Arrays.asList("a12.0"), seen);
    }

    @Test
    void mapOrNullAndIfHereElseAndTableEntryAnswerWithoutTheMissingPart() {
        assertEquals(null, GrugLinkArms.mapOrNull((String) null, String::length));
        assertEquals(Integer.valueOf(2), GrugLinkArms.mapOrNull("ab", String::length));
        assertTrue(GrugLinkArms.bothHere(true, true));
        assertFalse(GrugLinkArms.bothHere(true, false));
        assertFalse(GrugLinkArms.bothHere(false, true));
        assertTrue(GrugLinkArms.ifHereElse(false, true, () -> false));
        assertFalse(GrugLinkArms.ifHereElse(false, false, () -> true));
        assertTrue(GrugLinkArms.ifHereElse(true, false, () -> true));
        assertFalse(GrugLinkArms.ifHereElse(true, true, () -> false));
        String[] table = {"air", "stone", null};
        assertEquals(null, GrugLinkArms.tableEntry(table, 0));
        assertEquals(null, GrugLinkArms.tableEntry(table, -1));
        assertEquals("stone", GrugLinkArms.tableEntry(table, 1));
        assertEquals(null, GrugLinkArms.tableEntry(table, 2));
        assertEquals(null, GrugLinkArms.tableEntry(table, 3));
    }

    @Test
    void deliverOrReportReportsAMissingValueAndSendsAPresentOne() {
        List<String> log = new ArrayList<>();
        GrugLinkArms.deliverOrReport(null, "none", log::add, v -> log.add("sent " + v));
        GrugLinkArms.deliverOrReport("p", "none", log::add, v -> log.add("sent " + v));
        assertEquals(Arrays.asList("none", "sent p"), log);
    }

    @Test
    void aRefusedCallIsReportedWithItsPrefixAndAnswersTheFallback() {
        List<String> log = new ArrayList<>();
        assertEquals(Integer.valueOf(7), GrugLinkArms.callOrReport(() -> 7, "x: ", 1, log::add));
        assertEquals(
                Integer.valueOf(1),
                GrugLinkArms.callOrReport(
                        () -> {
                            throw new GrugControlServer.ControlException("no");
                        },
                        "x: ",
                        1,
                        log::add));
        assertEquals(Arrays.asList("x: no"), log);
        GrugLinkArms.runOrReport(() -> log.add("ran"), "y: ", log::add);
        GrugLinkArms.runOrReport(
                () -> {
                    throw new GrugControlServer.ControlException("nope");
                },
                "y: ",
                log::add);
        assertEquals(Arrays.asList("x: no", "ran", "y: nope"), log);
    }

    @Test
    void forEachMissingHandsOnlyTheKeysThatAreNotPresent() {
        List<String> missing = new ArrayList<>();
        GrugLinkArms.forEachMissing(Arrays.asList("a", "b", "c"), Arrays.asList("b"), missing::add);
        assertEquals(Arrays.asList("a", "c"), missing);
    }

    @Test
    void chooseWithPassesTheArgumentToTheArmItPicks() {
        List<String> seen = new ArrayList<>();
        GrugLinkArms.chooseWith(
                true, "a", v -> seen.add("server " + v), v -> seen.add("other " + v));
        GrugLinkArms.chooseWith(
                false, "b", v -> seen.add("server " + v), v -> seen.add("other " + v));
        assertEquals(Arrays.asList("server a", "other b"), seen);
    }

    @Test
    void chooseWith2PassesBothArgumentsToTheArmItPicks() {
        List<String> seen = new ArrayList<>();
        GrugLinkArms.chooseWith2(
                true, "a", 1, (a, b) -> seen.add("server " + a + b), (a, b) -> seen.add("other"));
        GrugLinkArms.chooseWith2(
                false, "b", 2, (a, b) -> seen.add("server"), (a, b) -> seen.add("other " + a + b));
        assertEquals(Arrays.asList("server a1", "other b2"), seen);
    }

    @Test
    void runOnRunsTheTaskOnItsArgumentOnlyWhenHere() {
        List<String> seen = new ArrayList<>();
        GrugLinkArms.runOn(false, "a", seen::add);
        GrugLinkArms.runOn(true, "b", seen::add);
        assertEquals(Arrays.asList("b"), seen);
    }

    @Test
    void membersOrNoneIsEmptyWithoutAWorldAndACopyWithOne() {
        assertEquals(
                new ArrayList<Object>(), GrugLinkArms.membersOrNone((List<String>) null, w -> w));
        List<String> world = new ArrayList<>(Arrays.asList("p", "q"));
        List<Object> copy = GrugLinkArms.membersOrNone(world, w -> w);
        world.clear();
        assertEquals(Arrays.<Object>asList("p", "q"), copy);
    }

    @Test
    void matchingKeepsOnlyAKnownMenuWithTheSameWindowId() {
        assertEquals(null, GrugLinkArms.matching((String) null, 3, String::length));
        assertEquals(null, GrugLinkArms.matching("ab", 3, String::length));
        assertEquals("abc", GrugLinkArms.matching("abc", 3, String::length));
    }

    @Test
    void aRunStartsOnlyWhenAPlayerHasJoinedStartedItAndHasAReadyWorld() {
        AtomicInteger asked = new AtomicInteger();
        assertFalse(
                GrugLinkArms.startReady(
                        true,
                        () -> {
                            asked.incrementAndGet();
                            return true;
                        },
                        "p",
                        p -> true));
        assertEquals(0, asked.get());
        assertFalse(GrugLinkArms.startReady(false, () -> false, "p", p -> true));
        assertFalse(GrugLinkArms.startReady(false, () -> true, (String) null, p -> true));
        assertFalse(GrugLinkArms.startReady(false, () -> true, "p", p -> false));
        assertTrue(GrugLinkArms.startReady(false, () -> true, "p", p -> true));
    }

    @Test
    void theStarterLeftWhenNoOneIsHereOrTheyAreNotAmongThePlayers() {
        assertTrue(GrugLinkArms.starterLeft(new ArrayList<String>(), "a"));
        assertTrue(GrugLinkArms.starterLeft(Arrays.asList("b"), "a"));
        assertFalse(GrugLinkArms.starterLeft(Arrays.asList("b", "a"), "a"));
    }

    @Test
    void aMenuIsOpenWhenItIsKnownAndNotThePlayersOwn() {
        Object own = new Object();
        assertFalse(GrugLinkArms.otherMenuOpen(null, own));
        assertFalse(GrugLinkArms.otherMenuOpen(own, own));
        assertTrue(GrugLinkArms.otherMenuOpen(new Object(), own));
    }

    @Test
    void runUnlessRunsTheTaskOnlyWhenNotSkipped() {
        AtomicInteger ran = new AtomicInteger();
        GrugLinkArms.runUnless(true, ran::incrementAndGet);
        assertEquals(0, ran.get());
        GrugLinkArms.runUnless(false, ran::incrementAndGet);
        assertEquals(1, ran.get());
    }
}
