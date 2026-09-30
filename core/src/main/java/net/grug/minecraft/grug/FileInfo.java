package net.grug.minecraft.grug;

import java.util.Objects;

public final class FileInfo {
    private final String path;
    private final String fileName;
    private final String modName;
    private final String entityType;
    private final String entityName;
    private final long fileId;
    private final String errorString;

    public FileInfo(
            String path,
            String fileName,
            String modName,
            String entityType,
            String entityName,
            long fileId,
            String errorString) {
        this.path = path;
        this.fileName = fileName;
        this.modName = modName;
        this.entityType = entityType;
        this.entityName = entityName;
        this.fileId = fileId;
        this.errorString = errorString;
    }

    public String path() {
        return path;
    }

    public String fileName() {
        return fileName;
    }

    public String modName() {
        return modName;
    }

    public String entityType() {
        return entityType;
    }

    public String entityName() {
        return entityName;
    }

    public long fileId() {
        return fileId;
    }

    public String errorString() {
        return errorString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileInfo)) return false;
        FileInfo other = (FileInfo) o;
        return fileId == other.fileId
                && Objects.equals(path, other.path)
                && Objects.equals(fileName, other.fileName)
                && Objects.equals(modName, other.modName)
                && Objects.equals(entityType, other.entityType)
                && Objects.equals(entityName, other.entityName)
                && Objects.equals(errorString, other.errorString);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, fileName, modName, entityType, entityName, fileId, errorString);
    }

    @Override
    public String toString() {
        return "FileInfo[path="
                + path
                + ", fileName="
                + fileName
                + ", modName="
                + modName
                + ", entityType="
                + entityType
                + ", entityName="
                + entityName
                + ", fileId="
                + fileId
                + ", errorString="
                + errorString
                + "]";
    }
}
