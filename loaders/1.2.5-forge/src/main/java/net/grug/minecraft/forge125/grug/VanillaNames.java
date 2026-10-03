package net.grug.minecraft.forge125.grug;

import net.grug.minecraft.grug.GrugGenerated;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a name to a 1.2.5 block or item id.
 *
 * <p>The runtime is obfuscated, so the other loaders' trick of reflecting over named fields does
 * not work here: {@code Block.chest} is a one-letter field at run time. Instead the build dumps the
 * dev classes' names and ids into {@code /vanilla_names.txt}, and this reads them back. Names are
 * keyed by {@link #key(String)}, which ignores word order so {@code iron_ingot} matches {@code
 * ingotIron}.
 *
 * <p>This is 1.2.5's own naming and nothing more. Turning a canonical name a mod wrote into the
 * name this game knows a block by, and this game's name back into the canonical one, is {@code
 * GrugVanillaBlocks}' job, so that the same table covers all five loaders. See #58.
 */
public final class VanillaNames {

    private static final Map<String, Integer> BLOCKS = new HashMap<>();
    private static final Map<Integer, String> BLOCK_NAMES = new HashMap<>();
    private static final Map<String, Integer> ITEMS = new HashMap<>();

    static {
        try (InputStream in = VanillaNames.class.getResourceAsStream("/vanilla_names.txt")) {
            if (in == null) {
                throw new IOException("/vanilla_names.txt is missing from the loader jar");
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 3) continue;
                int id = Integer.parseInt(parts[2]);
                if ("B".equals(parts[0])) {
                    BLOCKS.put(parts[1], id);
                    BLOCK_NAMES.putIfAbsent(id, parts[1]);
                } else if ("I".equals(parts[0])) {
                    ITEMS.put(parts[1], id);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read vanilla_names.txt", e);
        }
    }

    /** The block id for the name, or -1. */
    public static int blockId(String path) {
        Integer id = BLOCKS.get(key(path));
        return id == null ? -1 : id;
    }

    /**
     * This game's name for a vanilla block id, or null. It is the {@link #key} of the block's MCP
     * field name, which is what {@link #blockId} is asked with, so the two directions match.
     * Mapping it to a canonical name a mod can compare against is {@code GrugVanillaBlocks}' job.
     */
    public static String blockName(int id) {
        return BLOCK_NAMES.get(id);
    }

    /** The item id (its shiftedIndex) for the name, or -1. */
    public static int itemId(String path) {
        Integer id = ITEMS.get(key(path));
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

    @GrugGenerated("utility class: never instantiated")
    private VanillaNames() {}
}
