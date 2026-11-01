package net.grug.minecraft.stationapi.events.init;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.GrugBlockModels;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.grug.minecraft.stationapi.client.GrugFrameRenderer;
import net.grug.minecraft.stationapi.client.GrugOverlayHost;
import net.grug.minecraft.stationapi.gui.GrugScreen;
import net.grug.minecraft.stationapi.gui.GrugScreenHandler;
import net.grug.minecraft.stationapi.gui.StationGuiHelper;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.modificationstation.stationapi.api.client.event.block.entity.BlockEntityRendererRegisterEvent;
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
        GrugOverlayHost.init(InitListener.overlayFiles, InitListener.overlayMods);
        runTestsKey = new KeyBinding("key.grug.run_tests", Keyboard.KEY_R);
        event.keyBindings.add(runTestsKey);
        forceResolutionKey = new KeyBinding("key.grug.force_test_resolution", Keyboard.KEY_M);
        event.keyBindings.add(forceResolutionKey);
    }

    /**
     * Gives every grug block entity the per-frame renderer what {@code render_frame()} draws
     * through.
     *
     * <p>It is registered for the one grug block entity class every grug block shares, so the
     * renderer is asked about every grug block and answers only for the blocks that declared {@code
     * set_custom_render}, the same condition the chunk's geometry uses.
     */
    @EventListener
    public void registerBlockEntityRenderers(BlockEntityRendererRegisterEvent event) {
        event.register(GrugBlockEntity.class, new GrugFrameRenderer());
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

                            GrugGuiBuilder builder =
                                    StationGuiHelper.readBuilderFromPacket(message);

                            // The open packet can reach a client before the block update that
                            // places the block, when a block is placed and used in the same server
                            // tick, and a multiplayer client has a world of its own. The client
                            // block entity is then missing, so the menu is built on a placeholder
                            // inventory, as vanilla does, and the content packet fills the slots.
                            // See #428.
                            Inventory blockInventory =
                                    realBe instanceof Inventory realInv
                                            ? realInv
                                            : new SimpleInventory(
                                                    "Grug Inventory", placeholderSize(builder));

                            return new GrugScreen(
                                    new GrugScreenHandler(player, blockInventory, builder),
                                    builder);
                        },
                        GrugBlockEntity::new));
    }

    /** The slots a placeholder inventory has to hold for the layout's crafting grids. */
    private static int placeholderSize(GrugGuiBuilder layout) {
        int size = 0;
        for (GrugGuiBuilder.CraftingGridDef grid : layout.craftingGrids) {
            size = Math.max(size, grid.startSlot() + 9);
        }
        return size;
    }
}
