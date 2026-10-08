package net.grug.minecraft.stationapi.events.init;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.grug.FileInfo;
import net.grug.minecraft.grug.Grug;
import net.grug.minecraft.grug.GrugBlockData;
import net.grug.minecraft.grug.GrugFileIndex;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugItemData;
import net.grug.minecraft.grug.GrugModTreeDefect;
import net.grug.minecraft.grug.GrugModsExtractor;
import net.grug.minecraft.grug.GrugRecipeTree;
import net.grug.minecraft.grug.GrugReference;
import net.grug.minecraft.stationapi.StationApiAdapter;
import net.grug.minecraft.stationapi.block.GrugBlock;
import net.grug.minecraft.stationapi.block.entity.GrugBlockEntity;
import net.grug.minecraft.stationapi.item.GrugItem;
import net.mine_diver.unsafeevents.listener.EventListener;
import net.minecraft.block.material.Material;
import net.modificationstation.stationapi.api.StationAPI;
import net.modificationstation.stationapi.api.event.block.entity.BlockEntityRegisterEvent;
import net.modificationstation.stationapi.api.event.mod.InitEvent;
import net.modificationstation.stationapi.api.event.mod.PreInitEvent;
import net.modificationstation.stationapi.api.event.recipe.RecipeRegisterEvent;
import net.modificationstation.stationapi.api.event.registry.BlockRegistryEvent;
import net.modificationstation.stationapi.api.event.registry.ItemRegistryEvent;
import net.modificationstation.stationapi.api.mod.entrypoint.EntrypointManager;
import net.modificationstation.stationapi.api.registry.JsonRecipesRegistry;
import net.modificationstation.stationapi.api.registry.Registry;
import net.modificationstation.stationapi.api.util.Identifier;
import net.modificationstation.stationapi.api.util.Namespace;

import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.net.MalformedURLException;
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
import java.util.Set;

public class InitListener {
    static {
        EntrypointManager.registerLookup(MethodHandles.lookup());
    }

    public static final Namespace NAMESPACE = Namespace.resolve();
    public static final Logger LOGGER = NAMESPACE.getLogger();

    public static final Map<String, Long> itemFiles = new HashMap<>();
    private static final Map<String, Long> blockFiles = new HashMap<>();

    /**
     * Every registered grug block, so the client can reach the ones that draw their own geometry
     * when a resource reload hands it a new texture atlas to point them at.
     */
    private static final List<GrugBlock> grugBlocks = new ArrayList<>();

    /** The registered grug blocks, in registration order. */
    public static List<GrugBlock> getGrugBlocks() {
        return grugBlocks;
    }

    /**
     * What each grug recipe file registered, keyed by its path in the mods tree, so that deleting a
     * file can undo the registration.
     *
     * <p>The {@link URL} instance matters as much as the path: the registry holds a set of them,
     * and a set can only remove an object it already holds.
     */
    private static final Map<String, RegisteredRecipe> registeredRecipes = new HashMap<>();

    private static final class RegisteredRecipe {
        private final Identifier type;
        private final URL url;

        RegisteredRecipe(Identifier type, URL url) {
            this.type = type;
            this.url = url;
        }
    }

    @EventListener
    private static void serverInit(InitEvent event) {
        LOGGER.info(NAMESPACE.toString());
    }

    @GrugGenerated("non-dev-mode: only reached when not running from a dev mods directory")
    public static File getActiveGrugModsDir() {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();

        File override = GrugReference.modsDirOverride();
        if (override != null) {
            if (!override.isDirectory()) {
                throw Grug.fatal("GRUG_MODS_DIR is not a directory: " + override);
            }
            return override;
        }

        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            File devGrugDir = new File(gameDir, "../../../mods");
            if (devGrugDir.exists() && devGrugDir.isDirectory()) {
                return devGrugDir;
            }
        }
        return new File(gameDir, "grug_mods");
    }

    @SuppressWarnings("deprecation")
    @EventListener
    private static void preInit(PreInitEvent event) throws IOException {
        File gameDir = FabricLoader.getInstance().getGameDir().toFile();
        File runGrugDir = new File(gameDir, "grug_mods");

        if (!runGrugDir.exists()) runGrugDir.mkdirs();

        File modApiJson = new File(runGrugDir, "mod_api.json");

        File activeGrugDir = getActiveGrugModsDir();

        if (!activeGrugDir.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
            LOGGER.info(
                    "Dev mode detected: Pointing grug-rs directly to "
                            + activeGrugDir.getCanonicalPath());
        }

        try (InputStream in = InitListener.class.getResourceAsStream("/mod_api.json")) {
            if (in == null) throw Grug.fatal("/mod_api.json is missing from the grug jar");
            Files.copy(in, modApiJson.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        // Only extract if we are actually using the standard run folder
        if (activeGrugDir.getCanonicalPath().equals(runGrugDir.getCanonicalPath())) {
            extractDefaultGrugMods(runGrugDir.toPath());
        }

        // One adapter for both processes for now. The dedicated-server one has to be its own class
        // behind a lazily resolved branch: Fabric Loader will not define a class the game marked
        // server-only in a client, so a class referencing one from here breaks the client, and
        // preInit runs on both sides. See #165.
        GrugCore.initialize(new StationApiAdapter(), modApiJson, activeGrugDir);

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

        registerAutoDiscoveredRecipes(activeGrugDir);
    }

    private static Material stringToMaterial(String materialName) {
        if (materialName == null) return Material.STONE;

        return switch (materialName.toLowerCase()) {
            case "air" -> Material.AIR;
            case "organic", "solid_organic" -> Material.SOLID_ORGANIC;
            case "dirt", "grass", "soil" -> Material.SOIL;
            case "wood" -> Material.WOOD;
            case "stone" -> Material.STONE;
            case "metal", "iron" -> Material.METAL;
            case "water" -> Material.WATER;
            case "lava" -> Material.LAVA;
            case "leaves" -> Material.LEAVES;
            case "plant" -> Material.PLANT;
            case "sponge" -> Material.SPONGE;
            case "cloth", "wool" -> Material.WOOL;
            case "fire" -> Material.FIRE;
            case "sand" -> Material.SAND;
            case "piston_breakable" -> Material.PISTON_BREAKABLE;
            case "glass" -> Material.GLASS;
            case "tnt" -> Material.TNT;
            case "coral", "unused" -> Material.UNUSED;
            case "ice" -> Material.ICE;
            case "snow_layer" -> Material.SNOW_LAYER;
            case "snow", "snow_block" -> Material.SNOW_BLOCK;
            case "cactus" -> Material.CACTUS;
            case "clay" -> Material.CLAY;
            case "pumpkin" -> Material.PUMPKIN;
            case "nether_portal" -> Material.NETHER_PORTAL;
            case "cake" -> Material.CAKE;
            case "cobweb" -> Material.COBWEB;
            case "piston" -> Material.PISTON;
            default -> Material.STONE;
        };
    }

    @EventListener
    private static void registerBlocks(BlockRegistryEvent event) {
        for (Map.Entry<String, Long> entry : blockFiles.entrySet()) {
            String cleanName = entry.getKey();
            Identifier blockId = Identifier.of(NAMESPACE, cleanName);
            long blockFileId = entry.getValue();

            GrugBlockData blockData = new GrugBlockData(blockId.toString());
            Grug.currentlyInitializingBlock = blockData;

            long tempEntityHandle = Grug.createEntity(blockFileId);
            long initFnId = Grug.getExportFnId("Block", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            Grug.declaredBlocks.put(blockId.toString(), blockData);
            Grug.blockDataByFileId.put(blockFileId, blockData);
            Grug.currentlyInitializingBlock = null;

            Material mat = stringToMaterial(blockData.material);
            GrugBlock block = new GrugBlock(blockId, blockFileId, mat, blockData.hardness);
            block.setTranslationKey(blockId.namespace, blockId.path);
            grugBlocks.add(block);
        }
    }

    @EventListener
    private static void registerItems(ItemRegistryEvent event) {
        for (Map.Entry<String, Long> entry : itemFiles.entrySet()) {
            String cleanName = entry.getKey();
            Identifier itemId = Identifier.of(NAMESPACE, cleanName);
            long itemFileId = entry.getValue();

            GrugItemData itemData = new GrugItemData(itemId.toString());

            long tempEntityHandle = Grug.createEntity(itemFileId);
            long initFnId = Grug.getExportFnId("Item", "init");

            if (tempEntityHandle != 0 && initFnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                Grug.callExportFn(tempEntityHandle, initFnId);
            }

            if (tempEntityHandle != 0) {
                Grug.destroyEntity(tempEntityHandle);
            }

            Grug.declaredItems.put(itemId.toString(), itemData);
            Grug.itemDataByFileId.put(itemFileId, itemData);

            new GrugItem(itemId, itemFileId).setTranslationKey(itemId.namespace, itemId.path);
        }
    }

    @EventListener
    private static void registerBlockEntities(BlockEntityRegisterEvent event) {
        // Register a single namespace alias that all GrugBlockEntity instances share
        event.register("grug:generic_block_entity", GrugBlockEntity.class);
    }

    /**
     * Registers every recipe in the mods tree with the game's own recipe registry.
     *
     * <p>The game parses the files itself, so grug only has to decide which type each one belongs
     * to and hand it over. Finding the files and judging their shape is {@link GrugRecipeTree}'s,
     * which reports anything it can describe.
     */
    private static void registerAutoDiscoveredRecipes(File grugModsDir) {
        for (GrugRecipeTree.Recipe recipe : GrugRecipeTree.recipes(grugModsDir)) {
            registerJsonRecipe(recipe);
        }
    }

    private static void registerJsonRecipe(GrugRecipeTree.Recipe recipe) {
        Identifier recipeId = recipeType(recipe.type(), recipe.path());
        if (recipeId == null) return;

        try {
            URL url = createLegacyRecipeUrl(recipe.file().toPath());
            if (!JsonRecipesRegistry.INSTANCE.containsId(recipeId)) {
                Registry.register(JsonRecipesRegistry.INSTANCE, recipeId, new HashSet<>());
            }
            Objects.requireNonNull(JsonRecipesRegistry.INSTANCE.get(recipeId)).add(url);
            registeredRecipes.put(recipe.path(), new RegisteredRecipe(recipeId, url));
        } catch (MalformedURLException e) {
            // A URL this loader builds from a path it has just read cannot be unformable, so the
            // recipe would sit in the registry as something the game can never open.
            throw Grug.fatal("Failed to register the recipe " + recipe.path(), e);
        }
    }

    /**
     * The registry id for a recipe type, or null when the game has no use for the recipe.
     *
     * <p>A type this loader has no recipe type for is not a defect: a mod may ship recipes of a mod
     * it does not require, and the game ignores those exactly as it would ignore one this loader
     * failed to register. A type that is not a resource id names nothing at all, so no recipe
     * behind it can ever be crafted, which is a mod-tree defect and fails the run.
     */
    private static Identifier recipeType(String rawId, String recipePath) {
        if (!GrugRecipeTree.isWellFormedType(rawId)) {
            GrugModTreeDefect.report(
                    "Recipe '" + recipePath + "' has the malformed type '" + rawId + "'.");
            return null;
        }
        if (!isNamespaceLoaded(rawId.substring(0, rawId.indexOf(':')))) {
            return null;
        }
        return Identifier.of(rawId);
    }

    private static boolean isNamespaceLoaded(String namespace) {
        return "minecraft".equals(namespace) || FabricLoader.getInstance().isModLoaded(namespace);
    }

    /**
     * A URL the game can open for a recipe file, whose stream rewrites the modern {@code {"id":
     * "..."}} result to the legacy {@code {"item": "..."}} one, so a mod only has to write it one
     * way.
     */
    private static URL createLegacyRecipeUrl(Path p) throws MalformedURLException {
        return new URL(
                "grugrecipe",
                null,
                -1,
                p.toAbsolutePath().toString(),
                new java.net.URLStreamHandler() {
                    @Override
                    protected java.net.URLConnection openConnection(URL u) {
                        return new java.net.URLConnection(u) {
                            @Override
                            public void connect() {}

                            @Override
                            public InputStream getInputStream() throws IOException {
                                try (InputStreamReader reader =
                                        new InputStreamReader(
                                                new FileInputStream(p.toFile()),
                                                java.nio.charset.StandardCharsets.UTF_8)) {
                                    JsonObject json =
                                            JsonParser.parseReader(reader).getAsJsonObject();
                                    if (json.has("result")) {
                                        JsonObject result = json.getAsJsonObject("result");
                                        if (result.has("id")) {
                                            result.add("item", result.get("id"));
                                            result.remove("id");
                                        }
                                    }
                                    return new ByteArrayInputStream(
                                            json.toString()
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
                                }
                            }
                        };
                    }
                });
    }

    public static void handlePossibleRecipeUpdate(String updatedResourcePath) {
        if (!updatedResourcePath.endsWith(".json")) {
            // A deleted file reaches this path as its parent directory on the polling watchers,
            // which only see the directory's mtime change, and as the file itself on the
            // event-based ones. Checking the registrations under whatever was reported covers
            // both without having to know which watcher is in play.
            unregisterDeletedRecipes(normalized(updatedResourcePath));
            return;
        }

        String[] pathParts = updatedResourcePath.replace('\\', '/').split("/");

        // A valid path needs at least: <mod_name>/data/<namespace>/recipes/<file>.json
        if (pathParts.length < 5
                || !pathParts[1].equals("data")
                || !pathParts[3].equals("recipes")) {
            return;
        }

        File file = new File(getActiveGrugModsDir(), updatedResourcePath);
        if (!file.exists()) {
            // The file is gone, so the recipe it registered is gone with it. Leaving the URL in the
            // registry would keep the deleted recipe in the game until the next restart.
            unregisterRecipe(normalized(updatedResourcePath));
            return;
        }

        try {
            String path = normalized(updatedResourcePath);
            // The hot-reload path judges the one changed file the same way the startup walk does,
            // and reports a file that is no longer a recipe rather than crashing on it.
            String rawType = GrugRecipeTree.readType(file, path);
            if (rawType == null) return;

            Identifier recipeId = recipeType(rawType, path);
            if (recipeId == null) return;

            RegisteredRecipe previous = registeredRecipes.get(path);
            if (previous != null && !previous.type.equals(recipeId)) {
                removeRecipe(previous);
            }

            URL url = createLegacyRecipeUrl(file.toPath());
            if (!JsonRecipesRegistry.INSTANCE.containsId(recipeId)) {
                Registry.register(JsonRecipesRegistry.INSTANCE, recipeId, new HashSet<>());
            }
            Objects.requireNonNull(JsonRecipesRegistry.INSTANCE.get(recipeId)).add(url);
            registeredRecipes.put(path, new RegisteredRecipe(recipeId, url));

            LOGGER.info(
                    "Re-registering recipes of type {} due to change in {}",
                    recipeId,
                    updatedResourcePath);
            StationAPI.EVENT_BUS.post(RecipeRegisterEvent.builder().recipeId(recipeId).build());
        } catch (Exception e) {
            throw Grug.fatal("Failed to hot-reload the recipe " + file, e);
        }
    }

    /** Drops a recipe the run deleted from disk, and asks the game to read what is left. */
    private static void unregisterRecipe(String path) {
        RegisteredRecipe removed = registeredRecipes.remove(path);
        if (removed == null) return;

        removeRecipe(removed);
        LOGGER.info("Removed the deleted recipe {} of type {}", path, removed.type);
        StationAPI.EVENT_BUS.post(RecipeRegisterEvent.builder().recipeId(removed.type).build());
    }

    /**
     * Drops every registered recipe under {@code updatedPath} whose file is no longer there.
     *
     * <p>The reported path may be a directory, so only the registrations under it are candidates,
     * and one that still has its file is left alone: a directory's mtime also changes when a file
     * is added, which is not a removal.
     */
    private static void unregisterDeletedRecipes(String updatedPath) {
        String prefix = updatedPath + "/";
        for (String path : new ArrayList<>(registeredRecipes.keySet())) {
            if (!path.startsWith(prefix)) continue;
            if (new File(getActiveGrugModsDir(), path).exists()) continue;
            unregisterRecipe(path);
        }
    }

    private static void removeRecipe(RegisteredRecipe recipe) {
        Set<URL> recipes = JsonRecipesRegistry.INSTANCE.get(recipe.type);
        if (recipes != null) {
            recipes.remove(recipe.url);
        }
    }

    /** The recipe's own path in the mods tree, which is how a recipe is keyed and reported. */
    private static String normalized(String path) {
        return path.replace('\\', '/');
    }

    @GrugGenerated("non-dev-mode: only reached when not running from a dev mods directory")
    private static void extractDefaultGrugMods(Path targetGrugDir) {
        Path markerFile = targetGrugDir.resolve(".examples_generated.txt");

        Optional<ModContainer> modContainer =
                FabricLoader.getInstance().getModContainer(NAMESPACE.toString());
        if (modContainer.isEmpty()) return;

        Optional<Path> defaultModsPath = modContainer.get().findPath("mods");
        if (defaultModsPath.isEmpty()) return;

        // A file that cannot be copied aborts the extraction rather than being collected. The
        // marker is written only once the walk finishes, so carrying on would leave a mods
        // directory that is missing files while claiming to be complete, and no later run would
        // look for them again.
        try {
            GrugModsExtractor.extract(defaultModsPath.get(), targetGrugDir, markerFile);
        } catch (IOException e) {
            throw Grug.fatal("Failed to extract the default grug mods", e);
        }
    }
}
