package net.grug.minecraft.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The LICENSE convention shared by every loader: each mod directory under the grug mods directory
 * must contain a non-empty {@code LICENSE} file, so the license text travels with the mod wherever
 * it is copied, bundled, or redistributed.
 *
 * <p>This is deliberately the only implementation of the rule. The loaders run it while starting
 * up, so an author sees a violation locally, and CI fails through the very same path rather than
 * maintaining a second implementation that could drift.
 */
public final class GrugModLicenses {
    private GrugModLicenses() {
    }

    /** Every violation of the LICENSE convention under {@code modsDirectory}, empty when valid. */
    public static List<String> validate(File modsDirectory) {
        List<String> errors = new ArrayList<>();
        File[] modDirectories = modsDirectory.listFiles(File::isDirectory);
        if (modDirectories == null) {
            return errors;
        }
        for (File modDirectory : modDirectories) {
            File license = new File(modDirectory, "LICENSE");
            if (!license.isFile() || license.length() == 0) {
                errors.add(modDirectory.getName() + " has no non-empty LICENSE file.");
            }
        }
        return errors;
    }
}
