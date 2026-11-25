package net.grug.minecraft.ornithe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugErrors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * How a capture run tells the game's animated sprites from every other dynamic texture.
 *
 * <p>The identity has to be the binary class name: a simple name is shared by classes in different
 * packages and is synthetic for a lambda, so matching it would freeze the wrong texture and miss
 * the sprite. The table holds the binary name in both namespaces, because a development run sees
 * the mapped one and an installed game the intermediary one.
 *
 * <p>{@link GrugFrozenAnimations#read} takes the stream it parses, so the table a broken jar would
 * supply can be handed to it here: a missing resource, an empty file, one with only a header, and a
 * read that fails are all reachable from a unit test and none of them needs a game.
 */
class GrugFrozenAnimationsTest {

    private static final String TABLE = "grug_animated_sprites.txt";

    /** Two classes in different places that share a simple name, as two packages can. */
    private static final class First {
        static final class WaterSprite {}
    }

    private static final class Second {
        static final class WaterSprite {}
    }

    /**
     * Drains the fatal a test provoked. {@code Grug.fatal} remembers the first error per thread and
     * rethrows it at the next native call boundary, so leaving one set would fail the next test on
     * this JVM that drives an export function, for a reason unrelated to that test.
     */
    @AfterEach
    void drainPendingFatal() {
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive the test.
        }
    }

    private static InputStream table(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theSpriteIsFoundByItsBinaryName() {
        assertTrue(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.WaterSprite"));
    }

    @Test
    void theClockAndTheCompassStayAnimating() {
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.ClockSprite"));
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.CompassSprite"));
    }

    @Test
    void bothNamespacesAreInTheAlphaTable() {
        Set<String> names = GrugFrozenAnimations.animatedNames();
        assertEquals(
                10,
                names.size(),
                "Alpha ships five sprites, named and intermediary, and no nether portal: " + names);
        assertTrue(
                names.stream().anyMatch(name -> name.startsWith("net.minecraft.unmapped.")),
                "an installed game matches the intermediary column: " + names);
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.NetherPortalSprite"),
                "Alpha has no nether portal sprite, so its table cannot name one");
    }

    @Test
    void aClassThatSharesASimpleNameIsNotTheSprite() {
        assertEquals(
                First.WaterSprite.class.getSimpleName(),
                Second.WaterSprite.class.getSimpleName(),
                "the two stand-in classes do share the sprite's simple name");
        assertFalse(GrugFrozenAnimations.isGameAnimationName(First.WaterSprite.class.getName()));
        assertFalse(GrugFrozenAnimations.isGameAnimationName(Second.WaterSprite.class.getName()));
    }

    @Test
    void aLambdaIsNotASprite() {
        Runnable lambda = () -> {};
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(lambda.getClass().getName()),
                "a lambda's simple name is a synthetic hidden-class name, not a sprite's");
    }

    @Test
    void aTableIsReadAsBinaryNamesFromBothColumns() {
        Set<String> names =
                GrugFrozenAnimations.read(
                        TABLE,
                        table(
                                "intermediary named\n"
                                        + "net/minecraft/class_593"
                                        + " net/minecraft/client/render/texture/WaterSprite\n"));

        assertEquals(
                new HashSet<>(
                        Arrays.asList(
                                "net.minecraft.class_593",
                                "net.minecraft.client.render.texture.WaterSprite")),
                names,
                "both the intermediary and the named name are in the set, as binary names");
    }

    @Test
    void aJarWithoutTheTableIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class, () -> GrugFrozenAnimations.read(TABLE, null));

        assertTrue(error.getMessage().contains(TABLE), error.getMessage());
    }

    @Test
    void anEmptyTableIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFrozenAnimations.read(TABLE, table("")));

        assertTrue(error.getMessage().contains(TABLE + " is empty"), error.getMessage());
    }

    @Test
    void aTableWithOnlyAHeaderNamesNoSprite() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFrozenAnimations.read(TABLE, table("intermediary named\n")));

        assertTrue(error.getMessage().contains(TABLE + " names no sprite"), error.getMessage());
    }

    @Test
    void aTableThatCannotBeReadIsFatal() {
        IOException cause = new IOException("disk gone");
        InputStream broken =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw cause;
                    }
                };

        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFrozenAnimations.read(TABLE, broken));

        assertSame(cause, error.getCause());
    }
}
