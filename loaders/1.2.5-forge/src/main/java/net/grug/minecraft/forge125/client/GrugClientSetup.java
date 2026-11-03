package net.grug.minecraft.forge125.client;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;

import net.grug.minecraft.forge125.block.entity.GrugBlockEntity;
import net.minecraft.src.ModLoader;

import java.util.Map;

/**
 * What {@code mod_Grug.load()} does only on a client: the render id, the overlay host and the tick
 * handler. Split out so {@code mod_Grug} names no client type and a dedicated server can load it.
 */
public final class GrugClientSetup {

    private GrugClientSetup() {}

    /**
     * Registers the custom render type for blocks that declare custom geometry and returns its id.
     * FML hands out ids starting at 30, outside RenderBlocks' own switch, so a block returning this
     * reaches {@link GrugClientMod#renderWorldBlock} rather than drawing its cube.
     */
    public static int registerRenderId() {
        return FMLClientHandler.instance().obtainBlockModelIdFor(new GrugClientMod(), false);
    }

    /**
     * Only the client draws screens, and 1.2.5's tick handler is client-only. The overlay's entity
     * is created on the first eligible screen, after every item is registered; see GrugOverlayHost.
     *
     * <p>One handler for both cadences: ModLoader's hooks can only subscribe to render ticks or to
     * game ticks, never both, and the test runner needs the game tick that the other four loaders
     * drive it from, while the title screen is a GUI with no world and so needs the render tick.
     * Register it while the mods load: the first rendered frame is what moves registered handlers
     * into the tick queue, so registering later would silently never tick. See #135.
     */
    public static void init(Map<String, Long> overlayFiles, Map<String, String> overlayMods) {
        GrugOverlayHost.init(overlayFiles, overlayMods);
        FMLCommonHandler.instance().registerTickHandler(new GrugClientHooks());
    }

    /** Registers the block entity with the renderer that draws its frame. */
    public static void registerBlockEntity() {
        ModLoader.registerTileEntity(
                GrugBlockEntity.class, "GrugBlockEntity", new GrugFrameRenderer());
    }
}
