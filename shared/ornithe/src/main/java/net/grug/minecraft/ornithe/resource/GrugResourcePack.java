package net.grug.minecraft.ornithe.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.ornithemc.osl.core.api.util.NamespacedIdentifier;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;
import net.ornithemc.osl.core.api.util.function.IOSupplier;
import net.ornithemc.osl.resource.loader.api.resource.ResourceType;
import net.ornithemc.osl.resource.loader.api.resource.pack.AbstractResourcePack;
import net.ornithemc.osl.resource.loader.api.resource.pack.ResourceConsumer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GrugResourcePack extends AbstractResourcePack {

    @Override
    public String getName() {
        return "Grug Generated";
    }

    @Override
    public boolean hasResource(String path) {
        if (path != null && (path.equals("pack.mcmeta") || path.equals("/pack.mcmeta"))) {
            return true;
        }

        try {
            InputStream is = getResource(path);
            if (is != null) {
                is.close();
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public InputStream getResource(String path) throws IOException {
        if (path == null)
            return null;
        if (path.startsWith("/"))
            path = path.substring(1);

        // Supply a dummy pack.mcmeta in-memory so OSL can read the pack properties
        if (path.equals("pack.mcmeta")) {
            String mcmeta = "{\"pack\":{\"pack_format\":1,\"description\":\"Grug Generated Resources\"}}";
            return new ByteArrayInputStream(mcmeta.getBytes(StandardCharsets.UTF_8));
        }

        if (path.startsWith("lang/") && path.endsWith(".lang")) {
            InputStream langStream = openJsonAsLang(path);
            if (langStream != null) {
                return langStream;
            }
        }

        File[] modDirs = GrugModLoader.getActiveGrugModsDir().listFiles(File::isDirectory);
        if (modDirs != null) {
            for (File modDir : modDirs) {
                File file = new File(modDir, path);
                if (file.exists()) {
                    return new FileInputStream(file);
                }
            }
        }

        return null;
    }

    @Override
    protected Map<ResourceType, Set<String>> findNamespaces() {
        Map<ResourceType, Set<String>> map = new HashMap<>();
        Set<String> namespaces = new HashSet<>();
        namespaces.add("grug");

        File[] modDirs = GrugModLoader.getActiveGrugModsDir().listFiles(File::isDirectory);
        if (modDirs != null) {
            for (File modDir : modDirs) {
                File assetsDir = new File(modDir, "assets");
                File[] nsDirs = assetsDir.listFiles(File::isDirectory);
                if (nsDirs != null) {
                    for (File nsDir : nsDirs) {
                        namespaces.add(nsDir.getName());
                    }
                }
                File dataDir = new File(modDir, "data");
                nsDirs = dataDir.listFiles(File::isDirectory);
                if (nsDirs != null) {
                    for (File nsDir : nsDirs) {
                        namespaces.add(nsDir.getName());
                    }
                }
            }
        }
        map.put(ResourceType.CLIENT_ASSETS, namespaces);
        map.put(ResourceType.SERVER_DATA, namespaces);
        return map;
    }

    @Override
    public boolean hasResource(ResourceType type, NamespacedIdentifier id) {
        try {
            IOSupplier<InputStream> supplier = getResource(type, id);
            if (supplier != null) {
                InputStream is = supplier.get();
                if (is != null) {
                    is.close();
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public IOSupplier<InputStream> getResource(ResourceType type, NamespacedIdentifier id) {
        if (id == null)
            return null;

        String namespace = id.namespace();
        String path = id.identifier();

        if (type == ResourceType.CLIENT_ASSETS && path.startsWith("lang/") && path.endsWith(".lang")) {
            return () -> openJsonAsLang("lang/" + path.substring(5, path.length() - 5) + ".lang");
        }

        String baseDir = type == ResourceType.SERVER_DATA ? "data" : "assets";
        File[] modDirs = GrugModLoader.getActiveGrugModsDir().listFiles(File::isDirectory);

        if (modDirs != null) {
            for (File modDir : modDirs) {
                File file = new File(modDir, baseDir + "/" + namespace + "/" + path);
                if (file.exists()) {
                    return () -> new FileInputStream(file);
                }
            }
        }

        return null;
    }

    private InputStream openJsonAsLang(String diskPath) {
        String langName = diskPath.substring(5, diskPath.length() - 5);
        String jsonFileName = langName.toLowerCase() + ".json";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean foundAny = false;

        File[] modDirs = GrugModLoader.getActiveGrugModsDir().listFiles(File::isDirectory);
        if (modDirs != null) {
            for (File modDir : modDirs) {
                File assetsDir = new File(modDir, "assets");
                if (!assetsDir.exists())
                    continue;

                File[] nsDirs = assetsDir.listFiles(File::isDirectory);
                if (nsDirs == null)
                    continue;

                for (File nsDir : nsDirs) {
                    File jsonFile = new File(nsDir, "lang/" + jsonFileName);
                    if (!jsonFile.exists()) {
                        jsonFile = new File(nsDir, "lang/" + langName + ".json");
                    }

                    if (jsonFile.exists()) {
                        foundAny = true;
                        try (FileReader reader = new FileReader(jsonFile, StandardCharsets.UTF_8)) {
                            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                                String key = entry.getKey();
                                String value = entry.getValue().getAsString();

                                if (key.startsWith("block.")) {
                                    key = "tile." + key.substring(6);
                                    if (!key.endsWith(".name"))
                                        key += ".name";
                                } else if (key.startsWith("item.")) {
                                    if (!key.endsWith(".name"))
                                        key += ".name";
                                }

                                String line = key + "=" + value + "\n";
                                out.write(line.getBytes(StandardCharsets.UTF_8));
                            }
                        } catch (Exception e) {
                            GrugModLoader.LOGGER.error("Failed to parse JSON lang file: " + jsonFile, e);
                        }
                    }
                }
            }
        }

        if (!foundAny) {
            return null;
        }

        return new ByteArrayInputStream(out.toByteArray());
    }

    @Override
    public void findResources(ResourceType type, String namespace, String path, ResourceConsumer consumer) {
        String baseDir = type == ResourceType.SERVER_DATA ? "data" : "assets";
        File[] modDirs = GrugModLoader.getActiveGrugModsDir().listFiles(File::isDirectory);

        if (modDirs != null) {
            for (File modDir : modDirs) {
                File targetDir = new File(modDir, baseDir + "/" + namespace + "/" + path);
                if (targetDir.exists() && targetDir.isDirectory()) {
                    try (java.util.stream.Stream<Path> stream = Files.walk(targetDir.toPath())) {
                        stream.filter(Files::isRegularFile).forEach(p -> {
                            String rel = new File(modDir, baseDir + "/" + namespace).toPath()
                                    .relativize(p).toString().replace('\\', '/');

                            if (type == ResourceType.CLIENT_ASSETS && rel.startsWith("lang/")
                                    && rel.endsWith(".json")) {
                                String langRel = rel.substring(0, rel.length() - 5) + ".lang";
                                NamespacedIdentifier targetId = NamespacedIdentifiers.from(namespace, langRel);
                                IOSupplier<InputStream> supplier = getResource(type, targetId);
                                if (supplier != null) {
                                    consumer.accept(targetId, supplier);
                                }
                            }

                            NamespacedIdentifier targetId = NamespacedIdentifiers.from(namespace, rel);
                            IOSupplier<InputStream> supplier = getResource(type, targetId);
                            if (supplier != null) {
                                consumer.accept(targetId, supplier);
                            }
                        });
                    } catch (Exception e) {
                        GrugModLoader.LOGGER.error("Failed to walk resource directory: " + targetDir, e);
                    }
                }
            }
        }
    }
}
