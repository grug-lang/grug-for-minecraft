package net.grug.minecraft.core;

import net.grug.minecraft.grug.Grug;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class GrugTestRunner {
    public static void runAllTests(Object player) {
        List<Map.Entry<String, Long>> tests = new ArrayList<>();
        for (Map.Entry<String, Long> entry : Grug.fileIds.entrySet()) {
            if (entry.getKey().endsWith("-Test.grug")) {
                tests.add(entry);
            }
        }

        int totalCount = tests.size();
        int passedCount = 0;

        String startMsg = "Running " + totalCount + " " + (totalCount == 1 ? "test" : "tests") + "...";
        System.out.println("[GRUG CI] " + startMsg);

        synchronized (Grug.printQueue) {
            Grug.printQueue.add(startMsg);
        }

        for (Map.Entry<String, Long> entry : tests) {
            String path = entry.getKey();
            long fileId = entry.getValue();
            long entityHandle = 0;

            System.out.println("[GRUG CI] Executing test: " + path);

            try {
                entityHandle = Grug.createEntity(fileId);
                if (entityHandle != 0) {
                    long fnId = Grug.getExportFnId("Test", "run");
                    if (fnId != Grug.INVALID_GRUG_EXPORT_FN_ID) {
                        Grug.callExportFn(entityHandle, fnId);

                        System.out.println("[GRUG CI] PASS " + path);

                        synchronized (Grug.printQueue) {
                            Grug.printQueue.add("\u00A7aPASS " + path);
                        }
                        passedCount++;
                    } else {
                        throw new RuntimeException("Test entity missing 'run' export function.");
                    }
                }
            } catch (Exception e) {
                Grug.printQueue.clear();

                String msg = e.getMessage();
                if (msg != null && msg.startsWith("Broken grug invariant: ")) {
                    msg = msg.substring(23);
                } else if (msg == null) {
                    msg = e.toString();
                }

                System.out.println("[GRUG CI] FAIL " + path);
                System.out.println("[GRUG CI] " + msg);

                synchronized (Grug.runtimeErrorQueue) {
                    Grug.runtimeErrorQueue.add("FAIL " + path);
                    Grug.runtimeErrorQueue.add(msg);
                }
                break;
            } finally {
                if (entityHandle != 0) {
                    Grug.destroyEntity(entityHandle);
                }
            }
        }

        if (passedCount == totalCount) {
            System.out.println("[GRUG CI] ALL " + totalCount + " TESTS PASSED");
        }
    }
}
