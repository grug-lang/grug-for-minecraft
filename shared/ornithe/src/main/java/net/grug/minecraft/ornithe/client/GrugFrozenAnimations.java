package net.grug.minecraft.ornithe.client;

import net.grug.minecraft.grug.Grug;
import net.minecraft.client.render.texture.DynamicTexture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Names the game's own animated sprites, which a capture run leaves on the tile the texture pack
 * drew.
 *
 * <p>Alpha and Beta animate water, lava, fire, the compass, the clock and the nether portal by
 * writing new pixels into their atlas slot every tick. A capture of a scene holding one would
 * otherwise be whichever frame the run happened to reach: no committed golden could match the next
 * run, and the reference job, which compares its live capture with a golden exactly, could not
 * match at all. {@link net.grug.minecraft.ornithe.mixin.TextureManagerMixin} drops these from the
 * manager's list before the first upload, so the slot keeps the pack's own tile, which is the same
 * pixels in every run. The compass and the clock follow the player and the clock rather than the
 * number of frames a run has drawn, so they are left animating. See {@code
 * GrugReference.isAnimationFrozen()} and #1771.
 *
 * <p>A sprite is identified by its binary class name, not by its simple name: two classes in
 * different packages may share a simple name, and a lambda's synthetic hidden class has one that
 * names nothing in the game. A development run sees the named classes and an installed game sees
 * their intermediary names, so the build writes both namespaces' binary names into {@value #TABLE}
 * from this loader's mappings (gradle/ornithe-vanilla-names.gradle) and either kind of run matches
 * its own column. Alpha ships neither the clock nor the nether portal sprite, and the table simply
 * holds the classes the mappings have.
 */
public final class GrugFrozenAnimations {
    /** The table the build writes, one binary class name per namespace per line. */
    private static final String TABLE = "grug_animated_sprites.txt";

    private static final Set<String> ANIMATED = readAnimatedNames();

    private GrugFrozenAnimations() {}

    /** Whether {@code texture} is one of the game's own animated sprites. */
    public static boolean isGameAnimation(DynamicTexture texture) {
        return isGameAnimationName(texture.getClass().getName());
    }

    /** Whether the class with this binary name is one of the sprites, for a unit test. */
    static boolean isGameAnimationName(String binaryName) {
        return ANIMATED.contains(binaryName);
    }

    /** The binary names of the sprites in both namespaces, for a unit test. */
    static Set<String> animatedNames() {
        return ANIMATED;
    }

    private static Set<String> readAnimatedNames() {
        InputStream in = GrugFrozenAnimations.class.getResourceAsStream("/" + TABLE);
        if (in == null) {
            throw Grug.fatal("The grug jar holds no " + TABLE);
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            if (reader.readLine() == null) {
                throw Grug.fatal(TABLE + " is empty");
            }
            Set<String> names = new HashSet<>();
            String line;
            while ((line = reader.readLine()) != null) {
                // One sprite's name in each namespace of the header, so both are in the set.
                for (String name : line.split(" ")) {
                    names.add(name.replace('/', '.'));
                }
            }
            if (names.isEmpty()) {
                throw Grug.fatal(TABLE + " names no sprite");
            }
            return names;
        } catch (IOException e) {
            throw Grug.fatal("Failed to read " + TABLE, e);
        }
    }
}
