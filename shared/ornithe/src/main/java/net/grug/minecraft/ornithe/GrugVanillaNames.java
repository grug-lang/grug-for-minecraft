package net.grug.minecraft.ornithe;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.Grug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The mapped name of a static field of {@code Block} or {@code Item}, which is the only name a
 * vanilla block or item has on Alpha and Beta.
 *
 * <p>A development run sees the mapped names themselves. An installed game sees intermediary names
 * instead, so there the mapped name comes from {@code grug_vanilla_names.txt}, which the build
 * writes from this loader's mappings (gradle/ornithe-vanilla-names.gradle).
 */
public final class GrugVanillaNames {

    private static Map<String, String> namedByIntermediary;

    private GrugVanillaNames() {}

    public static String of(Field field) {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return field.getName();
        }
        if (namedByIntermediary == null) {
            namedByIntermediary = load();
        }
        String named = namedByIntermediary.get(field.getName());
        if (named == null) {
            throw Grug.fatal(
                    "grug_vanilla_names.txt has no mapped name for "
                            + field.getDeclaringClass().getName()
                            + "."
                            + field.getName());
        }
        return named;
    }

    private static Map<String, String> load() {
        Map<String, String> names = new HashMap<>();
        try (InputStream in =
                GrugVanillaNames.class.getResourceAsStream("/grug_vanilla_names.txt")) {
            if (in == null) {
                throw Grug.fatal("The jar holds no grug_vanilla_names.txt");
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] columns = line.split(" ");
                names.put(columns[0], columns[1]);
            }
        } catch (IOException e) {
            throw Grug.fatal("Failed to read grug_vanilla_names.txt", e);
        }
        return names;
    }
}
