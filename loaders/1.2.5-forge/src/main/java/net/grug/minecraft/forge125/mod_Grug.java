package net.grug.minecraft.forge125;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.forge125.block.GrugBlocks;
import net.grug.minecraft.forge125.block.GrugBoxRenderer;
import net.grug.minecraft.forge125.client.GrugClientHooks;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugFileIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.src.BaseMod;
import net.minecraft.src.Block;
import net.minecraft.src.IBlockAccess;
import net.minecraft.src.ModLoader;
import net.minecraft.src.RenderBlocks;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The Forge 1.2.5 (Tekkit Classic era) entry point.
 *
 * <p>1.2.5 Forge sits on Risugami's ModLoader, so grug attaches as a {@code mod_} class rather than
 * through Mixin. ModLoader scans the classpath for those, so the loader jar only has to be on the
 * classpath, not in {@code mods/}.
 */
public class mod_Grug extends BaseMod {

    public static final String MOD_ID = "grug";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = Logger.getLogger("grug");

    public static final Map<String, Long> blockFiles = new HashMap<>();
    public static final Map<String, Long> itemFiles = new HashMap<>();

    /**
     * The FML render ID for blocks that declare custom geometry, or -1 before {@link #load()}
     * registers it. FML hands out ids starting at 30, outside RenderBlocks' own switch, so a block
     * returning this reaches FML's renderWorldBlock hook rather than drawing its cube.
     */
    private static int customRenderId = -1;

    /** The mods directory {@link #load()} resolved, which recipe registration reads in return. */
    private static File activeGrugDir;

    /** The running client, or null before it exists. */
    public static Minecraft minecraft() {
        return ModLoader.getMinecraftInstance();
    }

    @Override
    public String getVersion() {
        return VERSION;
    }

    @Override
    public void load() {
        LOGGER.info("Successfully loaded grug (Forge 1.2.5)!");

        File gameDir = ModLoader.getMinecraftInstance().mcDataDir;
        File runGrugDir = new File(gameDir, "grug_mods");
        if (!runGrugDir.exists()) {
            runGrugDir.mkdirs();
        }

        File modApiJson = new File(runGrugDir, "mod_api.json");
        activeGrugDir = getActiveGrugModsDir(runGrugDir);

        try (InputStream in = mod_Grug.class.getResourceAsStream("/mod_api.json")) {
            if (in == null) {
                throw Grug.fatal("/mod_api.json is missing from the grug jar");
            }
            Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw Grug.fatal("Failed to copy mod_api.json", e);
        }

        GrugCore.initialize(new Grug125Adapter(), modApiJson, activeGrugDir);

        // Register the custom render type for blocks that declare custom geometry. FML hands out
        // ids starting at 30, outside RenderBlocks' own switch, so a block returning this reaches
        // FML's renderWorldBlock hook rather than drawing its cube.
        customRenderId = FMLClientHandler.instance().obtainBlockModelIdFor(this, false);

        FileInfo[] files = Grug.compileAllFiles();

        blockFiles.clear();
        itemFiles.clear();
        for (GrugFileIndex.Entry entry : GrugFileIndex.classify(files)) {
            FileInfo file = entry.file();
            Grug.fileIds.put(file.path(), file.fileId());
            String entityType = entry.entityType();
            if ("Block".equals(entityType)) {
                blockFiles.put(entry.cleanName(), file.fileId());
            } else if ("BlockEntity".equals(entityType)) {
                Grug.entityFileIdsByName.put(entry.cleanName(), file.fileId());
            } else if ("Item".equals(entityType)) {
                itemFiles.put(entry.cleanName(), file.fileId());
            }
        }

        LOGGER.info("Compiled " + files.length + " grug files successfully.");

        // One handler for both cadences: ModLoader's hooks can only subscribe to render ticks or to
        // game ticks, never both, and the test runner needs the game tick that the other four
        // loaders drive it from, while the title screen is a GUI with no world and so needs the
        // render tick. Register it while the mods load: the first rendered frame is what moves
        // registered handlers into the tick queue, so registering later would silently never tick.
        // See #135.
        FMLCommonHandler.instance().registerTickHandler(new GrugClientHooks());
    }

    /**
     * Registers the dynamic blocks and items once every other mod has registered its own.
     *
     * <p>1.2.5 mods claim block ids during {@code load()}, and many hardcode the id they expect
     * rather than searching for a free one (BuildCraft's factory module uses 169, Modular Force
     * Field System uses 255). Registering during {@code load()} therefore steals an id and makes
     * the other mod crash. Waiting until {@code modsLoaded()} means every other mod has claimed its
     * ids already, so grug can take one that is genuinely free.
     */
    @Override
    public void modsLoaded() {
        GrugBlocks.init();
        GrugRecipeParser.registerAll(activeGrugDir);
    }

    /**
     * The FML render ID for blocks that declare custom geometry.
     *
     * <p>Read by {@code GrugBlock.getRenderType()} so a custom-rendered block reaches this mod's
     * {@link #renderWorldBlock} hook instead of drawing its cube.
     */
    public static int getCustomRenderId() {
        return customRenderId;
    }

    /**
     * Draws a custom-rendered block's geometry, called by FML when a block's render type is outside
     * RenderBlocks' own switch.
     *
     * <p>This is the 1.2.5 equivalent of Alpha's BlockRendererMixin: the game's own tesselation
     * does the drawing, so lighting, face culling and atlas coordinates come from the game rather
     * than from a second rule kept in step with it. The block does not also draw its cube, because
     * the default case in renderBlockByRenderType does not call renderStandardBlock.
     *
     * <p>The mining overlay is not drawn for a custom-rendered block, because FML's hook replaces
     * the whole tesselation call. That is issue #201 and applies to every loader.
     */
    @Override
    public boolean renderWorldBlock(
            RenderBlocks renderer,
            IBlockAccess world,
            int x,
            int y,
            int z,
            Block block,
            int modelID) {
        return GrugBoxRenderer.render(renderer, world, block, x, y, z);
    }

    /**
     * The directory grug loads mods from. A reference run points this elsewhere so the port is not
     * loaded alongside the mod it recreates; a dev run points at the repository's {@code mods/}.
     */
    public static File getActiveGrugModsDir(File runGrugDir) {
        String override = System.getProperty("grug.mods.dir");
        if (override == null) {
            override = System.getenv("GRUG_MODS_DIR");
        }
        if (override != null && !override.isEmpty()) {
            File dir = new File(override);
            if (!dir.isDirectory()) {
                throw Grug.fatal("Broken grug invariant: GRUG_MODS_DIR is not a directory: " + dir);
            }
            return dir;
        }
        return runGrugDir;
    }
}
