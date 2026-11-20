package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The overlay scripts a client draws through, one entity each, and the exports asked on it.
 *
 * <p>A loader classifies the {@code -Overlay.grug} files at load and hands them here with {@link
 * #init}. The entity of every overlay is made on the first eligible screen rather than at load, so
 * its member scope runs after every item is registered: an overlay that asks the host for an item
 * by index would otherwise see only the items that happened to be registered when the mod was
 * constructed.
 *
 * <p>An edited overlay script is made again, the way a block's and an item's are, rather than
 * keeping the entity and the export function ids the load found: {@link GrugModsWatcher} reports
 * every overlay file the engine compiled, and the next {@link #ensureCreated} drops the entities
 * and makes them again from the new files, looking the export ids up rather than reusing them. A
 * file that failed to compile never reaches the watcher's reload, so its previous overlay keeps
 * drawing and the compile error is reported the same way a block's is. See #881.
 */
public final class GrugOverlayScripts {

    /** The way an overlay's entity is made, dropped and its exports looked up. */
    interface Entities {
        long create(long fileId, List<GrugObject> owner);

        void destroy(long entityHandle);

        long exportFnId(String entityType, String fnName);
    }

    /** The live grug state, which is what a loader's overlays go through. */
    static final Entities NATIVE =
            new Entities() {
                @Override
                public long create(long fileId, List<GrugObject> owner) {
                    return GrugEntities.createEntityWithOwner(fileId, owner, null);
                }

                @Override
                public void destroy(long entityHandle) {
                    Grug.destroyEntity(entityHandle);
                }

                @Override
                public long exportFnId(String entityType, String fnName) {
                    return Grug.getExportFnId(entityType, fnName);
                }
            };

    /**
     * What the entities are made and asked through. A unit test replaces it to count the creates,
     * the destroys and the export lookups of a reload, which no game test can observe.
     */
    private static Entities entities = NATIVE;

    /**
     * One overlay script's entity, and the exports of that file, asked by the loader each frame.
     */
    public static final class Script {
        public final long entityHandle;
        final List<GrugObject> owner;
        public final long renderFnId;
        public final long clickFnId;
        public final long keyFnId;
        public final long scrollFnId;
        public final long hudFnId;
        public final long worldFnId;
        public final long worldKeyFnId;
        public final String mod;

        Script(long entityHandle, List<GrugObject> owner, String mod, Entities entities) {
            this.entityHandle = entityHandle;
            this.owner = owner;
            this.renderFnId = entities.exportFnId("Overlay", "render");
            this.clickFnId = entities.exportFnId("Overlay", "click");
            this.keyFnId = entities.exportFnId("Overlay", "key");
            this.scrollFnId = entities.exportFnId("Overlay", "scroll");
            this.hudFnId = entities.exportFnId("Overlay", "render_hud");
            this.worldFnId = entities.exportFnId("Overlay", "render_world");
            this.worldKeyFnId = entities.exportFnId("Overlay", "world_key");
            this.mod = mod;
        }
    }

    /**
     * The overlay files the loader classified, by clean name, so the first screen can make them.
     */
    private static Map<String, Long> fileIds = new TreeMap<>();

    /** The mod each overlay file belongs to, by the same clean name. */
    private static Map<String, String> mods = new TreeMap<>();

    private static final List<Script> scripts = new ArrayList<>();

    /** Whether {@link #scripts} holds an entity for every file the engine has compiled so far. */
    private static boolean created = false;

    /**
     * How many overlay files the engine has compiled since the load, and how many of them the
     * scripts in {@link #scripts} were made from. The two are read and written under the state
     * lock, the watcher's own hold and {@link #ensureCreated}'s.
     */
    private static int reloads = 0;

    private static int builtReloads = 0;

    @GrugGenerated("utility class: never instantiated")
    private GrugOverlayScripts() {}

    /**
     * The overlay files to ask, by clean name, with the mod each belongs to.
     *
     * <p>The maps are held rather than copied, because on a loader whose client initializer and
     * file classification are separate entrypoints either can run first, and holding the loader's
     * own map lets the client see every file whichever order they ran in. The maps must be mutable,
     * because a later compile is recorded into them. Called once at load; a file the engine
     * compiles later arrives through {@link #reloaded} instead, which is what makes an overlay
     * added while the game runs appear on the next frame.
     */
    public static void init(Map<String, Long> files, Map<String, String> overlayMods) {
        fileIds = files;
        mods = overlayMods;
    }

    /**
     * Records that the engine compiled {@code cleanName} again, so the overlay is made again from
     * the new file on the next {@link #ensureCreated}.
     *
     * <p>Called by {@link GrugModsWatcher} for a file that compiled, and never for one that failed
     * to: a broken edit leaves the previous overlay drawing, as it leaves a block's previous
     * declarations in place.
     */
    public static void reloaded(String cleanName, long fileId, String mod) {
        fileIds.put(cleanName, fileId);
        mods.put(cleanName, mod);
        reloads++;
    }

    /**
     * Makes an entity for every overlay file, or makes them all again after one of their files
     * changed, and answers the scripts to ask.
     */
    public static List<Script> scripts() {
        ensureCreated();
        return scripts;
    }

    /**
     * Makes one entity per overlay file, or drops the ones made from older files and makes them
     * again, once the engine has compiled an overlay file since the last time.
     *
     * <p>The reload's entities are dropped rather than re-initialized by the engine alone, so the
     * member scope runs on a fresh entity and the host entities it created for the previous file
     * are released with the owner list. The export ids are looked up on the new entity rather than
     * carried over from the load.
     */
    public static void ensureCreated() {
        GrugStateLock.lock();
        try {
            if (created && builtReloads == reloads) return;
            if (created) dropScripts();
            created = true;
            builtReloads = reloads;
            for (Map.Entry<String, Long> entry : fileIds.entrySet()) {
                List<GrugObject> owner = new ArrayList<>();
                long handle = entities.create(entry.getValue(), owner);
                if (handle == 0) continue;
                scripts.add(
                        new Script(handle, owner, mods.getOrDefault(entry.getKey(), ""), entities));
            }
        } finally {
            GrugStateLock.unlock();
        }
    }

    /** Drops every overlay entity, so nothing keeps drawing a file the engine compiled again. */
    private static void dropScripts() {
        for (Script script : scripts) {
            entities.destroy(script.entityHandle);
            script.owner.clear();
        }
        scripts.clear();
    }

    /** Replaces what the entities are made and asked through, and answers the one in use. */
    static Entities useEntities(Entities replacement) {
        Entities previous = entities;
        entities = replacement;
        return previous;
    }

    /** Forgets every script and file a unit test set up, and points the class back at the state. */
    static void resetForTest() {
        if (created) dropScripts();
        entities = NATIVE;
        fileIds = new TreeMap<>();
        mods = new TreeMap<>();
        scripts.clear();
        created = false;
        reloads = 0;
        builtReloads = 0;
    }
}
