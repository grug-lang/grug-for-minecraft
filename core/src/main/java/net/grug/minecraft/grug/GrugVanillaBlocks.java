package net.grug.minecraft.grug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The canonical vanilla block names, and each loader's own spelling for the same block.
 *
 * <p>Minecraft renamed blocks and spells them differently per version, so a mod that places a block
 * by name gets a different block on a different loader unless the name is pinned. This reads the
 * audit in {@code /vanilla_blocks.txt}, which pairs each canonical name with the spelling every
 * loader uses for the same block, and answers the two questions a loader has: what does this loader
 * call the block a mod named, and what is the canonical name of the block this loader handed back.
 *
 * <p>Canonical means the spelling modern Minecraft uses. Most blocks are spelled the same on every
 * loader, so the usual answer is that the name a mod wrote is the name the loader wants. The table
 * exists for the blocks where that is not true.
 *
 * <p>A canonical name the audit does not cover, or one this loader does not have, comes back from
 * {@link Loader#localName} unchanged, and the loader falls back to its own resolution for it. A
 * name that still does not resolve is reported rather than placed as a different block, which is
 * what keeps one name meaning one block everywhere.
 */
public final class GrugVanillaBlocks {

    /** Where the table lives on the classpath, in every loader's jar. */
    private static final String TABLE = "/vanilla_blocks.txt";

    private static final String NAMESPACE = "minecraft:";

    /**
     * The loader each table column holds, in sorted order, which is the order the table's header
     * documents and the order {@code vanilla_blocks.py} derives from the loader directories. Fixed
     * here rather than read from the file so that a loader asking for itself gets a stable answer,
     * and so that a table with a missing column is a load failure rather than a shorter table.
     */
    private static final List<String> COLUMN_LOADERS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            "1.2.5-forge",
                            "1.20.6-forge",
                            "a1.1.2_01-ornithe",
                            "b1.7.3-ornithe",
                            "b1.7.3-stationapi"));

    private static final Table AUDIT = readFromJar();

    /**
     * Reads the table out of the jar.
     *
     * <p>Kept apart from {@link #parse} so the failure paths do not count against the parser's
     * coverage: the table is a resource inside the core jar, so a missing or unreadable one is not
     * something a test can produce and is not worth measuring.
     */
    @GrugGenerated(
            "table resource: the table ships in the core jar, so a missing one cannot be forced")
    private static Table readFromJar() {
        try (InputStream in = GrugVanillaBlocks.class.getResourceAsStream(TABLE)) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + TABLE, e);
        }
    }

    /**
     * Builds a table from the parsed file.
     *
     * <p>A {@code -} column means the loader has no such block, which is the same as an absent
     * canonical name to a lookup: this loader cannot place it. A row with too few columns is a
     * defect in the shipped file rather than something a mod can reach, so it fails here instead of
     * leaving a loader believing a block it does not have is one it can place.
     */
    static Table parse(InputStream in) throws IOException {
        Map<String, Loader> loaders = new HashMap<>();
        for (String loaderId : COLUMN_LOADERS) {
            loaders.put(loaderId, new Loader());
        }
        List<String> canonicalNames = new ArrayList<>();

        BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = trimmed.split("\\s+");
            if (columns.length != COLUMN_LOADERS.size() + 1) {
                throw new IllegalStateException(
                        TABLE
                                + " row '"
                                + trimmed
                                + "' has "
                                + columns.length
                                + " columns, but it needs a canonical name and one per loader ("
                                + (COLUMN_LOADERS.size() + 1)
                                + ")");
            }
            canonicalNames.add(columns[0]);
            for (int column = 1; column < columns.length; column++) {
                if (!"-".equals(columns[column])) {
                    loaders.get(COLUMN_LOADERS.get(column - 1)).add(columns[0], columns[column]);
                }
            }
        }
        return new Table(canonicalNames, loaders);
    }

    /**
     * One loader's view of the table. A loader asks for itself once and holds on to it, so the two
     * lookups below are a map read rather than a table scan on every placed block.
     */
    public static Loader forLoader(String loaderId) {
        Loader loader = AUDIT.loaders.get(loaderId);
        if (loader == null) {
            throw new IllegalArgumentException(
                    loaderId + " is not one of the loaders " + COLUMN_LOADERS);
        }
        return loader;
    }

    /** Every canonical name in the table, in the order the table lists them. */
    public static List<String> canonicalNames() {
        return AUDIT.canonicalNames;
    }

    /**
     * The loader ids the table has a column for, which are the directories under {@code loaders/}.
     */
    public static List<String> loaderIds() {
        return COLUMN_LOADERS;
    }

    /** A parsed table: the canonical names it names, and each loader's view of them. */
    static final class Table {

        private final List<String> canonicalNames;

        private final Map<String, Loader> loaders;

        private Table(List<String> canonicalNames, Map<String, Loader> loaders) {
            this.canonicalNames = Collections.unmodifiableList(canonicalNames);
            this.loaders = Collections.unmodifiableMap(loaders);
        }

        List<String> canonicalNames() {
            return canonicalNames;
        }

        Loader loader(String loaderId) {
            return loaders.get(loaderId);
        }
    }

    /** One loader's names for the vanilla blocks, read from {@link GrugVanillaBlocks}'s table. */
    public static final class Loader {

        /** Canonical name, without its namespace, to this loader's own spelling of the block. */
        private final Map<String, String> localNames = new HashMap<>();

        /** This loader's own spelling of a block, lower cased, back to the canonical name. */
        private final Map<String, String> canonicalNames = new HashMap<>();

        private Loader() {}

        private void add(String canonicalName, String localName) {
            localNames.put(stripNamespace(canonicalName), localName);
            canonicalNames.put(localName.toLowerCase(Locale.ROOT), canonicalName);
        }

        /**
         * The path this loader spells the block {@code canonicalName} under. The answer is always a
         * path with no namespace, which is what a loader's own resolver takes. A name the audit
         * does not cover comes back as it went in, because most blocks are spelled the same on
         * every loader and the table only carries the ones that are not.
         *
         * <p>The name may carry the {@code minecraft:} namespace or not, because both reach this
         * from a mod: written by {@code place_block}, and returned by {@code get_block}.
         */
        public String localName(String canonicalName) {
            String path = stripNamespace(canonicalName);
            String local = localNames.get(path);
            return local == null ? path : local;
        }

        /**
         * The canonical name for the block this loader calls {@code localPath}, or {@code
         * localPath} unchanged when the table does not cover it. A loader passes the path it would
         * otherwise have returned, so a block the table does not list keeps the loader's own
         * spelling instead of being renamed to a name that resolves to a different block.
         */
        public String canonicalName(String localPath) {
            String canonical = canonicalNames.get(localPath.toLowerCase(Locale.ROOT));
            return canonical == null ? NAMESPACE + localPath : canonical;
        }

        private static String stripNamespace(String name) {
            return name.startsWith(NAMESPACE) ? name.substring(NAMESPACE.length()) : name;
        }
    }

    @GrugGenerated("utility class: never instantiated")
    private GrugVanillaBlocks() {}
}
