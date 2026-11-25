package net.grug.minecraft.grug;

/**
 * The two per-world strings a mod keys its files on, decided in one place so every loader answers
 * the same way and so every arm is a unit test's to force: a dimension's key and a world's name.
 *
 * <p>A loader passes what its own game gives it, which is a numbered dimension on Alpha, 1.2.5 and
 * b1.7.3 and a resource location on 1.20.6, and this class turns that into the key {@link
 * net.grug.minecraft.core.ModLoaderAdapter#levelDimension} promises. Neither part of a resource
 * location may hold {@code ~} or {@code +}, so the encoding of a named dimension is injective: two
 * different dimensions never answer the same string. The name falls back to {@code world} when the
 * game holds none, so a call never throws and never answers an empty string.
 */
public final class GrugLevelKey {

    private GrugLevelKey() {}

    /**
     * The key of a dimension the game numbers: the three the game ships, and any other number a
     * provider reports, which is what Alpha, 1.2.5 and b1.7.3 report.
     */
    public static String ofNumber(int id) {
        if (id == 0) {
            return "overworld";
        }
        if (id == -1) {
            return "the_nether";
        }
        if (id == 1) {
            return "the_end";
        }
        return "dimension_" + id;
    }

    /**
     * The key of a dimension named by a resource location's parts, which is what 1.20.6 reports.
     * The namespace and the path are joined with {@code ~}, and the path's {@code /} becomes {@code
     * +}, so the pair is recoverable and two different dimensions never share a key.
     */
    public static String ofKey(String namespace, String path) {
        if ("minecraft".equals(namespace)
                && ("overworld".equals(path)
                        || "the_nether".equals(path)
                        || "the_end".equals(path))) {
            return path;
        }
        return "dimension_" + namespace + "~" + path.replace('/', '+');
    }

    /**
     * The level's name: the address a client dialled when it joined somebody else's server, else
     * the name the game gives the level, else {@code world} when the game holds no name at all.
     */
    public static String name(String address, String saveName) {
        if (address != null && !address.isEmpty()) {
            return address;
        }
        return saveName == null || saveName.isEmpty() ? "world" : saveName;
    }
}
