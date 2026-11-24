package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

class GrugHandlerLookupTest {
    private static final String WRAPPER = GrugHandlerLookupTest.Wrapper.class.getName();

    private static class Conn {}

    private static class Wrapper {
        private final Object other = "unrelated";
        private final Conn conn;

        Wrapper(Conn conn) {
            this.conn = conn;
        }
    }

    private static class Unrelated {
        private final String name = "x";
    }

    private static class Direct {
        private final String name = "x";
        private final Conn conn;

        Direct(Conn conn) {
            this.conn = conn;
        }
    }

    private static class Inherits extends Direct {
        Inherits(Conn conn) {
            super(conn);
        }
    }

    private static class Wrapping {
        private final Wrapper first;
        private final Wrapper second;

        Wrapping(Wrapper first, Wrapper second) {
            this.first = first;
            this.second = second;
        }
    }

    @Test
    void aConnectionIsFoundOnTheFirstLevelOrInASuperclass() {
        Conn conn = new Conn();
        assertSame(conn, GrugHandlerLookup.connectionOf(new Direct(conn), Conn.class, WRAPPER, 0));
        assertSame(
                conn, GrugHandlerLookup.connectionOf(new Inherits(conn), Conn.class, WRAPPER, 0));
        assertNull(GrugHandlerLookup.connectionOf(new Direct(null), Conn.class, WRAPPER, 0));
    }

    @Test
    void noHolderAndNoFieldOfTheTypeGiveNull() {
        assertNull(GrugHandlerLookup.connectionOf(null, Conn.class, WRAPPER, 1));
        assertNull(GrugHandlerLookup.connectionOf(new Object(), Conn.class, WRAPPER, 1));
        assertNull(GrugHandlerLookup.connectionOf(new Unrelated(), Conn.class, WRAPPER, 1));
    }

    @Test
    void aWrapperIsWalkedOnlyWhileDepthIsLeftAndTheFirstNonNullResultWins() {
        Conn conn = new Conn();
        Wrapping wrapping = new Wrapping(new Wrapper(null), new Wrapper(conn));
        assertNull(GrugHandlerLookup.connectionOf(wrapping, Conn.class, WRAPPER, 0));
        // The first wrapper's connection is null, which the walk goes past into the second.
        assertNull(
                GrugHandlerLookup.connectionOf(new Wrapping(null, null), Conn.class, WRAPPER, 1));
        assertSame(conn, GrugHandlerLookup.connectionOf(wrapping, Conn.class, WRAPPER, 1));
        // The inner walk has no depth left, so a wrapper in a wrapper is not entered.
        assertNull(
                GrugHandlerLookup.connectionOf(
                        new Wrapping(new Wrapper(null), null), Conn.class, WRAPPER, 1));
    }

    private static class Player {}

    private static class Handler {
        private final Object other = "x";
        private final Player player;

        Handler(Player player) {
            this.player = player;
        }
    }

    private static class SubHandler extends Handler {
        SubHandler(Player player) {
            super(player);
        }
    }

    /** A candidate that holds a handler in a field, and nothing else of the Player type. */
    private static class Candidate extends Player {
        private final int number = 3;
        private final Object held;

        Candidate(Object held) {
            this.held = held;
        }
    }

    @Test
    void aPlayerFieldOfTheHandlerOrItsSuperclassIsTakenFirst() {
        Player player = new Player();
        assertSame(
                player,
                GrugHandlerLookup.playerOf(
                        new Handler(player), Player.class, Collections.emptyList()));
        assertSame(
                player,
                GrugHandlerLookup.playerOf(
                        new SubHandler(player), Player.class, Collections.emptyList()));
    }

    @Test
    void aNullPlayerFieldFallsBackToTheCandidateThatHoldsTheHandler() {
        Handler handler = new Handler(null);
        Candidate other = new Candidate("not the handler");
        Candidate holder = new Candidate(handler);
        assertSame(
                holder,
                GrugHandlerLookup.playerOf(handler, Player.class, Arrays.asList(other, holder)));
    }

    @Test
    void noCandidateHoldingTheHandlerGivesNull() {
        Handler handler = new Handler(null);
        assertNull(GrugHandlerLookup.playerOf(handler, Player.class, Collections.emptyList()));
        assertNull(
                GrugHandlerLookup.playerOf(
                        handler,
                        Player.class,
                        Arrays.asList(new Candidate("x"), new Candidate(null))));
    }

    private static class Own {
        private final Object other = "x";
        private final Handler handler;
        private final Handler second;

        Own(Handler handler, Handler second) {
            this.handler = handler;
            this.second = second;
        }
    }

    private static class SubOwn extends Own {
        SubOwn(Handler handler, Handler second) {
            super(handler, second);
        }
    }

    @Test
    void theFirstNonNullConnectionOfAHandlerFieldWins() {
        Handler first = new Handler(new Player());
        Handler second = new Handler(null);
        java.util.function.Function<Object, Object> id = value -> value == second ? "second" : null;
        assertEquals(
                "second",
                GrugHandlerLookup.nestedConnectionOf(new Own(first, second), Handler.class, id));
        assertEquals(
                "second",
                GrugHandlerLookup.nestedConnectionOf(new SubOwn(first, second), Handler.class, id));
        assertNull(GrugHandlerLookup.nestedConnectionOf(new Own(null, null), Handler.class, id));
        assertNull(GrugHandlerLookup.nestedConnectionOf(new Unrelated(), Handler.class, id));
    }
}
