package net.grug.minecraft.stationapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How a capture run tells the game's animated sprites from every other dynamic texture.
 *
 * <p>The identity has to be the binary class name, unmapped from the running game's namespace: a
 * simple name is shared by classes in different packages and is synthetic for a lambda, so matching
 * it would freeze the wrong texture and miss the sprite.
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
                        "net.minecraft.client.render.texture.WaterSprite", name -> name));
    }

    @Test
    void aClassThatSharesASimpleNameIsNotTheSprite() {
        assertEquals(
                First.WaterSprite.class.getSimpleName(),
                Second.WaterSprite.class.getSimpleName(),
                "the two stand-in classes do share the sprite's simple name");
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        First.WaterSprite.class.getName(), name -> name));
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        Second.WaterSprite.class.getName(), name -> name));
    }

    @Test
    void aLambdaIsNotASprite() {
        Runnable lambda = () -> {};
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(lambda.getClass().getName(), name -> name),
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

    @Test
    void stationApisOwnWaterBinderIsHeldStill() {
        assertTrue(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                + ".binder.ArsenicStillWater",
                        name -> name),
                "the list this loader actually holds is StationAPI's binders, not Beta's sprites");
    }

    @Test
    void theCompassAndTheClockKeepAnimating() {
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                + ".binder.ArsenicCompass",
                        name -> name));
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                + ".binder.ArsenicClock",
                        name -> name));
    }
}
