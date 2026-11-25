package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The overlay scripts a client draws through, one entity each, and the exports asked on it.
 *
 * <p>A loader classifies the {@code -Overlay.grug} files at load and hands them here with {@link
 * #init}. The entity of every overlay is made on the first eligible screen rather than at load, so
 * its member scope runs after every item is registered: an overlay that asks the host for an item
 * by index would otherwise see only the items that happened to be registered when the mod was
 * constructed.
 *
 * <p>{@link GrugModsWatcher} reports every overlay file the engine compiled, and the next {@link
 * #ensureCreated} drops that file's script and makes it again from the new file, looking all seven
 * exports up rather than reusing the ids the load found. Only the edited file's overlay is touched,
 * so another mod's overlay keeps its entity and the state its member scope put there. A file that
 * failed to compile never reaches the watcher's reload, so its previous overlay keeps drawing and
 * the compile error is reported the same way a block's is. See #881.
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
        /** The clean name of the file this entity was made from, which a reload matches on. */
        final String name;

        public final long entityHandle;
        final List<GrugObject> owner;
        public final long renderFnId;
        public final long clickFnId;
        public final long keyFnId;
        public final long scrollFnId;
        public final long hudFnId;
        public final long worldFnId;
        public final long worldKeyFnId;
        public final long worldBindingFnId;
        public final String mod;

        Script(
                String name,
                long entityHandle,
                List<GrugObject> owner,
                String mod,
                Entities entities) {
            this.name = name;
            this.entityHandle = entityHandle;
            this.owner = owner;
            this.renderFnId = entities.exportFnId("Overlay", "render");
            this.clickFnId = entities.exportFnId("Overlay", "click");
            this.keyFnId = entities.exportFnId("Overlay", "key");
            this.scrollFnId = entities.exportFnId("Overlay", "scroll");
            this.hudFnId = entities.exportFnId("Overlay", "render_hud");
            this.worldFnId = entities.exportFnId("Overlay", "render_world");
            this.worldKeyFnId = entities.exportFnId("Overlay", "world_key");
            this.worldBindingFnId = entities.exportFnId("Overlay", "world_binding");
            this.mod = mod;
        }
    }

    /**
     * The overlay files the loader classified, by clean name, so the first screen can make them.
     */
    private static Map<String, Long> fileIds = new TreeMap<>();

    /** The mod each overlay file belongs to, by the same clean name. */
    private static Map<String, String> mods = new TreeMap<>();

    /** The scripts to ask, in clean-name order, which is the order the load used. */
    private static final List<Script> scripts = new ArrayList<>();

    /**
     * The clean names of the overlay files the engine compiled again since the last {@link
     * #ensureCreated}, each of which is dropped and made again on the next frame. A sorted set so a
     * batch that reports several files puts them back in the list in the same order the load did.
     */
    private static final Set<String> pending = new TreeSet<>();

    /**
     * Whether {@link #scripts} holds an entity for every file the engine had compiled at the load.
     */
    private static boolean created = false;

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
     * added while the game runs, or one fixed after failing at load, appear on the next frame.
     */
    public static void init(Map<String, Long> files, Map<String, String> overlayMods) {
        fileIds = files;
        mods = overlayMods;
    }

    /**
     * Records that the engine compiled {@code cleanName} again, so that file's overlay alone is
     * made again on the next {@link #ensureCreated}.
     *
     * <p>Called by {@link GrugModsWatcher} for a file that compiled, and never for one that failed
     * to: a broken edit leaves the previous overlay drawing, as it leaves a block's previous
     * declarations in place.
     */
    public static void reloaded(String cleanName, long fileId, String mod) {
        fileIds.put(cleanName, fileId);
        mods.put(cleanName, mod);
        pending.add(cleanName);
    }

    /**
     * Makes an entity for every overlay file, or makes one file's again after it changed, and
     * answers the scripts to ask.
     */
    public static List<Script> scripts() {
        ensureCreated();
        return scripts;
    }

    /**
     * The overlay of {@code mod} that answers its declared key bindings, or null when the mod has
     * no overlay with a {@code world_binding} export. The first in clean-name order is the one
     * asked, so two overlay files of one mod cannot both answer one press of one binding.
     */
    public static Script bindingScript(String mod) {
        for (Script script : scripts()) {
            if (!mod.equals(script.mod)) continue;
            if (script.worldBindingFnId == Grug.INVALID_GRUG_EXPORT_FN_ID) continue;
            return script;
        }
        return null;
    }

    /**
     * The one overlay per mod that answers its declared key bindings, in clean-name order. A test
     * presses a declared key through these, and a loader that registers no binding polls them.
     */
    public static List<Script> bindingScripts() {
        List<Script> answering = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Script script : scripts()) {
            if (script.worldBindingFnId == Grug.INVALID_GRUG_EXPORT_FN_ID) continue;
            if (!seen.add(script.mod)) continue;
            answering.add(script);
        }
        return answering;
    }

    /**
     * Makes one entity per overlay file, or drops the ones made from files the engine compiled
     * again and makes them again from those files.
     *
     * <p>The engine has already re-initialized an edited file's tracked entity on the watcher
     * thread by the time {@link GrugModsWatcher} reports it, so this drop and make is what runs the
     * member scope again on the render thread, where the first entity was made. Only the files in
     * {@link #pending} are dropped: every other overlay keeps the entity and the member state it
     * had, so editing one mod's overlay does not reset another's.
     *
     * <p>The host entities made by the member scope during this call are rooted in the new entity's
     * owner list, which is cleared when that entity is dropped. The ones the engine's own
     * re-initialisation makes are rooted in {@link GrugEntities#retainedFnEntities}, which nothing
     * clears; a block's temporary {@code init} entity leaves its member-scope entities there too,
     * so this path does not add a leak the block path does not have. See #881.
     */
    public static void ensureCreated() {
        GrugStateLock.lock();
        try {
            if (!created) {
                created = true;
                for (Map.Entry<String, Long> entry : fileIds.entrySet()) {
                    addScript(entry.getKey(), entry.getValue());
                }
                pending.clear();
                return;
            }
            if (pending.isEmpty()) return;
            for (String name : pending) {
                dropScript(name);
                addScript(name, fileIds.get(name));
            }
            pending.clear();
        } finally {
            GrugStateLock.unlock();
        }
    }

    /** Makes one overlay's entity and puts its script where the clean-name order puts it. */
    private static void addScript(String name, long fileId) {
        List<GrugObject> owner = new ArrayList<>();
        long handle = entities.create(fileId, owner);
        if (handle == 0) return;
        Script script = new Script(name, handle, owner, mods.getOrDefault(name, ""), entities);
        int at = 0;
        while (at < scripts.size() && scripts.get(at).name.compareTo(name) < 0) at++;
        scripts.add(at, script);
    }

    /** Drops the one entity made from {@code name}'s file, if it is there. */
    private static void dropScript(String name) {
        for (Iterator<Script> iterator = scripts.iterator(); iterator.hasNext(); ) {
            Script script = iterator.next();
            if (!script.name.equals(name)) continue;
            entities.destroy(script.entityHandle);
            script.owner.clear();
            iterator.remove();
            return;
        }
    }

    /** Replaces what the entities are made and asked through, and answers the one in use. */
    static Entities useEntities(Entities replacement) {
        Entities previous = entities;
        entities = replacement;
        return previous;
    }

    /** Forgets every script and file a unit test set up, and points the class back at the state. */
    static void resetForTest() {
        for (Script script : scripts) {
            entities.destroy(script.entityHandle);
            script.owner.clear();
        }
        entities = NATIVE;
        fileIds = new TreeMap<>();
        mods = new TreeMap<>();
        scripts.clear();
        pending.clear();
        created = false;
    }
}
