package net.grug.minecraft.stationapi.client;

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
 * the sprite. Beta's classes come from a table holding both namespaces, because a development run
 * sees the named one and an installed game the intermediary one.
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
    void anInstalledGamesIntermediaryNameIsInTheTable() {
        Set<String> names = GrugFrozenAnimations.animatedNames();
        assertEquals(
                12,
                names.size(),
                "six sprites, named and intermediary, are the whole table: " + names);
        assertTrue(
                GrugFrozenAnimations.isGameAnimationName("net.minecraft.class_593"),
                "an installed game names the water sprite in its intermediary namespace: " + names);
    }

    @Test
    void theCompassAndTheClockKeepAnimating() {
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.ClockSprite"));
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.minecraft.client.render.texture.CompassSprite"));
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                + ".binder.ArsenicClock"));
        assertFalse(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                + ".binder.ArsenicCompass"));
    }

    @Test
    void stationApisOwnWaterBinderIsHeldStill() {
        assertTrue(
                GrugFrozenAnimations.isGameAnimationName(
                        "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                + ".binder.ArsenicStillWater"),
                "the list this loader actually holds is StationAPI's binders, not Beta's sprites");
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
