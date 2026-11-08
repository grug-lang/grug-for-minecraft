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
    void noPieceEndsInASpaceBecauseTheOlderChatReadsPastOne() {
        // 1.2.5's FontRenderer.sizeStringToWidth has no break in its space case, so it falls into
        // its colour-code case, steps one character past the end of a piece that ends in a space
        // and throws StringIndexOutOfBoundsException. That crashed the 1.2.5 dedicated leg's
        // client on the compiler's "$  " source echo (#1215), which reached chat as the piece "$ "
        // with the error prefix in front of it.
        String path = "coverage/code/coverage_fixture_entity-BlockEntity.grug";
        String report =
                "Failed to hot-reload coverage_fixture_entity-BlockEntity.grug:\n"
                        + "Error: File is empty\n$  "
                        + path;
        for (String piece : GrugChat.wrap(report)) {
            assertFalse(piece.endsWith(" "), "a piece ends in a space: '" + piece + "'");
        }

        // The doubled space is what leaves a piece ending in one, and its distance from the start
        // is what decides whether the cut lands on it, so every count of spaces is tried.
        for (int spaces = 2; spaces <= 100; spaces++) {
            StringBuilder line = new StringBuilder("$");
            for (int space = 0; space < spaces; space++) {
                line.append(' ');
            }
            line.append(path);
            for (String piece : GrugChat.wrap(line.toString())) {
                assertFalse(piece.endsWith(" "), "a piece ends in a space: '" + piece + "'");
            }
        }
    }

    @Test
    void noPieceEndsInASectionSignBecauseTheOlderChatReadsPastOne() {
        // 1.2.5's FontRenderer.sizeStringToWidth falls from its space case into its colour-code
        // case, which reads the character after a section sign without checking the piece's end,
        // so a piece that ends in one throws StringIndexOutOfBoundsException. A colour code is its
        // sign and the character it names, so a cut never lands between them, and a sign with no
        // character after it is dropped (#1224).
        String tail = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";

        // The code is placed at every distance from the line's start, so every cut position from 0
        // to 60 meets it, the sign at LINE_WIDTH - 1 landing on the cut included.
        for (int offset = 0; offset <= 60; offset++) {
            StringBuilder line = new StringBuilder();
            for (int at = 0; at < offset; at++) {
                line.append('a');
            }
            line.append("§c").append(tail);

            List<String> pieces = GrugChat.wrap(line.toString());
            int whole = 0;
            for (String piece : pieces) {
                assertFalse(piece.endsWith("§"), "a piece ends in a section sign: '" + piece + "'");
                for (int at = piece.indexOf("§c"); at >= 0; at = piece.indexOf("§c", at + 1)) {
                    whole++;
                }
            }
            // The code is whole in one piece, so no cut took its sign from the character it names.
            assertEquals(1, whole, "the code lost its character: " + pieces);
        }

        // A sign at the end of the text names no character, so it is dropped instead of handed on.
        assertEquals(Arrays.asList("first", "second"), GrugChat.wrap("first\nsecond§"));
        // Dropping it must not leave the space before it at the end of the piece.
        assertEquals(Collections.singletonList("short"), GrugChat.wrap("short §"));
        // A space that is a code's own character is not a word gap, so it never ends a piece
        // either.
        StringBuilder spaceCode = new StringBuilder();
        for (int at = 0; at < GrugChat.LINE_WIDTH - 2; at++) {
            spaceCode.append('a');
        }
        spaceCode.append("§ ").append(tail);
        for (String piece : GrugChat.wrap(spaceCode.toString())) {
            assertFalse(piece.endsWith("§"), "a piece ends in a section sign: '" + piece + "'");
        }
    }

    @Test
    void noPieceEndsInASectionSignEvenWhenSeveralRunTogether() {
        // The older chat reads the character after every space and every section sign it reaches,
        // so only the piece's last character decides whether it throws: a piece ending in two
        // signs throws as one ending in a lone sign does. A run of signs ends no piece, then: it
        // moves whole to the next piece, or is dropped when a line or the text ends there (#1224).
        String tail = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz";

        assertEquals(Collections.singletonList("a"), GrugChat.wrap("a §§"));
        assertEquals(Collections.singletonList("§ca"), GrugChat.wrap("§ca §§"));
        assertEquals(Collections.emptyList(), GrugChat.wrap("§"));
        assertEquals(Collections.emptyList(), GrugChat.wrap("§§"));
        assertEquals(Collections.emptyList(), GrugChat.wrap(" §§"));
        assertEquals(Arrays.asList("a", "b"), GrugChat.wrap("a\n§\nb"));
        assertEquals(Collections.emptyList(), GrugChat.wrap(repeat("§", 60)));
        // A run longer than a piece goes and what follows it stays, which is what the run shows
        // as whole codes anyway.
        assertEquals(Collections.singletonList("hello"), GrugChat.wrap(repeat("§", 60) + "hello"));

        // A word gap inside the width right after "§§" used to put the pair at the end of the
        // piece. The pair moves whole to the head of the next piece instead.
        String line = repeat("a", 47) + " §§ " + tail;
        List<String> pieces = GrugChat.wrap(line);
        assertEquals(repeat("a", 47), pieces.get(0));
        int pairs = 0;
        for (String piece : pieces) {
            assertFalse(piece.isEmpty(), "a piece is empty");
            assertFalse(piece.endsWith("§"), "a piece ends in a section sign: '" + piece + "'");
            for (int at = piece.indexOf("§§"); at >= 0; at = piece.indexOf("§§", at + 1)) {
                pairs++;
            }
        }
        assertEquals(1, pairs, "the pair was cut apart: " + pieces);

        // A lone sign at every distance from the line's start meets every cut position as well.
        for (int offset = 0; offset <= 60; offset++) {
            String withSign = repeat("a", offset) + "§" + tail;
            for (String piece : GrugChat.wrap(withSign)) {
                assertFalse(piece.endsWith("§"), "a piece ends in a section sign: '" + piece + "'");
            }
        }
    }

    @Test
    void noPieceIsEmptyBecauseAChatWouldShowItAsABlankLine() {
        // An empty line is nothing to show, and neither is an empty piece: every caller sends each
        // piece as a chat line, so each empty one would be a blank line.
        assertEquals(Collections.emptyList(), GrugChat.wrap("§"));
        assertEquals(Collections.emptyList(), GrugChat.wrap("   "));
        assertEquals(Arrays.asList("a", "b"), GrugChat.wrap("a\n\n§\nb"));
        // The space is the last one inside the width, so the piece before it is empty; dropping it
        // leaves the line whole, which both sides wrap the same way.
        assertEquals(Collections.singletonList(FIFTY), GrugChat.wrap(" " + FIFTY));
    }

    @Test
    void aTrailingSpaceAtTheEndOfTheLastLineIsNotPartOfIt() {
        assertEquals(Collections.singletonList("short"), GrugChat.wrap("short "));
    }

    /** {@code text} repeated {@code times} times. */
    private static String repeat(String text, int times) {
        StringBuilder repeated = new StringBuilder();
        for (int at = 0; at < times; at++) {
            repeated.append(text);
        }
        return repeated.toString();
    }
}
