package net.grug.minecraft.ornithe;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.grug.Grug;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class GrugModLoader implements ModInitializer {

    public static final String MOD_ID = "grug";
    public static final Logger LOGGER = LogManager.getLogger("Grug");

    public static final Map<String, Long> blockFiles = new HashMap<>();
    public static final Map<String, Long> itemFiles = new HashMap<>();

    @Override
    public void init() {
        LOGGER.info("Successfully loaded Grug into Alpha 1.1.2_01 (Ornithe)!");

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
                LOGGER.info("Dev mode detected: Pointing grug-rs directly to " + activeGrugDir.getCanonicalPath());
            }
        } catch (IOException e) {
            LOGGER.error("Failed to resolve canonical path", e);
        }

        try (InputStream in = GrugModLoader.class.getResourceAsStream("/mod_api.json")) {
            if (in != null) {
                Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to copy mod_api.json", e);
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

        for (FileInfo file : files) {
            if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
                throw new RuntimeException("Failed to compile " + file.path() + ":\n" + file.errorString());
            }

            String[] pathParts = file.path().replace('\\', '/').split("/");
            if (pathParts.length < 2 || !pathParts[1].equals("code")) {
                throw new RuntimeException(
                        "Grug file misplaced! '" + file.path() + "' must be placed inside a 'code/' directory.");
            }

            Grug.fileIds.put(file.path(), file.fileId());

            String cleanName = file.entityName().contains("-") ? file.entityName().split("-")[0]
                    : file.entityName();

            if ("Block".equals(file.entityType())) {
                blockFiles.put(cleanName, file.fileId());
            } else if ("BlockEntity".equals(file.entityType())) {
                Grug.entityFileIdsByName.put(cleanName, file.fileId());
            } else if ("Item".equals(file.entityType())) {
                itemFiles.put(cleanName, file.fileId());
            }
        }

        LOGGER.info("Compiled " + files.length + " Grug files successfully.");
    }

    public static File getActiveGrugModsDir() {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            File devGrugDir = new File(gameDir, "../../../core/src/main/resources/default_grug_mods");
            if (devGrugDir.exists() && devGrugDir.isDirectory()) {
                return devGrugDir;
            }
        }
        return new File(gameDir, "grug_mods");
    }

    private static void extractDefaultGrugMods(Path targetGrugDir) {
        Path markerFile = targetGrugDir.resolve(".examples_generated.txt");

        if (Files.exists(markerFile)) {
            return;
        }

        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (modContainer.isEmpty())
            return;

        Optional<Path> defaultModsPath = modContainer.get().findPath("default_grug_mods");
        if (defaultModsPath.isEmpty())
            return;

        Path srcRoot = defaultModsPath.get();
        try (Stream<Path> stream = Files.walk(srcRoot)) {
            stream.forEach(source -> {
                try {
                    Path relative = srcRoot.relativize(source);
                    Path target = targetGrugDir.resolve(relative.toString());

                    if (Files.isDirectory(source)) {
                        if (!Files.exists(target))
                            Files.createDirectories(target);
                    } else {
                        if (!Files.exists(target))
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    LOGGER.error("Failed to extract default grug mod file: " + source, e);
                }
            });

            String msg = "This file tells the mod that the default examples have already been generated.\n" +
                    "If you delete the example folders, they won't come back.\n" +
                    "If you WANT the examples back, delete this file and restart the game.\n";
            Files.writeString(markerFile, msg);

        } catch (IOException e) {
            LOGGER.error("Failed to walk default_grug_mods directory", e);
        }
    }
}
