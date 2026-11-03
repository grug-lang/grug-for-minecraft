package net.grug.minecraft.core;

/**
 * The checks on a sound call's arguments, shared by the host functions that take them and by both
 * ends of the test-control channel that forwards them, so a value one end refuses is refused by the
 * other.
 */
public final class GrugSoundArgs {
    private GrugSoundArgs() {}

    /**
     * Why a sound cannot be a name at all, or null when it has the shape {@code namespace:path},
     * both parts of lowercase letters, digits and the characters {@code . _ -}, the path also
     * allowing {@code /}. That is a resource location's shape on 1.20.6, and every name in the
     * older versions' sound pools fits the path's.
     */
    public static String nameProblem(String name) {
        if (!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            return "A sound name is a namespace, a colon and a path, such as"
                    + " \"minecraft:ui.button.click\", and \""
                    + name
                    + "\" is not one.";
        }
        return null;
    }

    /**
     * Why a path of the game's own {@code minecraft} namespace, which is what a forwarded call
     * carries, is not one; null when it is. It also has to fit in a control message.
     */
    public static String pathProblem(String path) {
        if (path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > GrugControlWire.MAX_NAME) {
            return "The sound path is over " + GrugControlWire.MAX_NAME + " bytes.";
        }
        return nameProblem("minecraft:" + path);
    }

    /**
     * Why a sound cannot be played at this volume and pitch, or null when it can. Both games clamp
     * a volume to 0 to 1 and 1.20.6 clamps a pitch to 0.5 to 2, so a value beyond them is a
     * script's mistake that would otherwise play as a different number than the one it asked for.
     * Not-a-number and the infinities are outside both ranges.
     */
    public static String levelsProblem(double volume, double pitch) {
        if (!(volume >= 0 && volume <= 1)) {
            return "The volume has to be from 0 to 1, and was " + volume + ".";
        }
        if (!(pitch >= 0.5 && pitch <= 2)) {
            return "The pitch has to be from 0.5 to 2, and was " + pitch + ".";
        }
        return null;
    }
}
