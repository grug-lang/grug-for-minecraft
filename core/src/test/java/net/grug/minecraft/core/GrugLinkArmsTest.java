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
}
