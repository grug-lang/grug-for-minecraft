package net.grug.minecraft.ornithe.mixin;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes the light updates {@code World} has queued but not run yet.
 *
 * <p>Alpha's game loop never drains this queue itself, so a loader that drains it in its frame hook
 * is the only drain there is. Reading it before draining is what tells that hook whether a test
 * wrote blocks after the room's light settled, which the hook then settles and marks again. See
 * {@code OrnitheAdapter#frameHadPendingWork} and #1825.
 *
 * <p>The field is private, and this loader's game classes come from its own mappings rather than
 * from a patch this repository keeps, so an accessor is the one way to read it without reflecting
 * on a mapped name. The mixin is in the client configuration, as the hook that reads it is.
 */
@Mixin(World.class)
public interface WorldLightAccess {
    @Accessor("lightUpdates")
    List<?> grug$lightUpdates();
}
