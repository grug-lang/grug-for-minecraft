package net.grug.minecraft.grug;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The other name grug uses for a vanilla block, for the loaders whose own lookup does not have it.
 *
 * <p>A mod writes one name and every loader answers with the same block, which is the promise the
 * loaders make. It only holds while the loaders agree on what blocks are called, and they do not
 * always: the lit redstone torch is {@code minecraft:redstone_torch} on four of the five and {@code
 * minecraft:redstone_torch_lit} on StationAPI. A loader that cannot resolve a name asks here for
 * the other spelling before giving up, so a mod naming either one gets the same torch everywhere.
 *
 * <p>This is the fallback half of the naming work {@code #58} tracks, not the whole of it: the
 * table holds only the names an audit has found to differ, and a full canonical table, with {@code
 * get_block} answering in those same names, is what that issue still asks for.
 */
public final class GrugBlockNames {

    private static final Map<String, String> OTHER_SPELLINGS = new HashMap<>();

    static {
        // The lit torch. Four loaders call it redstone_torch; StationAPI calls it
        // redstone_torch_lit,
        // and 1.2.5 maps both onto its own lit torch. See #58.
        OTHER_SPELLINGS.put("redstone_torch_lit", "redstone_torch");
    }

    @GrugGenerated("utility class: never instantiated")
    private GrugBlockNames() {}

    /**
     * The block {@code path} names on this loader, or null when it names none.
     *
     * <p>The loader's own lookup is a function rather than a name because every loader answers this
     * differently: some read a registry, some reflect over named fields, and 1.2.5 reads a table
     * dumped at build time. Only the fallback is shared.
     *
     * <p>The path alone, so the namespace has already been settled by the caller: these are vanilla
     * names, and a mod's own block is resolved before any of this.
     */
    public static <T> T resolve(String path, Function<String, T> loaderLookup) {
        T block = loaderLookup.apply(path);
        if (block != null) return block;

        String other = OTHER_SPELLINGS.get(path);
        return other == null ? null : loaderLookup.apply(other);
    }

    /** Every name with another spelling, which is what a test can assert on. */
    public static Map<String, String> otherSpellings() {
        return Collections.unmodifiableMap(OTHER_SPELLINGS);
    }
}
