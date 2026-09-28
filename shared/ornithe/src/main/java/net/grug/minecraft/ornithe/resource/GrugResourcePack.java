package net.grug.minecraft.ornithe.resource;

import net.grug.minecraft.grug.GrugResourceIndex;
import net.grug.minecraft.ornithe.GrugModLoader;
import net.ornithemc.osl.core.api.util.NamespacedIdentifier;
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers;
import net.ornithemc.osl.core.api.util.function.IOSupplier;
import net.ornithemc.osl.resource.loader.api.resource.ResourceType;
import net.ornithemc.osl.resource.loader.api.resource.pack.AbstractResourcePack;
import net.ornithemc.osl.resource.loader.api.resource.pack.ResourceConsumer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serves the grug mods' generated assets and data.
 *
 * <p>The filesystem work lives in {@link GrugResourceIndex} so Java tests can cover it; this class
 * only adapts it to OSL's resource pack API.
 */
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
        if (path == null) return null;
        if (path.startsWith("/")) path = path.substring(1);

        // Supply a dummy pack.mcmeta in-memory so OSL can read the pack properties
        if (path.equals("pack.mcmeta")) {
            String mcmeta =
                    "{\"pack\":{\"pack_format\":1,\"description\":\"Grug Generated Resources\"}}";
            return new ByteArrayInputStream(mcmeta.getBytes(StandardCharsets.UTF_8));
        }

        if (path.startsWith("lang/") && path.endsWith(".lang")) {
            InputStream langStream = openJsonAsLang(path);
            if (langStream != null) {
                return langStream;
            }
        }

        File file =
                GrugResourceIndex.findDirectResource(GrugModLoader.getActiveGrugModsDir(), path);
        return file != null ? new FileInputStream(file) : null;
    }

    @Override
    protected Map<ResourceType, Set<String>> findNamespaces() {
        Set<String> namespaces =
                GrugResourceIndex.findNamespaces(GrugModLoader.getActiveGrugModsDir());
        namespaces.add("grug");

        Map<ResourceType, Set<String>> map = new HashMap<>();
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
        if (id == null) return null;

        String namespace = id.namespace();
        String path = id.identifier();

        if (type == ResourceType.CLIENT_ASSETS
                && path.startsWith("lang/")
                && path.endsWith(".lang")) {
            return () -> openJsonAsLang("lang/" + path.substring(5, path.length() - 5) + ".lang");
        }

        String baseDir = type == ResourceType.SERVER_DATA ? "data" : "assets";
        File file =
                GrugResourceIndex.findResource(
                        GrugModLoader.getActiveGrugModsDir(), baseDir, namespace, path);
        return file != null ? () -> new FileInputStream(file) : null;
    }

    private InputStream openJsonAsLang(String diskPath) {
        String langName = diskPath.substring(5, diskPath.length() - 5);
        byte[] merged = GrugResourceIndex.mergeLang(GrugModLoader.getActiveGrugModsDir(), langName);
        return merged != null ? new ByteArrayInputStream(merged) : null;
    }

    @Override
    public void findResources(
            ResourceType type, String namespace, String path, ResourceConsumer consumer) {
        String baseDir = type == ResourceType.SERVER_DATA ? "data" : "assets";

        List<String> failures = new ArrayList<>();
        List<String> relatives =
                GrugResourceIndex.findResources(
                        GrugModLoader.getActiveGrugModsDir(), baseDir, namespace, path, failures);
        for (String failure : failures) {
            GrugModLoader.LOGGER.error(failure);
        }

        for (String rel : relatives) {
            if (type == ResourceType.CLIENT_ASSETS
                    && rel.startsWith("lang/")
                    && rel.endsWith(".json")) {
                String langRel = rel.substring(0, rel.length() - 5) + ".lang";
                NamespacedIdentifier langId = NamespacedIdentifiers.from(namespace, langRel);
                IOSupplier<InputStream> supplier = getResource(type, langId);
                if (supplier != null) {
                    consumer.accept(langId, supplier);
                }
            }

            NamespacedIdentifier targetId = NamespacedIdentifiers.from(namespace, rel);
            IOSupplier<InputStream> supplier = getResource(type, targetId);
            if (supplier != null) {
                consumer.accept(targetId, supplier);
            }
        }
    }
}
