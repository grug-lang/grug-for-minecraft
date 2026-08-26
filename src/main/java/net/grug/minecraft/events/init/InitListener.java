package net.grug.minecraft.events.init;

import com.google.gson.Gson;
import net.grug.minecraft.block.GrugBlock;
import net.grug.minecraft.block.entity.GrugBlockEntity;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.item.GrugItem;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.modificationstation.stationapi.api.event.block.entity.BlockEntityRegisterEvent;
import net.modificationstation.stationapi.api.event.mod.InitEvent;
import net.modificationstation.stationapi.api.event.mod.PreInitEvent;
import net.modificationstation.stationapi.api.event.registry.BlockRegistryEvent;
import net.modificationstation.stationapi.api.event.registry.ItemRegistryEvent;
import net.modificationstation.stationapi.api.mod.entrypoint.EntrypointManager;
import net.modificationstation.stationapi.api.registry.JsonRecipesRegistry;
import net.modificationstation.stationapi.api.registry.Registry;
import net.modificationstation.stationapi.api.util.Identifier;
import net.modificationstation.stationapi.api.util.Namespace;
import net.modificationstation.stationapi.api.util.exception.MissingModException;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public class InitListener {
    static {
        EntrypointManager.registerLookup(MethodHandles.lookup());
    }

    public static final Namespace NAMESPACE = Namespace.resolve();
    public static final Logger LOGGER = NAMESPACE.getLogger();

    public static final Map<String, Long> itemFiles = new HashMap<>();
    private static final Map<String, Long> blockFiles = new HashMap<>();

    @EventListener
    private static void serverInit(InitEvent event) {
        LOGGER.info(NAMESPACE.toString());
    }

    public static File getActiveGrugModsDir() {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            File devGrugDir = new File(gameDir, "../src/main/resources/default_grug_mods");
            if (devGrugDir.exists() && devGrugDir.isDirectory()) {
                return devGrugDir;
            }
        }
        return new File(gameDir, "grug_mods");
    }

    // Runs on PreInitEvent (earlier than BlockRegistryEvent/ItemRegistryEvent)
    // because StationAPI's JsonRecipesLoader also scans for recipes on
    // PreInitEvent. grug's own recipes/tags need to exist before that scan
    // finishes, so all compiling and mod-level init() script execution happens
    // here now, with block/item synthesis deferred to their own registry events.
    @EventListener
    private static void preInit(PreInitEvent event) {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File runGrugDir = new File(gameDir, "grug_mods");

        if (!runGrugDir.exists())
            runGrugDir.mkdirs();

        File modApiJson = new File(runGrugDir, "mod_api.json");

        File activeGrugDir = getActiveGrugModsDir();

        try {
            if (!activeGrugDir.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
                LOGGER.info("Dev mode detected: Pointing grug-rs directly to " + activeGrugDir.getCanonicalPath());
            }

            try (InputStream in = InitListener.class.getResourceAsStream("/mod_api.json")) {
                if (in != null)
                    Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            // Only extract if we are actually using the standard run folder
            if (activeGrugDir.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
                extractDefaultGrugMods(runGrugDir.toPath());
            }

            Grug.init(modApiJson, activeGrugDir);

            FileInfo[] files = Grug.compileAllFiles();

            List<Long> modFileIds = new ArrayList<>();
            blockFiles.clear();
            itemFiles.clear();

            for (FileInfo file : files) {
                if (file.fileId() == Grug.INVALID_GRUG_FILE_ID) {
                    LOGGER.error("Failed to compile {}: \n{}", file.path(), file.errorString());
                    continue;
                }

                Grug.fileIds.put(file.path(), file.fileId());

                String cleanName = file.entityName().contains("-") ? file.entityName().split("-")[0]
                        : file.entityName();

                if ("Block".equals(file.entityType())) {
                    blockFiles.put(cleanName, file.fileId());
                } else if ("BlockEntity".equals(file.entityType())) {
                    Grug.entityFileIdsByName.put(cleanName, file.fileId());
                } else if ("Mod".equals(file.entityType())) {
                    modFileIds.add(file.fileId());
                } else if ("Item".equals(file.entityType())) {
                    itemFiles.put(cleanName, file.fileId());
                }
            }

            // Run mod-level init() scripts before blocks get synthesized, so
            // tags/recipes are registered before anything might need them.
            long modInitFnId = Grug.getExportFnId("Mod", "init");
            if (modInitFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                for (long modFileId : modFileIds) {
                    long tempEntityHandle = Grug.createEntity(modFileId);
                    if (tempEntityHandle != 0) {
                        Grug.callExportFn(tempEntityHandle, modInitFnId);
                        Grug.destroyEntity(tempEntityHandle);
                    }
                }
            }

            registerDeclaredRecipes(activeGrugDir);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize grug-rs", e);
        }
    }

    @EventListener
    private static void registerBlocks(BlockRegistryEvent event) {
        // Synthesize and register the Block instances (compiling/mod-init already
        // happened in preInit)
        for (Map.Entry<String, Long> entry : blockFiles.entrySet()) {
            String cleanName = entry.getKey();

            Identifier blockId = Identifier.of(NAMESPACE, cleanName);

            long blockFileId = entry.getValue();

            GrugBlockData blockData = new GrugBlockData(blockId);
            Grug.currentlyInitializingBlock = blockData;

            long tempEntityHandle = Grug.createEntity(blockFileId);
            long initFnId = Grug.getExportFnId("Block", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            Grug.declaredBlocks.put(blockId, blockData);
            Grug.blockDataByFileId.put(blockFileId, blockData);
            Grug.currentlyInitializingBlock = null;

            new GrugBlock(blockId, blockFileId).setTranslationKey(blockId.namespace, blockId.path);
        }
    }

    @EventListener
    private static void registerItems(ItemRegistryEvent event) {
        for (Map.Entry<String, Long> entry : itemFiles.entrySet()) {
            String cleanName = entry.getKey();
            Identifier itemId = Identifier.of(NAMESPACE, cleanName);
            long itemFileId = entry.getValue();

            GrugItemData itemData = new GrugItemData(itemId);
            Grug.currentlyInitializingItem = itemData;

            long tempEntityHandle = Grug.createEntity(itemFileId);
            long initFnId = Grug.getExportFnId("Item", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            Grug.declaredItems.put(itemId, itemData);
            Grug.itemDataByFileId.put(itemFileId, itemData);
            Grug.currentlyInitializingItem = null;

            new GrugItem(itemId, itemFileId).setTranslationKey(itemId.namespace, itemId.path);
        }
    }

    @EventListener
    private static void registerBlockEntities(BlockEntityRegisterEvent event) {
        // Register a single namespace alias that all GrugBlockEntity instances share
        event.register("grug:generic_block_entity", GrugBlockEntity.class);
    }

    // Mirrors JsonRecipesLoader.registerRecipe, but reads Grug.declaredRecipes
    // off disk instead of scanning the classpath, since grug mods are loose
    // files rather than jar resources.
    private static void registerDeclaredRecipes(File grugModsDir) {
        for (String recipePath : Grug.declaredRecipes) {
            File file = new File(grugModsDir, recipePath);
            if (!file.exists()) {
                LOGGER.warn("Declared recipe not found on disk: " + file.getAbsolutePath());
                continue;
            }
            try {
                registerJsonRecipe(file.toURI().toURL());
            } catch (Exception e) {
                LOGGER.error("Failed to register recipe: " + file, e);
            }
        }
    }

    private static void registerJsonRecipe(URL recipe) throws IOException {
        String rawId;
        try (InputStreamReader reader = new InputStreamReader(recipe.openStream())) {
            rawId = new Gson().fromJson(reader, RecipeTypeHolder.class).type;
        }

        Identifier recipeId;
        try {
            recipeId = Identifier.of(rawId);
        } catch (MissingModException e) {
            LOGGER.warn("Found an unknown recipe type " + rawId + ". Ignoring.");
            return;
        }

        if (!JsonRecipesRegistry.INSTANCE.containsId(recipeId))
            Registry.register(JsonRecipesRegistry.INSTANCE, recipeId, new HashSet<>());
        Objects.requireNonNull(JsonRecipesRegistry.INSTANCE.get(recipeId)).add(recipe);
    }

    // Minimal stand-in for stationapi's internal JsonRecipeType, since that
    // class isn't guaranteed accessible outside its package. Only needs to
    // match the "type" field Gson reads from each recipe JSON.
    private static class RecipeTypeHolder {
        String type;
    }

    private static void extractDefaultGrugMods(Path targetGrugDir) {
        Path markerFile = targetGrugDir.resolve(".examples_generated.txt");

        // If the marker file exists, the player already generated them.
        // We respect their right to delete the 'foo' folder without it coming back.
        if (Files.exists(markerFile)) {
            return;
        }

        Optional<ModContainer> modContainer = FabricLoader.getInstance().getModContainer(NAMESPACE.toString());
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

            // Write the marker file so we never forcefully extract again
            String msg = "This file tells the mod that the default examples have already been generated.\n" +
                    "If you delete the example folders, they won't come back.\n" +
                    "If you WANT the examples back, delete this file and restart the game.\n";
            Files.writeString(markerFile, msg);

        } catch (IOException e) {
            LOGGER.error("Failed to walk default_grug_mods directory", e);
        }
    }
}
