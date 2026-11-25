package net.grug.minecraft.grug;

/**
 * The handle {@code File.open} returns: the one mod its calls resolve against.
 *
 * <p>It holds only the mod's name, because the two directories are read from the loader at each
 * call, and it never holds a path, so a mod cannot hand a call a directory the host did not choose.
 */
final class GrugModFile {

    private final String modName;

    GrugModFile(String modName) {
        this.modName = modName;
    }

    String modName() {
        return modName;
    }
}
