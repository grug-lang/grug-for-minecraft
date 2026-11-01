package net.grug.minecraft.stationapi.mixin;

import net.minecraft.client.sound.SoundEntry;
import net.minecraft.client.sound.SoundManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the pool of sounds the sound manager has loaded, which is how {@code
 * StationApiAdapter.hasSound} tells a name the game has from one it does not.
 *
 * <p>The field is private, and this loader's game classes come from its own mappings rather than
 * from a patch this repository keeps, so an accessor is the one way to read it without reflecting
 * on a mapped name. See #914.
 */
@Mixin(SoundManager.class)
public interface SoundManagerAccess {
    @Accessor("sounds")
    SoundEntry grug$sounds();
}
