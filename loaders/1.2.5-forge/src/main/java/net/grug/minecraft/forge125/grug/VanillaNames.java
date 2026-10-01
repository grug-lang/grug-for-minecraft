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
 * Resolves a {@code minecraft:...} resource name to a 1.2.5 block or item id.
 *
 * <p>The runtime is obfuscated, so the other loaders' trick of reflecting over named fields does
 * not work here: {@code Block.chest} is a one-letter field at run time. Instead the build dumps the
 * dev classes' names and ids into {@code /vanilla_names.txt}, and this reads them back. Names are
 * keyed by {@link #key(String)}, which ignores word order so {@code iron_ingot} matches {@code
 * ingotIron}.
 *
 * <p>A few names genuinely changed between 1.2.5 and the names grug writes, so {@link #ALIASES}
 * carries those. This is a stopgap; a resource name that is not an alias or the same words in a
 * different order will not resolve.
 */
public final class VanillaNames {

    private static final Map<String, Integer> BLOCKS = new HashMap<>();
    private static final Map<Integer, String> BLOCK_NAMES = new HashMap<>();
    private static final Map<String, Integer> ITEMS = new HashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        ALIASES.put(key("crafting_table"), key("workbench"));
        ALIASES.put(key("enchanting_table"), key("enchantmenttable"));

        // The other loaders call the lit torch "redstone_torch" and StationAPI calls it
        // "redstone_torch_lit"; 1.2.5's MCP field is torchRedstoneActive. Map both grug names onto
        // the lit torch so the cross-loader test places the same block everywhere. See #58.
        ALIASES.put(key("redstone_torch"), key("torchRedstoneActive"));
        ALIASES.put(key("redstone_torch_lit"), key("torchRedstoneActive"));

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
        Integer id = BLOCKS.get(resolve(path));
        return id == null ? -1 : id;
    }

    /**
     * The normalised name for a vanilla block id, or null. This is the reverse of {@link #key}, not
     * the block's MCP field name, so multi-word blocks come back run together (for example {@code
     * oreIron} becomes {@code ironore}). It is enough to name the single-word blocks the
     * block-query test checks; canonical names across loaders are tracked separately.
     */
    public static String blockName(int id) {
        return BLOCK_NAMES.get(id);
    }

    /** The item id (its shiftedIndex) for the name, or -1. */
    public static int itemId(String path) {
        Integer id = ITEMS.get(resolve(path));
        return id == null ? -1 : id;
    }

    private static String resolve(String path) {
        String k = key(path);
        String alias = ALIASES.get(k);
        return alias != null ? alias : k;
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
