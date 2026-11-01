package net.grug.minecraft.core;

/**
 * The name comparisons a loader's registry lookup makes, as pure functions.
 *
 * <p>1.2.5 predates registry names, so a declared grug name is matched by suffix and an item a
 * reference mod named is found by scanning every item's own name and its stack's name. Both rules
 * live here, where a unit test covers every combination, instead of in a loader whose match arms
 * only a reference run reaches.
 */
public final class GrugRegistryNames {
    private GrugRegistryNames() {}

    /**
     * Whether a declared name is the one the path names: the key ends with {@code ":" + path} when
     * the path carries no namespace, or the key is the path exactly when it does.
     */
    public static boolean matches(String key, String path) {
        return key.endsWith(":" + path) || key.equals(path);
    }

    /**
     * The item a reference name scan has found so far, given the item the scan is at.
     *
     * @param found the item an earlier step of the scan matched, or null
     * @param candidate the item this step is at, or null for an empty slot
     * @param ownName the name the item set for itself
     * @param stackName the name its stack falls back to
     * @param itemName the {@code item.} spelling the path asks for
     * @param tileName the {@code tile.} spelling the path asks for
     */
    public static Object firstItemMatch(
            Object found,
            Object candidate,
            String ownName,
            String stackName,
            String itemName,
            String tileName) {
        if (found != null) {
            return found;
        }
        if (candidate == null) {
            return null;
        }
        if (itemName.equals(ownName)
                || tileName.equals(ownName)
                || itemName.equals(stackName)
                || tileName.equals(stackName)) {
            return candidate;
        }
        return null;
    }
}
