package net.grug.minecraft.forge.mixin;

import net.minecraft.client.renderer.ItemInHandRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the equip heights 1.20.6's hand renderer interpolates its two hands with, which it keeps
 * private.
 *
 * <p>A capture waits while the hand is still easing into place, so that two runs capture the same
 * pose rather than whatever point of the rise their ticks reached (#961). The values are the ones
 * {@code ItemInHandRenderer.tick} writes and {@code renderHandsWithItems} draws with, not a second
 * copy of that state.
 */
@Mixin(ItemInHandRenderer.class)
public interface ItemInHandRendererAccessor {
    @Accessor("mainHandHeight")
    float grug$mainHandHeight();

    @Accessor("oMainHandHeight")
    float grug$lastMainHandHeight();

    @Accessor("offHandHeight")
    float grug$offHandHeight();

    @Accessor("oOffHandHeight")
    float grug$lastOffHandHeight();
}
