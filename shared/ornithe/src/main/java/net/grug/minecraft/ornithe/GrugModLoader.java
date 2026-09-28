package net.grug.minecraft.ornithe;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugModsExtractor;
import net.grug.minecraft.ornithe.block.GrugBlocks;
import net.ornithemc.osl.blocks.api.BlockEvents;
import net.ornithemc.osl.entrypoints.api.ModInitializer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class GrugModLoader implements ModInitializer {

    public static final String MOD_ID = "grug";
    public static final Logger LOGGER = LogManager.getLogger("grug");

    public static final Map<String, Long> blockFiles = new HashMap<>();
    public static final Map<String, Long> itemFiles = new HashMap<>();

    @Override
    public void init() {
        LOGGER.info("Successfully loaded grug (Ornithe)!");

        BlockEvents.REGISTER_BLOCKS.register(GrugBlocks::init);

        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File runGrugDir = new File(gameDir, "grug_mods");

        if (!runGrugDir.exists()) {
            runGrugDir.mkdirs();
        }

        File modApiJson = new File(runGrugDir, "mod_api.json");
        File activeGrugDir = getActiveGrugModsDir();

        try {
            if (!activeGrugDir.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
                LOGGER.info(
                        "Dev mode detected: Pointing grug-rs directly to "
                                + activeGrugDir.getCanonicalPath());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to resolve canonical path", e);
        }

        try (InputStream in = GrugModLoader.class.getResourceAsStream("/mod_api.json")) {
            if (in == null) {
                throw Grug.fatal("/mod_api.json is missing from the grug jar");
            }
            Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw Grug.fatal("Failed to copy mod_api.json", e);
        }

        try {
            if (activeGrugDir.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
                extractDefaultGrugMods(runGrugDir.toPath());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to extract default grug mods", e);
        }

        GrugCore.initialize(new OrnitheAdapter(), modApiJson, activeGrugDir);
        FileInfo[] files = Grug.compileAllFiles();

        blockFiles.clear();
        itemFiles.clear();

        for (GrugFileIndex.Entry entry : GrugFileIndex.classify(files)) {
            FileInfo file = entry.file();
            Grug.fileIds.put(file.path(), file.fileId());

            switch (entry.entityType()) {
                case "Block" -> blockFiles.put(entry.cleanName(), file.fileId());
                case "BlockEntity" ->
                        Grug.entityFileIdsByName.put(entry.cleanName(), file.fileId());
                case "Item" -> itemFiles.put(entry.cleanName(), file.fileId());
                default -> {}
            }
        }

        LOGGER.info("Compiled " + files.length + " grug files successfully.");
    }

    @GrugGenerated("non-dev-mode: only reached when not running from a dev mods directory")
    public static File getActiveGrugModsDir() {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            File devGrugDir = new File(gameDir, "../../../mods");
            if (devGrugDir.exists() && devGrugDir.isDirectory()) {
                return devGrugDir;
            }
        }
        return new File(gameDir, "grug_mods");
    }

    @GrugGenerated("non-dev-mode: only reached when not running from a dev mods directory")
    private static void extractDefaultGrugMods(Path targetGrugDir) {
        Path markerFile = targetGrugDir.resolve(".examples_generated.txt");

        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (modContainer.isEmpty()) return;

        Optional<Path> defaultModsPath = modContainer.get().findPath("mods");
        if (defaultModsPath.isEmpty()) return;

        List<String> errors = new ArrayList<>();
        try {
            GrugModsExtractor.extract(defaultModsPath.get(), targetGrugDir, markerFile, errors);
        } catch (IOException e) {
            LOGGER.error("Failed to walk mods directory", e);
        }
        for (String error : errors) {
            LOGGER.error(error);
        }
    }
}
