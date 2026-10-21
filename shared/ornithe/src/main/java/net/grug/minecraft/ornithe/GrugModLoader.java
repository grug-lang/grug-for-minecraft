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

    public static final Map<String, Long> blockFiles = new HashMap<>();
    public static final Map<String, Long> itemFiles = new HashMap<>();

    /** How many block and item ids each grug mod needs, by the name of its directory. */
    public static final Map<String, Integer> blockCountsByMod = new TreeMap<>();

    public static final Map<String, Integer> itemCountsByMod = new TreeMap<>();

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
        blockCountsByMod.clear();
        itemCountsByMod.clear();

        for (GrugFileIndex.Entry entry : GrugFileIndex.classify(files)) {
            FileInfo file = entry.file();
            GrugModsWatcher.fileIds.put(file.path(), file.fileId());

            switch (entry.entityType()) {
                case "Block":
                    blockFiles.put(entry.cleanName(), file.fileId());
                    blockCountsByMod.merge(entry.mod(), 1, Integer::sum);
                    break;
                case "BlockEntity":
                    GrugEntities.entityFileIdsByName.put(entry.cleanName(), file.fileId());
                    break;
                case "Item":
                    itemFiles.put(entry.cleanName(), file.fileId());
                    itemCountsByMod.merge(entry.mod(), 1, Integer::sum);
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
