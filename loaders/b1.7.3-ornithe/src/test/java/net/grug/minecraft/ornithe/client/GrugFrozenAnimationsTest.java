package net.grug.minecraft.ornithe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.function.Function;

/**
 * How a capture run tells the game's animated sprites from every other dynamic texture.
 *
 * <p>The identity has to be the binary class name, unmapped from the running game's namespace: a
 * simple name is shared by classes in different packages and is synthetic for a lambda, so matching
 * it would freeze the wrong texture and miss the sprite.
 */
class GrugFrozenAnimationsTest {

    /** The unmapping a development run does, where the running names are the named ones. */
    private static final Function<String, String> NAMED = name -> name;

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
                        "net.minecraft.client.render.texture.WaterSprite", NAMED));
        assertTrue(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.NetherPortalSprite", NAMED));
    }

    @Test
    void theClockAndTheCompassStayAnimating() {
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.ClockSprite", NAMED));
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.CompassSprite", NAMED));
    }

    @Test
    void aClassThatSharesASimpleNameIsNotTheSprite() {
        assertEquals(
                First.WaterSprite.class.getSimpleName(),
                Second.WaterSprite.class.getSimpleName(),
                "the two stand-in classes do share the sprite's simple name");
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(First.WaterSprite.class.getName(), NAMED));
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        Second.WaterSprite.class.getName(), NAMED));
    }

    @Test
    void aLambdaIsNotASprite() {
        Runnable lambda = () -> {};
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(lambda.getClass().getName(), NAMED),
                "a lambda's simple name is a synthetic hidden-class name, not a sprite's");
    }

    @Test
    void anInstalledGamesIntermediaryNameIsResolvedToTheNamedOne() {
        String intermediary = "net.minecraft.unmapped.C_19106855";
        assertTrue(
                GrugFrozenAnimations.isGameAnimationName(
                        intermediary,
                        name ->
                                name.equals(intermediary)
                                        ? "net.minecraft.client.render.texture.WaterSprite"
                                        : name),
                "the comparison happens after the runtime name is unmapped");
    }
}
