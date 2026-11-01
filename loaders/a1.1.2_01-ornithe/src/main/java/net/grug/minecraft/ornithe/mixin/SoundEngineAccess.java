package net.grug.minecraft.ornithe.mixin;

import net.minecraft.client.sound.SoundEngine;
import net.minecraft.client.sound.Sounds;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the pool of sounds the sound engine has loaded, which is how {@code
 * OrnitheAdapter.hasSound} tells a name the game has from one it does not.
 *
 * <p>The field is private, and this loader's game classes come from its own mappings rather than
 * from a patch this repository keeps, so an accessor is the one way to read it without reflecting
 * on a mapped name. See #914.
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccess {
    @Accessor("sounds")
    Sounds grug$sounds();
}
