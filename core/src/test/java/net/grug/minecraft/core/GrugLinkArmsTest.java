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
}
