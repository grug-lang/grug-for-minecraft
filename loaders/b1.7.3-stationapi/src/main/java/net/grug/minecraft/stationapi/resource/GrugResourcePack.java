package net.grug.minecraft.stationapi.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.grug.minecraft.stationapi.events.init.InitListener;
import net.modificationstation.stationapi.api.resource.InputSupplier;
import net.modificationstation.stationapi.api.resource.ResourcePack;
import net.modificationstation.stationapi.api.resource.ResourceType;
import net.modificationstation.stationapi.api.util.Identifier;
import net.modificationstation.stationapi.api.util.Namespace;
import net.modificationstation.stationapi.impl.resource.AbstractFileResourcePack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GrugResourcePack extends AbstractFileResourcePack {

    public GrugResourcePack() {
        super("Grug Generated", true);
    }

    private static Namespace namespaceOf(String raw) {
        return Identifier.of(raw + ":_").getNamespace();
    }

    @Override
    public Set<Namespace> getNamespaces(ResourceType type) {
        Set<Namespace> namespaces = new HashSet<>();
        namespaces.add(InitListener.NAMESPACE);

        File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
        if (modDirs != null) {
            String baseDir = type == ResourceType.SERVER_DATA ? "data" : "assets";
            for (File modDir : modDirs) {
                File typeDir = new File(modDir, baseDir);
                File[] nsDirs = typeDir.listFiles(File::isDirectory);
                if (nsDirs != null) {
                    for (File nsDir : nsDirs) {
                        namespaces.add(namespaceOf(nsDir.getName()));
                    }
                }
            }
        }
        return namespaces;
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... segments) {
        return null;
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        if (id == null)
            return null;

        String path = id.getPath();
        String diskPath = path.startsWith("stationapi/") ? path.substring(11) : path;

        // Tags require merging arrays
        if (path.startsWith("stationapi/tags/")) {
            return mergeTagFragments(id.getNamespace(), diskPath);
        }

        if (!id.getNamespace().equals(InitListener.NAMESPACE)) {
            return null;
        }

        // Intercept StationAPI requests for .lang files and generate them purely from
        // JSON files on disk
        if (path.startsWith("stationapi/lang/") && path.endsWith(".lang")) {
            return openJsonAsLang(id.getNamespace(), diskPath);
        }

        // Standard files (Textures, Models, Blockstates, Recipes)
        String baseDir = type == ResourceType.SERVER_DATA ? "data" : "assets";
        File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);

        if (modDirs != null) {
            for (File modDir : modDirs) {
                File file = new File(modDir, baseDir + "/" + id.getNamespace() + "/" + diskPath);
                if (file.exists()) {
                    return () -> new FileInputStream(file);
                }
            }
        }

        return null;
    }

    private InputSupplier<InputStream> openJsonAsLang(Namespace namespace, String diskPath) {
        // Extract language code from diskPath (e.g. "lang/en_US.lang" -> "en_US")
        String langName = diskPath.substring("lang/".length(), diskPath.length() - ".lang".length());
        String jsonFileName = langName.toLowerCase() + ".json";

        return () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
            boolean foundAny = false;

            if (modDirs != null) {
                for (File modDir : modDirs) {
                    File jsonFile = new File(modDir, "assets/" + namespace + "/lang/" + jsonFileName);
                    if (!jsonFile.exists()) {
                        // Fallback check for exact-cased JSON files (e.g. en_US.json)
                        jsonFile = new File(modDir, "assets/" + namespace + "/lang/" + langName + ".json");
                    }

                    if (jsonFile.exists()) {
                        foundAny = true;
                        try (FileReader reader = new FileReader(jsonFile, StandardCharsets.UTF_8)) {
                            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                                String key = entry.getKey();
                                String value = entry.getValue().getAsString();

                                // Map modern JSON key formats to legacy StationAPI key formats
                                if (key.startsWith("block.")) {
                                    key = "tile." + key.substring("block.".length());
                                    if (!key.endsWith(".name")) {
                                        key = key + ".name";
                                    }
                                } else if (key.startsWith("item.")) {
                                    if (!key.endsWith(".name")) {
                                        key = key + ".name";
                                    }
                                }

                                String line = key + "=" + value + "\n";
                                out.write(line.getBytes(StandardCharsets.UTF_8));
                            }
                        } catch (Exception e) {
                            InitListener.LOGGER.error("Failed to parse JSON lang file: " + jsonFile, e);
                        }
                    }
                }
            }

            if (!foundAny) {
                return null;
            }
            return new ByteArrayInputStream(out.toByteArray());
        };
    }

    @Override
    public void findResources(ResourceType type, Namespace namespace, String prefix,
            ResourcePack.ResultConsumer consumer) {
        String diskPrefix = prefix.startsWith("stationapi/") ? prefix.substring(11) : prefix;

        // Allow any namespace for tags, but restrict others to the mod's namespace
        if (!prefix.startsWith("stationapi/tags") && !namespace.equals(InitListener.NAMESPACE)) {
            return;
        }

        String baseDir = type == ResourceType.SERVER_DATA ? "data" : "assets";
        File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);

        if (modDirs != null) {
            Set<Identifier> discoveredIds = new HashSet<>();
            for (File modDir : modDirs) {
                File targetDir = new File(modDir, baseDir + "/" + namespace + "/" + diskPrefix);
                if (targetDir.exists() && targetDir.isDirectory()) {
                    try (java.util.stream.Stream<Path> stream = Files.walk(targetDir.toPath())) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> {
                                    String str = p.toString();
                                    return str.endsWith(".png") || str.endsWith(".json");
                                })
                                .forEach(p -> {
                                    String relativePath = new File(modDir, baseDir + "/" + namespace).toPath()
                                            .relativize(p).toString().replace('\\', '/');

                                    // If a JSON lang file is discovered, expose virtual .lang IDs to StationAPI
                                    if (relativePath.startsWith("lang/") && relativePath.endsWith(".json")) {
                                        String langName = relativePath.substring("lang/".length(),
                                                relativePath.length() - ".json".length());

                                        Identifier targetId1 = Identifier.of(namespace,
                                                "stationapi/lang/" + langName + ".lang");
                                        if (discoveredIds.add(targetId1)) {
                                            InputSupplier<InputStream> supplier = this.open(type, targetId1);
                                            if (supplier != null) {
                                                consumer.accept(targetId1, supplier);
                                            }
                                        }

                                        // Register en_US.lang alias for StationAPI's default locale if en_us.json is
                                        // present
                                        if (langName.equalsIgnoreCase("en_us")) {
                                            Identifier targetId2 = Identifier.of(namespace,
                                                    "stationapi/lang/en_US.lang");
                                            if (discoveredIds.add(targetId2)) {
                                                InputSupplier<InputStream> supplier = this.open(type, targetId2);
                                                if (supplier != null) {
                                                    consumer.accept(targetId2, supplier);
                                                }
                                            }
                                        }
                                    } else {
                                        Identifier targetId = Identifier.of(namespace, "stationapi/" + relativePath);
                                        if (discoveredIds.add(targetId)) {
                                            InputSupplier<InputStream> supplier = this.open(type, targetId);
                                            if (supplier != null) {
                                                consumer.accept(targetId, supplier);
                                            }
                                        }
                                    }
                                });
                    } catch (Exception e) {
                        InitListener.LOGGER.error("Failed to walk resource directory: " + targetDir, e);
                    }
                }
            }
        }
    }

    private static InputSupplier<InputStream> mergeTagFragments(Namespace namespace, String diskPath) {
        JsonArray mergedValues = new JsonArray();
        boolean found = false;

        File[] modDirs = InitListener.getActiveGrugModsDir().listFiles(File::isDirectory);
        if (modDirs != null) {
            for (File modDir : modDirs) {
                File file = new File(modDir, "data/" + namespace + "/" + diskPath);
                if (file.exists()) {
                    found = true;
                    try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                        JsonObject fragment = JsonParser.parseReader(reader).getAsJsonObject();
                        if (fragment.has("values")) {
                            for (var value : fragment.getAsJsonArray("values")) {
                                mergedValues.add(value);
                            }
                        }
                    } catch (Exception e) {
                        InitListener.LOGGER.error("Failed to parse tag fragment: " + file, e);
                    }
                }
            }
        }

        if (!found) {
            return null;
        }

        JsonObject merged = new JsonObject();
        merged.add("values", mergedValues);
        byte[] bytes = merged.toString().getBytes(StandardCharsets.UTF_8);
        return () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void close() {
    }
}
