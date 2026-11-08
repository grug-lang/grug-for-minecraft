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
     * there when it holds no space. An empty line is nothing to show rather than an empty message,
     * and no piece is empty either, since every caller sends each one as a chat line.
     *
     * <p>A piece never ends in a space or in a section sign. 1.2.5 hands this list straight to its
     * chat, and its {@code FontRenderer.sizeStringToWidth} has no break in its space case: a space
     * falls into its colour-code case, which reads the character after every space and every sign
     * it reaches without checking the piece's end, so a piece whose last character is either one
     * throws. A colour code is the sign and the character after it, so a piece that would end in a
     * sign ends before the run of signs instead, leaving the code whole at the start of the next
     * piece, and a run at the end of a line or of the text is dropped, since a piece ending in two
     * signs throws exactly as one ending in a lone sign does. See #1215 and #1224.
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
                if (split == 0) {
                    if (rest.charAt(0) == ' ') {
                        // A word gap at the line's head leaves a piece with nothing in it, which a
                        // chat is never handed, so the gap is not part of the line.
                        rest = rest.trim();
                        continue;
                    }
                    // The head is a run of signs that no piece may end in and that the next gap
                    // does not reach past. The run moves to the head of the piece cut at the
                    // width, and a run longer than a whole piece goes, as one at a piece's end
                    // does.
                    split = cutAtOrBefore(rest, LINE_WIDTH);
                    if (split == 0) {
                        rest = withoutLeadingSectionSigns(rest);
                        continue;
                    }
                }
                addPiece(pieces, rest.substring(0, split));
                rest = rest.substring(split).trim();
            }
            if (!rest.isEmpty()) {
                addPiece(pieces, rest);
            }
        }
        return pieces;
    }

    /** Adds {@code text} as the piece a chat gets, unless nothing of it is left to show. */
    private static void addPiece(List<String> pieces, String text) {
        String piece = piece(text);
        if (!piece.isEmpty()) {
            pieces.add(piece);
        }
    }

    /**
     * {@code text} as the piece a chat is handed: no space it ends in and no run of section signs
     * it ends in. A space can be a sign's character and dropping a sign can uncover a space, so
     * both are applied until neither leaves anything to fix.
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
     * The last offset at or before {@code limit} that a chat may cut {@code text} at: never the one
     * right after a section sign, whose run of signs would end the piece before it. The run moves
     * whole to the next piece, so its code stays with it.
     */
    private static int cutAtOrBefore(String text, int limit) {
        int cut = Math.min(limit, text.length());
        while (cut > 0 && text.charAt(cut - 1) == SECTION_SIGN) {
            cut--;
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

    /** {@code text} from its first character that is not a section sign. */
    private static String withoutLeadingSectionSigns(String text) {
        int at = 0;
        while (at < text.length() && text.charAt(at) == SECTION_SIGN) {
            at++;
        }
        return text.substring(at);
    }
}
