package net.grug.minecraft.grug;

import net.minecraft.block.entity.BlockEntity;
import net.grug.minecraft.events.init.InitListener;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

public final class Grug {
    private static boolean loaded = false;
    public static long statePtr = 0;
    public static final long INVALID_GRUG_FILE_ID = -1L;
    public static final long INVALID_GRUG_EXPORT_FN_ID = -1L;

    public static final WeakGrugValueMap entityData = new WeakGrugValueMap();
    private static final Map<GrugEntityType, Integer> nextEntityIndices = new HashMap<>();
    public static final Map<String, Long> fileIds = new HashMap<>();

    public static BlockEntity currentlyInitializingBlockEntity = null;
    public static GrugBlockData currentlyInitializingBlock = null;
    public static GrugItemData currentlyInitializingItem = null;

    public static final Map<net.modificationstation.stationapi.api.util.Identifier, GrugBlockData> declaredBlocks = new HashMap<>();
    public static final Map<Long, GrugBlockData> blockDataByFileId = new HashMap<>();

    public static final Map<net.modificationstation.stationapi.api.util.Identifier, GrugItemData> declaredItems = new HashMap<>();
    public static final Map<Long, GrugItemData> itemDataByFileId = new HashMap<>();

    public static final List<GrugObject> globalFnEntities = new ArrayList<>();
    public static List<GrugObject> fnEntities = globalFnEntities;

    public static final Map<String, Long> entityFileIdsByName = new HashMap<>();

    public static final List<TagContribution> declaredTags = new ArrayList<>();
    public static final List<String> declaredRecipes = new ArrayList<>();

    public static final Queue<String> runtimeErrorQueue = new ArrayDeque<>();
    public static final Queue<String> printQueue = new ArrayDeque<>();

    public record TagContribution(String namespace, String path) {
    }

    static {
        for (GrugEntityType type : GrugEntityType.values()) {
            nextEntityIndices.put(type, 0);
        }
    }

    public static synchronized void load() {
        if (loaded)
            return;
        try {
            File tempFile = File.createTempFile("libadapter", ".so");
            tempFile.deleteOnExit();
            try (InputStream in = Grug.class.getResourceAsStream("/natives/libadapter.so");
                    OutputStream out = Files.newOutputStream(tempFile.toPath())) {
                if (in == null) {
                    throw new IOException("libadapter.so not found on the classpath");
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            System.load(tempFile.getAbsolutePath());
            initGrugAdapter();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load libadapter.so", e);
        }
        loaded = true;
    }

    public static void init(File modApiJson, File modsDir) {
        load();
        if (statePtr != 0)
            return;
        statePtr = nativeInit(modApiJson.getAbsolutePath(), modsDir.getAbsolutePath());
    }

    public static FileInfo[] compileAllFiles() {
        if (statePtr == 0)
            throw new IllegalStateException("grug_state is not initialized");
        return nativeCompileAllFiles(statePtr);
    }

    public static String[] update(Consumer<String> onError) {
        if (statePtr == 0)
            return new String[0];

        FileInfo[] updatedFiles = nativeUpdate(statePtr);
        List<String> reloadTriggers = new ArrayList<>();

        for (FileInfo file : updatedFiles) {
            if (file.fileId() == INVALID_GRUG_FILE_ID) {
                String errorMsg = "Failed to hot-reload " + file.fileName() + ":\n" + file.errorString();
                InitListener.LOGGER.error(errorMsg);
                if (onError != null) {
                    onError.accept(errorMsg);
                }
            } else {
                boolean isNew = !fileIds.containsKey(file.path());
                fileIds.put(file.path(), file.fileId());

                // Maintain the dynamic entity linking map
                if ("BlockEntity".equals(file.entityType())) {
                    String cleanName = file.entityName().contains("-") ? file.entityName().split("-")[0]
                            : file.entityName();
                    entityFileIdsByName.put(cleanName, file.fileId());
                }

                if (!isNew) {
                    InitListener.LOGGER.info("Successfully hot-reloaded {} with file ID {}", file.path(),
                            file.fileId());

                    GrugBlockData blockData = blockDataByFileId.get(file.fileId());
                    if (blockData != null) {
                        blockData.langPaths.clear();
                        blockData.blockEntityString = null;

                        currentlyInitializingBlock = blockData;
                        long tempEntityHandle = createEntity(file.fileId());
                        long initFnId = getExportFnId("Block", "init");

                        if (tempEntityHandle != 0 && initFnId != INVALID_GRUG_EXPORT_FN_ID) {
                            callExportFn(tempEntityHandle, initFnId);
                        }

                        if (tempEntityHandle != 0) {
                            destroyEntity(tempEntityHandle);
                        }
                        currentlyInitializingBlock = null;

                        reloadTriggers.add(file.path());
                    }
                }
            }
        }

        for (String resource : nativeGetUpdatedResources(statePtr)) {
            reloadTriggers.add(resource);
        }

        return reloadTriggers.toArray(new String[0]);
    }

    public static void onRuntimeError(String reason) {
        InitListener.LOGGER.error(reason);
        synchronized (runtimeErrorQueue) {
            runtimeErrorQueue.add(reason);
        }
    }

    public static long addEntity(GrugEntityType type, Object object) {
        GrugObject grugObject = new GrugObject(type, object);
        int index = nextEntityIndices.get(type);
        nextEntityIndices.put(type, index + 1);
        long id = ((long) type.ordinal() << 32) | (index & 0xFFFFFFFFL);

        entityData.put(id, grugObject);
        if (fnEntities != null)
            fnEntities.add(grugObject);
        return id;
    }

    public static void addEntityWithId(long id, GrugEntityType type, Object object) {
        GrugObject grugObject = new GrugObject(type, object);
        entityData.put(id, grugObject);
        if (fnEntities != null)
            fnEntities.add(grugObject);
    }

    public static long createEntity(long fileId) {
        return nativeCreateEntity(statePtr, fileId);
    }

    public static long getEntityId(long entityHandle) {
        return nativeGetEntityId(statePtr, entityHandle);
    }

    public static long getExportFnId(String entityType, String fnName) {
        return nativeGetExportFnId(statePtr, entityType, fnName);
    }

    public static boolean callExportFn(long entityHandle, long exportFnId) {
        return nativeCallExportFn(statePtr, entityHandle, exportFnId);
    }

    public static void destroyEntity(long entityHandle) {
        nativeDestroyEntity(statePtr, entityHandle);
    }

    private static native void initGrugAdapter();

    private static native long nativeInit(String modApiPath, String modsDirPath);

    private static native FileInfo[] nativeCompileAllFiles(long statePtr);

    private static native FileInfo[] nativeUpdate(long statePtr);

    private static native long nativeCreateEntity(long statePtr, long fileId);

    private static native long nativeGetEntityId(long statePtr, long entityHandle);

    private static native long nativeGetExportFnId(long statePtr, String entityType, String fnName);

    private static native boolean nativeCallExportFn(long statePtr, long entityHandle, long exportFnId);

    private static native void nativeDestroyEntity(long statePtr, long entityHandle);

    private static native String[] nativeGetUpdatedResources(long statePtr);

    public static native void gameFunctionErrorHappened(long statePtr, String message);
}
