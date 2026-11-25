package net.grug.minecraft.ornithe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * How a capture run tells the game's animated sprites from every other dynamic texture.
 *
 * <p>The identity has to be the binary class name: a simple name is shared by classes in different
 * packages and is synthetic for a lambda, so matching it would freeze the wrong texture and miss
 * the sprite. The table holds the binary name in both namespaces, because a development run sees
 * the mapped one and an installed game the intermediary one.
 */
class GrugFrozenAnimationsTest {

    /** Two classes in different places that share a simple name, as two packages can. */
    private static final class First {
        static final class WaterSprite {}
    }

    private static final class Second {
        static final class WaterSprite {}
    }

    @Test
    void theSpriteIsFoundByItsBinaryName() {
        assertTrue(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.WaterSprite"));
        assertTrue(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.NetherPortalSprite"));
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
    void bothNamespacesAreInTheTable() {
        Set<String> names = GrugFrozenAnimations.animatedNames();
        assertEquals(
                12,
                names.size(),
                "six sprites, named and intermediary, are the whole table: " + names);
        assertTrue(
                names.stream().anyMatch(name -> name.startsWith("net.minecraft.unmapped.")),
                "an installed game matches the intermediary column: " + names);
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
}
