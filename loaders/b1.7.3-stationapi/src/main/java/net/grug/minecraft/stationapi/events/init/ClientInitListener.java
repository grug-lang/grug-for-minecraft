package net.grug.minecraft.stationapi.events.init;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.GrugBlockModels;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.grug.minecraft.stationapi.gui.GrugScreen;
import net.grug.minecraft.stationapi.gui.GrugScreenHandler;
import net.grug.minecraft.stationapi.gui.StationGuiHelper;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.modificationstation.stationapi.api.client.event.option.KeyBindingRegisterEvent;
import net.modificationstation.stationapi.api.client.event.texture.TextureRegisterEvent;
import net.modificationstation.stationapi.api.client.gui.screen.GuiHandler;
import net.modificationstation.stationapi.api.client.texture.atlas.Atlases;
import net.modificationstation.stationapi.api.event.registry.GuiHandlerRegistryEvent;
import net.modificationstation.stationapi.api.network.packet.MessagePacket;
import net.modificationstation.stationapi.api.util.Identifier;

import org.lwjgl.input.Keyboard;

public class ClientInitListener {

    public static KeyBinding runTestsKey;
    public static KeyBinding forceResolutionKey;

    @EventListener
    public void registerKeyBindings(KeyBindingRegisterEvent event) {
        runTestsKey = new KeyBinding("key.grug.run_tests", Keyboard.KEY_R);
        event.keyBindings.add(runTestsKey);
        forceResolutionKey = new KeyBinding("key.grug.force_test_resolution", Keyboard.KEY_M);
        event.keyBindings.add(forceResolutionKey);
    }

    /**
     * Points every custom-rendered block at the sprite of the texture its model asks for, in the
     * terrain atlas this reload just stitched.
     *
     * <p>A custom-rendered shape is drawn through the block's own sprite, and StationAPI's model
     * bake does not reach it, so the sprite index has to come from the model file. The atlas is
     * rebuilt on every reload, which is why this runs on every reload too: a block left holding an
     * index from a previous atlas would draw whatever sprite now occupies that slot.
     */
    @EventListener
    public void registerBlockTextures(TextureRegisterEvent event) {
        for (GrugBlock block : InitListener.getGrugBlocks()) {
            if (!block.drawsCustomGeometry()) continue;

            String[] faces = GrugBlockModels.resolveFaceTextures(block.identifier.getPath());
            if (faces == null) continue;

            int[] sprites = new int[faces.length];
            for (int face = 0; face < faces.length; face++) {
                sprites[face] = Atlases.getTerrain().addTexture(Identifier.of(faces[face])).index;
            }
            block.faceTextures = sprites;
        }
    }

    @EventListener
    public void registerGuiHandlers(GuiHandlerRegistryEvent event) {
        event.register(
                Identifier.of(InitListener.NAMESPACE, "dynamic_gui"),
                new GuiHandler(
                        (PlayerEntity player, Inventory dummyInv, MessagePacket message) -> {
                            if (message.strings == null
                                    || message.ints == null
                                    || message.strings.length < 2
                                    || message.ints.length < 10) {
                                return null;
                            }

                            BlockEntity realBe =
                                    player.world.getBlockEntity(
                                            message.ints[1], message.ints[2], message.ints[3]);
                            if (!(realBe instanceof Inventory realInv)) {
                                return null;
                            }

                            GrugGuiBuilder builder =
                                    StationGuiHelper.readBuilderFromPacket(message);

                            return new GrugScreen(
                                    new GrugScreenHandler(player, realInv, builder), builder);
                        },
                        GrugBlockEntity::new));
    }
}
