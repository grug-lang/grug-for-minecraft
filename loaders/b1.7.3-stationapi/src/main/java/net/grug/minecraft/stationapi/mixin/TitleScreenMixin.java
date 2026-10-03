package net.grug.minecraft.stationapi.mixin;

import net.minecraft.client.SingleplayerInteractionManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen {

    /**
     * The level {@code test-saves/b1.7.3.zip} actually contains, and the only one it contains.
     *
     * <p>{@code startGame} creates the level when the name does not resolve, so naming a level the
     * save does not have is not a no-op: it generates a fresh world instead, and this generation
     * picks its spawn at random from the new terrain. The fixtures are built at a fixed offset
     * above {@code Test.get_origin()}, which is the player's own position, so a random spawn also
     * made every fixture's absolute height differ per run. On this version the world is 128 blocks
     * tall and {@code World.setBlock} refuses a write at or above it, so a run whose spawn was high
     * enough lost a fixture to a placement that silently did nothing. See #151.
     */
    private static final String SAVE_LEVEL = "New World";

    @Unique private boolean grug$autoLoaded = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void grug$autoLoadWorld(CallbackInfo ci) {
        if (!this.grug$autoLoaded && "true".equals(System.getenv("GRUG_CI"))) {
            this.grug$autoLoaded = true;
            System.out.println("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");
            this.minecraft.interactionManager = new SingleplayerInteractionManager(this.minecraft);
            this.minecraft.startGame(SAVE_LEVEL, SAVE_LEVEL, 0L);
            this.minecraft.setScreen(null);
        }
    }
}
