import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Dumps the vanilla block and item names to ids, so the obfuscated 1.2.5 runtime can resolve a
 * resource name like {@code minecraft:chest} without reflecting on obfuscated field names.
 *
 * <p>This runs against the MCP-named dev classes, where the static fields still carry their human
 * names, and prints {@code B <normalised name> <block id>} and {@code I <normalised name> <item
 * id>} lines. The normalisation matches how the adapter matches a name: lower case, underscores
 * removed.
 */
public final class GenerateVanillaNames {
    public static void main(String[] args) throws Exception {
        for (Field field : net.minecraft.src.Block.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!net.minecraft.src.Block.class.isAssignableFrom(field.getType())) continue;
            net.minecraft.src.Block block = (net.minecraft.src.Block) field.get(null);
            if (block == null) continue;
            System.out.println("B " + key(field.getName()) + " " + block.blockID);
        }

        for (Field field : net.minecraft.src.Item.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!net.minecraft.src.Item.class.isAssignableFrom(field.getType())) continue;
            net.minecraft.src.Item item = (net.minecraft.src.Item) field.get(null);
            if (item == null) continue;
            System.out.println("I " + key(field.getName()) + " " + item.shiftedIndex);
        }
    }

    /**
     * A canonical key that ignores word order, so {@code iron_ingot} and {@code ingotIron} both
     * become {@code ingotiron}. Splits camel case and underscores, lower-cases, then sorts.
     */
    private static String key(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] tokens = spaced.toLowerCase().split("[\\s_]+");
        java.util.Arrays.sort(tokens);
        return String.join("", tokens);
    }

    private GenerateVanillaNames() {}
}
