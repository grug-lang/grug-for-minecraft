package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugErrors;

import java.util.ArrayList;
import java.util.List;

/**
 * What grug says to a player outside the log: the queues a client drains into its one chat every
 * tick, and the rules a dedicated server needs instead, because it has one chat per player (#165).
 *
 * <p>A client has a single chat, so its hook drains both queues straight into it. A server has no
 * such chat: a report belongs to the player whose work raised it, and the only player grug can name
 * that way is the run's starter, because a run is started by one player and every client call the
 * server forwards is theirs. With no run, the only connected player is the only candidate. With
 * several connected and no run, no one player can be named and the report stays in the log.
 *
 * <p>The rules live in core so the unit tests reach them: only the dispatch-only multiplayer job
 * launches a dedicated server.
 */
public final class GrugChat {
    /**
     * The width grug wraps a chat line to, in characters. The chat window decides how much fits and
     * this game clips a longer line rather than wrapping it, so a report is wrapped before it is
     * sent, on either side.
     */
    public static final int LINE_WIDTH = 50;

    /** The sign a chat reads as the start of a colour code, whose character follows it. */
    private static final char SECTION_SIGN = '\u00a7';

    private GrugChat() {}

    /** One chat, which takes a line of grug's text and whether grug reported it as an error. */
    public interface Chat {
        void line(String text, boolean error);
    }

    /**
     * The player a report is for: {@code starter} while it is connected, otherwise the only
     * connected player, otherwise null.
     *
     * <p>Untyped like the other player handles, because the loader's list holds the game's own
     * class and a client must not be made to load it.
     */
    public static Object actingPlayer(List<?> connected, Object starter) {
        if (starter != null && connected.contains(starter)) {
            return starter;
        }
        return connected.size() == 1 ? connected.get(0) : null;
    }

    /**
     * Drains grug's queues into {@code chat}, errors before prints, answering how many messages it
     * took. A message is one report as grug made it, newlines and all; {@link #wrap} is what turns
     * one into the lines a chat can show.
     */
    public static int drain(Chat chat) {
        int drained = 0;
        synchronized (GrugErrors.runtimeErrorQueue) {
            while (!GrugErrors.runtimeErrorQueue.isEmpty()) {
                chat.line(GrugErrors.runtimeErrorQueue.poll(), true);
                drained++;
            }
        }
        synchronized (GrugErrors.printQueue) {
            while (!GrugErrors.printQueue.isEmpty()) {
                chat.line(GrugErrors.printQueue.poll(), false);
                drained++;
            }
        }
        return drained;
    }

    /**
     * One report in the pieces a chat window can show: one line per newline, each whole when it
     * fits and otherwise wrapped at its last space inside {@link #LINE_WIDTH} characters, or cut
     * there when it holds no space. An empty line is nothing to show rather than an empty message.
     *
     * <p>A piece never ends in a space, even where a doubled space would put one at the cut. 1.2.5
     * hands this list straight to its chat, and its {@code FontRenderer.sizeStringToWidth} has no
     * break in its space case, so it falls into its colour-code case, steps one character past the
     * end of a piece that ends in a space and throws. See #1215.
     *
     * <p>A piece never ends in a section sign either, for that same fallthrough: the colour-code
     * case reads the character after the sign without checking the piece's end, so a sign whose
     * character is in the next piece, or in none at all, throws the same way. A colour code is two
     * characters, so a piece that would end between them ends before its sign instead, leaving the
     * code whole at the start of the next piece, and a sign with no character after it is dropped.
     * See #1224.
     */
    public static List<String> wrap(String text) {
        List<String> pieces = new ArrayList<>();
        for (String line : text.split("\n")) {
            String rest = line;
            while (rest.length() > LINE_WIDTH) {
                int split = rest.lastIndexOf(' ', LINE_WIDTH);
                if (split < 0) {
                    split = LINE_WIDTH;
                }
                split = cutAtOrBefore(rest, split);
                if (split == 0 && rest.charAt(0) != ' ') {
                    // The last space that fits is a colour code's own character, so it is not a
                    // word gap and cutting before it would take nothing out of the line. Cut at
                    // the width instead, where the sign takes at most one character off.
                    split = cutAtOrBefore(rest, LINE_WIDTH);
                }
                pieces.add(piece(rest.substring(0, split)));
                rest = rest.substring(split).trim();
            }
            if (!rest.isEmpty()) {
                pieces.add(piece(rest));
            }
        }
        return pieces;
    }

    /**
     * {@code text} as the piece a chat is handed: no space it ends in and no section sign it ends
     * in that names no character. A space can be a section sign's character and dropping a sign can
     * uncover a space, so both are applied until neither leaves anything to fix.
     */
    private static String piece(String text) {
        String piece = text;
        while (true) {
            String trimmed = withoutTrailingSpaces(piece);
            String whole = trimmed.substring(0, cutAtOrBefore(trimmed, trimmed.length()));
            if (whole.length() == piece.length()) {
                return whole;
            }
            piece = whole;
        }
    }

    /**
     * The last offset at or before {@code limit} that a chat may cut {@code text} at: after a whole
     * colour code, never between its section sign and the character that names it, and never after
     * a section sign that names no character at all.
     */
    private static int cutAtOrBefore(String text, int limit) {
        int cut = 0;
        int at = 0;
        while (at < text.length()) {
            boolean code = text.charAt(at) == SECTION_SIGN && at + 1 < text.length();
            int next = code ? at + 2 : at + 1;
            if (next > limit) {
                break;
            }
            at = next;
            if (code || text.charAt(next - 1) != SECTION_SIGN) {
                cut = next;
            }
        }
        return cut;
    }

    /** {@code piece} without the spaces it ends in, which a chat must never be handed. */
    private static String withoutTrailingSpaces(String piece) {
        int end = piece.length();
        while (end > 0 && piece.charAt(end - 1) == ' ') {
            end--;
        }
        return piece.substring(0, end);
    }
}
