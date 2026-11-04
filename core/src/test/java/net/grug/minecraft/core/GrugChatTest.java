package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

class GrugChatTest {
    /** One line a chat was given, in the order the chat took it. */
    private static final class Line {
        private final String text;
        private final boolean error;

        Line(String text, boolean error) {
            this.text = text;
            this.error = error;
        }
    }

    /** Fifty characters, which is exactly what {@link GrugChat#LINE_WIDTH} lets through. */
    private static final String FIFTY = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwx";

    @AfterEach
    void clearQueues() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.clear();
        }
    }

    @Test
    void theRunStarterIsThePlayerWhoCausedTheWork() {
        Object starter = new Object();
        Object other = new Object();

        assertSame(starter, GrugChat.actingPlayer(Arrays.asList(other, starter), starter));
    }

    @Test
    void theOnlyConnectedPlayerIsTheAnswerWhenTheStarterLeft() {
        Object left = new Object();
        Object remaining = new Object();

        assertSame(remaining, GrugChat.actingPlayer(Collections.singletonList(remaining), left));
        assertSame(remaining, GrugChat.actingPlayer(Collections.singletonList(remaining), null));
    }

    @Test
    void noOnePlayerCanBeNamedWithoutAStarterAndWithSeveralPlayers() {
        assertNull(GrugChat.actingPlayer(Collections.emptyList(), null));
        assertNull(GrugChat.actingPlayer(Arrays.asList(new Object(), new Object()), null));
        assertNull(GrugChat.actingPlayer(Arrays.asList(new Object(), new Object()), new Object()));
    }

    @Test
    void errorsAreDrainedBeforePrintsAndFlagged() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.add("the first err");
            GrugErrors.runtimeErrorQueue.add("the second err");
        }
        synchronized (GrugErrors.printQueue) {
            GrugErrors.printQueue.add("PASS a-Mod-Test.grug");
        }

        List<Line> lines = new ArrayList<>();
        int drained = GrugChat.drain((text, error) -> lines.add(new Line(text, error)));

        assertEquals(3, drained);
        assertEquals(3, lines.size());
        assertEquals("the first err", lines.get(0).text);
        assertTrue(lines.get(0).error);
        assertEquals("the second err", lines.get(1).text);
        assertTrue(lines.get(1).error);
        assertEquals("PASS a-Mod-Test.grug", lines.get(2).text);
        assertFalse(lines.get(2).error);
        assertEquals(0, GrugErrors.runtimeErrorQueue.size());
        assertEquals(0, GrugErrors.printQueue.size());
    }

    @Test
    void anEmptyQueueDrainsNothing() {
        assertEquals(
                0,
                GrugChat.drain(
                        (text, error) -> {
                            throw new AssertionError(text);
                        }));
    }

    @Test
    void aLineThatFitsIsOnePiece() {
        assertEquals(Collections.singletonList(FIFTY), GrugChat.wrap(FIFTY));
        assertEquals(Collections.singletonList("short"), GrugChat.wrap("short"));
    }

    @Test
    void aLongLineIsWrappedAtItsLastSpaceThatFits() {
        String line = "The test script reported a runtime error and stopped early, see above";

        List<String> pieces = GrugChat.wrap(line);

        assertEquals(
                Arrays.asList(
                        "The test script reported a runtime error and", "stopped early, see above"),
                pieces);
        assertTrue(pieces.get(0).length() <= GrugChat.LINE_WIDTH);
        assertEquals(line, pieces.get(0) + " " + pieces.get(1));
    }

    @Test
    void aLongLineWithoutASpaceIsCutAtTheWidth() {
        String line = FIFTY + "yz";

        assertEquals(Arrays.asList(FIFTY, "yz"), GrugChat.wrap(line));
    }

    @Test
    void everyNewlineIsItsOwnLineAndAnEmptyOneIsNothing() {
        String text = "first\n\nsecond long enough to need a wrap at the last space that fits\n";

        assertEquals(
                Arrays.asList(
                        "first",
                        "second long enough to need a wrap at the last",
                        "space that fits"),
                GrugChat.wrap(text));
    }

    @Test
    void anEmptyReportIsNoPieces() {
        assertEquals(Collections.emptyList(), GrugChat.wrap(""));
    }

    @Test
    void aSpaceAtTheStartOfALongLineLeavesAnEmptyFirstPieceAndTrimsTheRest() {
        // The space is the last one inside the width, so the piece before it is empty and the rest
        // loses the space it was split at. A client has always sent it that way, and the server
        // wraps with the same helper so both read the same.
        assertEquals(Arrays.asList("", FIFTY), GrugChat.wrap(" " + FIFTY));
    }
}
