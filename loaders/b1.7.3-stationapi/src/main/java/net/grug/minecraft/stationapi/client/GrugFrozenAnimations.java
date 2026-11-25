package net.grug.minecraft.stationapi.client;

import net.grug.minecraft.grug.Grug;
import net.minecraft.client.render.texture.DynamicTexture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Which of the texture manager's dynamic textures a capture run holds still, so an animated tile
 * keeps the pixels the texture pack stitched.
 *
 * <p>Beta animates water, lava, fire and the nether portal through a dynamic texture whose every
 * frame StationAPI's renderer binds into its own stitched atlas, so a capture of the tank's column
 * held in water was a different animation frame in every run and the golden one run wrote never
 * matched the next run or the dedicated leg. The compass and the clock are absent, so they keep
 * animating as the other loaders leave them, because no capture shows them. See {@code
 * GrugReference.isAnimationFrozen} and #1771.
 *
 * <p>StationAPI's renderer replaces Beta's six sprite classes with its own binders, and those are
 * what the list holds at run time: a capture run's list is eighteen entries, all {@code Arsenic*}
 * binders, so a set of the game's own class names alone freezes nothing. Both families are listed,
 * because which one a given StationAPI build installs is not this loader's to assume.
 *
 * <p>A sprite is identified by its binary class name, not by its simple name, which two classes in
 * different packages can share and which a lambda's synthetic hidden class makes unstable. Beta's
 * classes are named in the namespace the build gives them and in the intermediary one an installed
 * game runs them in, so {@value #TABLE} holds both and either kind of run matches its own column.
 * StationAPI's own binders are not in the game's mappings, so their name is the same in both. The
 * identity sits here rather than in the mixin because Mixin refuses a non-private method in a mixin
 * class, and a unit test has to reach it.
 */
public final class GrugFrozenAnimations {
    /** The table the build writes, one Beta sprite per line with both its binary names. */
    private static final String TABLE = "grug_animated_sprites.txt";

    /**
     * StationAPI's own binders, whose binary names are the same in a development and an installed
     * run.
     */
    private static final Set<String> BINDERS =
            new HashSet<>(
                    Arrays.asList(
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicStillWater",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicFlowingWater",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicStillLava",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicFlowingLava",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicFire",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicPortal"));

    private static final Set<String> ANIMATED =
            read(TABLE, GrugFrozenAnimations.class.getResourceAsStream("/" + TABLE));

    private GrugFrozenAnimations() {}

    /** Whether {@code texture} is one of the game's own animated tiles. */
    public static boolean isGameAnimation(DynamicTexture texture) {
        return isGameAnimationName(texture.getClass().getName());
    }

    /** Whether the class with this binary name is one of the tiles, for a unit test. */
    static boolean isGameAnimationName(String binaryName) {
        return ANIMATED.contains(binaryName) || BINDERS.contains(binaryName);
    }

    /** The Beta sprites' binary names in both namespaces, for a unit test. */
    static Set<String> animatedNames() {
        return ANIMATED;
    }

    /**
     * Parses {@code table}'s sprite names. {@code in} is null when the jar holds no such table,
     * which is a broken invariant like a table without a line for a sprite.
     *
     * <p>Takes the stream rather than fetching it, so a unit test can hand it a table of its own
     * and reach every refusal: a missing resource, an empty file, one with only a header, and a
     * read that fails.
     */
    static Set<String> read(String table, InputStream in) {
        if (in == null) {
            throw Grug.fatal("The grug jar holds no " + table);
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            if (reader.readLine() == null) {
                throw Grug.fatal(table + " is empty");
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
                throw Grug.fatal(table + " names no sprite");
            }
            return names;
        } catch (IOException e) {
            throw Grug.fatal("Failed to read " + table, e);
        }
    }
}
