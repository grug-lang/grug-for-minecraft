package net.grug.minecraft.stationapi.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.util.Timer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the client's frame timer, which the game keeps private, so a capture's diagnostics can
 * log the partial tick the frame is drawn with (#169). It is the same object {@code Minecraft.run}
 * advances, not a second copy of its state.
 */
@Mixin(Minecraft.class)
public interface MinecraftTimerAccess {
    @Accessor("timer")
    Timer grug$timer();
}
