package net.grug.minecraft.ornithe;

import net.fabricmc.loader.api.FabricLoader;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugEntities;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugModsDirectory;
import net.grug.minecraft.grug.GrugModsWatcher;
import net.grug.minecraft.ornithe.block.GrugBlocks;
import net.ornithemc.osl.blocks.api.BlockEvents;
import net.ornithemc.osl.entrypoints.api.ModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class GrugModLoader implements ModInitializer {

    public static final String MOD_ID = "grug";
    public static final Logger LOGGER = LogManager.getLogger("grug");

    /**
     * Sorted by name, because ids are handed out in this order: the same mods then get the same ids
     * on every run, where a HashMap's order shifted whenever a mod was added (#599).
     */
    public static final Map<String, Long> blockFiles = new TreeMap<>();

    public static final Map<String, Long> itemFiles = new TreeMap<>();

    /**
     * The mod each registered block and item comes from, by the name the maps above key it by, so a
     * name two mods both ship counts once, for the mod whose file is the one registered.
     */
    public static final Map<String, String> blockMods = new HashMap<>();

    public static final Map<String, String> itemMods = new HashMap<>();

    /** The overlay files, by clean name, and the mod each belongs to. */
    public static final Map<String, Long> overlayFiles = new TreeMap<>();

    public static final Map<String, String> overlayMods = new HashMap<>();

    @Override
    public void init() {
        LOGGER.info("Successfully loaded grug (Ornithe)!");

        BlockEvents.REGISTER_BLOCKS.register(GrugBlocks::init);

        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File activeGrugDir =
                GrugModsDirectory.prepare(
                        gameDir, FabricLoader.getInstance().isDevelopmentEnvironment());
        File modApiJson = GrugModsDirectory.modApiJson(gameDir);

        // The adapter that answers for the level and player handles depends on which process this
        // is, and this class is shared with the Alpha loader, so each loader's own OrnitheAdapter
        // picks. See #162 for why the two handles cannot both be the client's.
        GrugCore.initialize(OrnitheAdapter.forCurrentEnvironment(), modApiJson, activeGrugDir);
        FileInfo[] files = Grug.compileAllFiles();

        blockFiles.clear();
        itemFiles.clear();
        overlayFiles.clear();
        blockMods.clear();
        itemMods.clear();
        overlayMods.clear();

        for (GrugFileIndex.Entry entry : GrugFileIndex.classify(files)) {
            FileInfo file = entry.file();
            GrugModsWatcher.fileIds.put(file.path(), file.fileId());

            switch (entry.entityType()) {
                case "Block":
                    blockFiles.put(entry.cleanName(), file.fileId());
                    blockMods.put(entry.cleanName(), entry.mod());
                    break;
                case "BlockEntity":
                    GrugEntities.entityFileIdsByName.put(entry.cleanName(), file.fileId());
                    break;
                case "Item":
                    itemFiles.put(entry.cleanName(), file.fileId());
                    itemMods.put(entry.cleanName(), entry.mod());
                    break;
                case "Overlay":
                    overlayFiles.put(entry.cleanName(), file.fileId());
                    overlayMods.put(entry.cleanName(), entry.mod());
                    break;
                default:
                    break;
            }
        }

        LOGGER.info("Compiled " + files.length + " grug files successfully.");
    }

    public static File getActiveGrugModsDir() {
        return GrugModsDirectory.resolve(
                FabricLoader.getInstance().getGameDir().toFile(),
                FabricLoader.getInstance().isDevelopmentEnvironment());
    }
}
