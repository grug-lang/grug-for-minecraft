package net.grug.minecraft.grug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The names a pre-registry version gives its vanilla blocks and items, each with the id it holds.
 *
 * <p>Alpha, Beta and 1.2.5 have no block or item registry, and 1.2.5's runtime is obfuscated, so
 * the other loaders' trick of reflecting over named fields does not work there: {@code Block.chest}
 * is a one-letter field at run time. Such a loader's build dumps the dev classes' names and ids
 * into a table instead, and this reads it back, one {@code B name id} or {@code I name id} line per
 * entry. Names are keyed by {@link #key}, which ignores word order, so {@code iron_ingot} matches
 * {@code ingotIron}; the build's own dump uses the same key.
 */
public final class GrugVanillaIds {

    private final Map<String, Integer> blockIds;
    private final Map<Integer, String> blockNames;
    private final Map<String, Integer> itemIds;

    private GrugVanillaIds(
            Map<String, Integer> blockIds,
            Map<Integer, String> blockNames,
            Map<String, Integer> itemIds) {
        this.blockIds = blockIds;
        this.blockNames = blockNames;
        this.itemIds = itemIds;
    }

    /**
     * Reads {@code table}.
     *
     * <p>{@code in} is null when the jar holds no such table, which is a broken build like a line
     * that is neither a block nor an item: it fails here rather than leaving a loader resolving
     * every name to nothing.
     */
    public static GrugVanillaIds read(String table, InputStream in) {
        if (in == null) {
            throw Grug.fatal("The grug jar holds no " + table);
        }
        Map<String, Integer> blockIds = new HashMap<>();
        Map<Integer, String> blockNames = new HashMap<>();
        Map<String, Integer> itemIds = new HashMap<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 3) {
                    throw Grug.fatal(
                            table
                                    + " has the line \""
                                    + line
                                    + "\", which is not a kind, a name and an id");
                }
                int id = Integer.parseInt(parts[2]);
                if ("B".equals(parts[0])) {
                    blockIds.put(parts[1], id);
                    blockNames.putIfAbsent(id, parts[1]);
                } else if ("I".equals(parts[0])) {
                    itemIds.put(parts[1], id);
                } else {
                    throw Grug.fatal(
                            table + " has the line \"" + line + "\", whose kind is not B or I");
                }
            }
        } catch (IOException e) {
            throw Grug.fatal("Failed to read " + table, e);
        }
        return new GrugVanillaIds(blockIds, blockNames, itemIds);
    }

    /** The block id for the name, or -1. */
    public int blockId(String path) {
        Integer id = blockIds.get(key(path));
        return id == null ? -1 : id;
    }

    /** The name the table gives a block id, or null. */
    public String blockName(int id) {
        return blockNames.get(id);
    }

    /** The item id for the name, or -1. */
    public int itemId(String path) {
        Integer id = itemIds.get(key(path));
        return id == null ? -1 : id;
    }

    /**
     * A canonical key that ignores word order: lower case, split on camel case and underscores,
     * sorted and rejoined. {@code iron_ingot} and {@code ingotIron} both key to {@code ingotiron}.
     */
    public static String key(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] tokens = spaced.toLowerCase().split("[\\s_]+");
        Arrays.sort(tokens);
        return String.join("", tokens);
    }
}
