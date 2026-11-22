package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference JEI's search of the item list, apart from the game, so a unit test enters every arm
 * (#865). It follows {@code IngredientFilter}: the text is lower cased and split on {@code |} into
 * alternatives, each alternative is split into tokens that are a quoted run or a run of non-space
 * characters, a token that starts with {@code -} must not match, and an item is listed when it
 * matches every other token of at least one alternative. A token that starts with {@code @} is
 * searched in the item's mod id and any other token in its name, as a substring, which is what the
 * reference's suffix trees answer. The list's own order is kept.
 */
public final class GrugItemSearch {
    private static final Pattern TOKEN = Pattern.compile("(-?\".*?(?:\"|$)|\\S+)");

    @GrugGenerated("utility class: never instantiated")
    private GrugItemSearch() {}

    /** What the search reads of one item: its lower case name and its lower case mod id. */
    public interface Names {
        /** The number of items. */
        int count();

        /** The item's display name, in lower case. */
        String name(int index);

        /** The item's mod id, in lower case. */
        String modId(int index);
    }

    /** One token of an alternative: the text to find, and whether it is searched in the mod id. */
    private static final class Token {
        final String text;
        final boolean modId;

        Token(String text, boolean modId) {
            this.text = text;
            this.modId = modId;
        }
    }

    /** The indexes of the items the text lists, in list order. */
    public static List<Integer> search(String text, Names names) {
        List<List<Token>> keep = new ArrayList<>();
        List<List<Token>> remove = new ArrayList<>();
        for (String alternative : text.toLowerCase(Locale.ROOT).split("\\|")) {
            List<Token> toSearch = new ArrayList<>();
            List<Token> toRemove = new ArrayList<>();
            Matcher matcher = TOKEN.matcher(alternative);
            while (matcher.find()) {
                String token = matcher.group(1);
                boolean negated = token.startsWith("-");
                if (negated) {
                    token = token.substring(1);
                }
                token = token.replace("\"", "");
                Token parsed = parse(token);
                if (parsed != null) {
                    (negated ? toRemove : toSearch).add(parsed);
                }
            }
            if (!toSearch.isEmpty()) {
                keep.add(toSearch);
                remove.add(toRemove);
            }
        }

        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < names.count(); index++) {
            if (keep.isEmpty() || listed(index, keep, remove, names)) {
                result.add(index);
            }
        }
        return result;
    }

    /** The reference's prefix parser: {@code @} is the mod id, a lone prefix is no token. */
    private static Token parse(String token) {
        if (token.isEmpty()) {
            return null;
        }
        if (token.charAt(0) != '@') {
            return new Token(token, false);
        }
        if (token.length() == 1) {
            return null;
        }
        return new Token(token.substring(1), true);
    }

    private static boolean listed(
            int index, List<List<Token>> keep, List<List<Token>> remove, Names names) {
        for (int alternative = 0; alternative < keep.size(); alternative++) {
            if (all(index, keep.get(alternative), names)
                    && !any(index, remove.get(alternative), names)) {
                return true;
            }
        }
        return false;
    }

    private static boolean all(int index, List<Token> tokens, Names names) {
        for (Token token : tokens) {
            if (!matches(index, token, names)) {
                return false;
            }
        }
        return true;
    }

    private static boolean any(int index, List<Token> tokens, Names names) {
        for (Token token : tokens) {
            if (matches(index, token, names)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(int index, Token token, Names names) {
        String haystack = token.modId ? names.modId(index) : names.name(index);
        return haystack.contains(token.text);
    }

    /**
     * The mod id of an item from the name its loader gives it: the part before the colon of a
     * registry key, {@code minecraft} for Alpha's {@code vanilla} and for a name with no colon,
     * which is a version that predates registry keys.
     */
    public static String modIdOf(String registryName) {
        if (registryName == null) {
            return "minecraft";
        }
        int colon = registryName.indexOf(':');
        if (colon <= 0) {
            return "minecraft";
        }
        String namespace = registryName.substring(0, colon).toLowerCase(Locale.ROOT);
        return namespace.equals("vanilla") ? "minecraft" : namespace;
    }
}
