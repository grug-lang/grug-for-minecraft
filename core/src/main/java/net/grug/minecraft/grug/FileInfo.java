package net.grug.minecraft.grug;

public record FileInfo(
        String path,
        String fileName,
        String modName,
        String entityType,
        String entityName,
        long fileId,
        String errorString) {
}
