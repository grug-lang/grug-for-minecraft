package net.grug.minecraft.stationapi.mixin;

import net.minecraft.client.render.item.HeldItemRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the equip height Beta's hand renderer interpolates the first-person hand with, which it
 * keeps private.
 *
 * <p>A capture waits while the hand is still easing into place, so that two runs capture the same
 * pose rather than whatever point of the rise their ticks reached (#961). The values are the ones
 * {@code HeldItemRenderer.tick} writes and {@code render} draws with, not a second copy of that
 * state. The field is private and this loader's game classes come from its own mappings, so an
 * accessor is the one way to read it without reflecting on a mapped name, as with {@link
 * WorldRendererAccess}.
 */
@Mixin(HeldItemRenderer.class)
public interface HeldItemRendererAccess {
    @Accessor("height")
    float grug$height();

    @Accessor("prevHeight")
    float grug$prevHeight();
}
