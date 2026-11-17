package net.grug.minecraft.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The model textures created while a chunk was compiling, waiting to be uploaded outside any
 * display list.
 *
 * <p>OpenGL records a {@code glTexImage2D} made during a display list's compile and runs it when
 * the list does. A texture first created by a compile therefore holds no pixels until that
 * particular list is drawn, and a list rebuilt before then leaves it empty for every other list
 * that binds it, which draws white (#1096). A texture created there is queued here, and the frame
 * hook uploads it for real.
 */
public final class GrugPendingUploads {
    private final Set<String> paths = new LinkedHashSet<>();

    /** Queues a texture, once however often it is asked for. */
    public void add(String path) {
        paths.add(path);
    }

    /** The queued textures in the order they were first queued, which are no longer queued. */
    public List<String> drain() {
        List<String> drained = new ArrayList<>(paths);
        paths.clear();
        return drained;
    }
}
