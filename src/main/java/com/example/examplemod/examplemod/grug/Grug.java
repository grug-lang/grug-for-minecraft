package com.example.examplemod.examplemod.grug;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

public final class Grug {
    private static boolean loaded = false;
    public static long statePtr = 0;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try {
            File tempFile = File.createTempFile("libadapter", ".so");
            tempFile.deleteOnExit();
            try (InputStream in = Grug.class.getResourceAsStream("/natives/libadapter.so");
                 OutputStream out = Files.newOutputStream(tempFile.toPath())) {
                if (in == null) {
                    throw new IOException("libadapter.so not found on the classpath at /natives/libadapter.so");
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            System.load(tempFile.getAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load libadapter.so", e);
        }
        loaded = true;
    }

    public static void init(File modApiJson, File modsDir) {
        load();
        if (statePtr != 0) {
            return; // Prevent duplicate initialization
        }
        statePtr = nativeInit(modApiJson.getAbsolutePath(), modsDir.getAbsolutePath());
    }

    public static boolean ping() {
        load();
        return nativeGrugPing();
    }

    private static native boolean nativeGrugPing();
    
    private static native long nativeInit(String modApiPath, String modsDirPath);
}
