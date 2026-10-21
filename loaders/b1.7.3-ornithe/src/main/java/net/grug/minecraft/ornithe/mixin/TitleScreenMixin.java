package net.grug.minecraft.ornithe.mixin;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.grug.GrugIdShortage;
import net.grug.minecraft.grug.GrugModManager;
import net.grug.minecraft.grug.GrugModsScreenModel;
import net.grug.minecraft.ornithe.client.GrugModsButton;
import net.grug.minecraft.ornithe.client.GrugModsScreen;
import net.minecraft.client.SurvivalInteractionManager;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;

import org.spongepowered.asm.mixin.Mixin;
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
     * tall and {@code World.setBlockQuietly} refuses a write at or above it, so a run whose spawn
     * was high enough lost a fixture to a placement that silently did nothing. See #151.
     */
    private static final String SAVE_LEVEL = "New World";

    @Inject(method = "init", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void grug$autoLoadWorld(CallbackInfo ci) {
        this.buttons.add(new GrugModsButton(this));
        // A game short of block or item ids registered none of that kind and let the title screen
        // come up, so the player can turn mods off here (#510). Nothing but the mod manager is
        // safe to enter: a world saved without the blocks would lose them.
        if (!GrugIdShortage.all().isEmpty()) {
            // No one is there to answer the screen of a CI run, which should say why it stopped.
            if ("true".equals(System.getenv("GRUG_CI"))) GrugIdShortage.failIfShort();
            this.minecraft.openScreen(
                    new GrugModsScreen(
                            this,
                            new GrugModsScreenModel(
                                    GrugModManager.of(
                                            FabricLoader.getInstance().getGameDir().toFile()),
                                    5)));
            return;
        }
        if ("true".equals(System.getenv("GRUG_CI"))) {
            System.out.println("[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL");
            this.minecraft.interactionManager = new SurvivalInteractionManager(this.minecraft);
            this.minecraft.startGame(SAVE_LEVEL, SAVE_LEVEL, 0L);
            this.minecraft.openScreen(null);
        }
    }
}
