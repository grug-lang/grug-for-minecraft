package net.grug.minecraft.forge.resource;

import net.grug.minecraft.forge.GrugModLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class GrugPackResources implements PackResources {
    private final PackLocationInfo locationInfo;

    public GrugPackResources(PackLocationInfo locationInfo) {
        this.locationInfo = locationInfo;
    }

    private File getResourceFile(PackType packType, ResourceLocation location) {
        if (packType != PackType.CLIENT_RESOURCES)
            return null;
        File activeGrugDir = GrugModLoader.getActiveGrugModsDir();
        if (!activeGrugDir.exists() || !activeGrugDir.isDirectory())
            return null;

        File[] modDirs = activeGrugDir.listFiles(File::isDirectory);
        if (modDirs == null)
            return null;

        String path = "assets/" + location.getNamespace() + "/" + location.getPath();
        for (File modDir : modDirs) {
            File file = new File(modDir, path);
            if (file.exists() && file.isFile()) {
                return file;
            }
        }
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getRootResource(String... elements) {
        return null;
    }

    @Nullable
    @Override
    public IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        File file = getResourceFile(packType, location);
        if (file != null) {
            return () -> new FileInputStream(file);
        }
        return null;
    }

    @Override
    public void listResources(PackType packType, String namespace, String path, ResourceOutput output) {
        if (packType != PackType.CLIENT_RESOURCES)
            return;
        File activeGrugDir = GrugModLoader.getActiveGrugModsDir();
        if (!activeGrugDir.exists() || !activeGrugDir.isDirectory())
            return;

        File[] modDirs = activeGrugDir.listFiles(File::isDirectory);
        if (modDirs == null)
            return;

        for (File modDir : modDirs) {
            File targetDir = new File(modDir, "assets/" + namespace + "/" + path);
            if (targetDir.exists() && targetDir.isDirectory()) {
                Path targetPath = targetDir.toPath();
                try (Stream<Path> stream = Files.walk(targetPath)) {
                    stream.filter(Files::isRegularFile).forEach(p -> {
                        String rel = targetPath.relativize(p).toString().replace('\\', '/');
                        String fullPath = path.isEmpty() ? rel : path + "/" + rel;
                        ResourceLocation loc = new ResourceLocation(namespace, fullPath);
                        output.accept(loc, () -> Files.newInputStream(p));
                    });
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType packType) {
        return packType == PackType.CLIENT_RESOURCES ? Set.of("grug") : Collections.emptySet();
    }

    @Nullable
    @Override
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
        if (deserializer == PackMetadataSection.TYPE) {
            return (T) new PackMetadataSection(Component.literal("Grug Mod Resources"), 32, Optional.empty());
        }
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return this.locationInfo;
    }

    @Override
    public void close() {
    }
}
