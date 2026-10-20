package net.grug.minecraft.grug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mapped names of a game's fields, read from a table the build writes from a loader's mappings.
 *
 * <p>Alpha and Beta have no block or item registry, so a loader for them names a vanilla block or
 * item after the field that holds it, and only the mapped name of that field is the name a mod
 * uses. A development run sees mapped names, but an installed game sees another namespace's names,
 * so the loader looks every field up here, under whichever namespace the game runs in.
 *
 * <p>The table's first line names its columns' namespaces, and every other line holds one field's
 * name in each of them, separated by spaces. The column named {@link #MAPPED} is the answer.
 */
public final class GrugFieldNames {
    /** The namespace of the names a mod sees. */
    public static final String MAPPED = "named";

    private final String table;
    private final Map<String, String> mappedByRuntime;

    private GrugFieldNames(String table, Map<String, String> mappedByRuntime) {
        this.table = table;
        this.mappedByRuntime = mappedByRuntime;
    }

    /**
     * Reads {@code table}, keyed by the names of the {@code runtime} namespace. {@code in} is null
     * when the jar holds no such table, which is a broken invariant like a table without a column
     * for either namespace.
     */
    public static GrugFieldNames read(String table, InputStream in, String runtime) {
        if (in == null) {
            throw Grug.fatal("The grug jar holds no " + table);
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw Grug.fatal(table + " is empty");
            }
            List<String> namespaces = Arrays.asList(header.split(" "));
            int key = namespaces.indexOf(runtime);
            int value = namespaces.indexOf(MAPPED);
            if (key < 0 || value < 0) {
                throw Grug.fatal(
                        table
                                + " has the namespaces "
                                + namespaces
                                + ", which lack "
                                + (key < 0 ? runtime : MAPPED));
            }
            Map<String, String> mappedByRuntime = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] names = line.split(" ");
                if (names.length != namespaces.size()) {
                    throw Grug.fatal(
                            table
                                    + " has the line \""
                                    + line
                                    + "\", which does not name a field in each of "
                                    + namespaces);
                }
                mappedByRuntime.put(names[key], names[value]);
            }
            return new GrugFieldNames(table, mappedByRuntime);
        } catch (IOException e) {
            throw Grug.fatal("Failed to read " + table, e);
        }
    }

    /** The mapped name of the field the running game calls {@code runtimeName}. */
    public String mapped(String runtimeName) {
        String mapped = mappedByRuntime.get(runtimeName);
        if (mapped == null) {
            throw Grug.fatal(table + " has no mapped name for the field " + runtimeName);
        }
        return mapped;
    }
}
