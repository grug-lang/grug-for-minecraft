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

        /**
         * The entity's handle, filled in once the file's member scope has run: a script made here
         * is bound before it exists, so a screen the member scope creates can belong to it.
         */
        public long entityHandle;

        final List<GrugObject> owner;
        public long renderFnId;
        public long clickFnId;
        public long keyFnId;
        public long scrollFnId;
        public long hudFnId;
        public long worldFnId;
        public long worldKeyFnId;
        public long worldBindingFnId;
        public long screenInitFnId;
        public long screenRenderFnId;
        public long screenClickFnId;
        public long screenKeyFnId;
        public long screenScrollFnId;
        public long screenWidgetChangedFnId;
        public long screenListRowFnId;
        public long screenClosedFnId;
        public final String mod;

        Script(String name, long entityHandle, List<GrugObject> owner, String mod) {
            this.name = name;
            this.entityHandle = entityHandle;
            this.owner = owner;
            this.mod = mod;
        }

        /**
         * Looks the file's exports up on the entity that was just made. It runs only once the
         * member scope returned a handle, so a file whose entity could not be made is asked
         * nothing, and a screen it made before failing is dropped without a lookup it cannot use.
         */
        void lookUpExports(Entities entities) {
            this.renderFnId = entities.exportFnId("Overlay", "render");
            this.clickFnId = entities.exportFnId("Overlay", "click");
            this.keyFnId = entities.exportFnId("Overlay", "key");
            this.scrollFnId = entities.exportFnId("Overlay", "scroll");
            this.hudFnId = entities.exportFnId("Overlay", "render_hud");
            this.worldFnId = entities.exportFnId("Overlay", "render_world");
            this.worldKeyFnId = entities.exportFnId("Overlay", "world_key");
            this.worldBindingFnId = entities.exportFnId("Overlay", "world_binding");
            this.screenInitFnId = entities.exportFnId("Overlay", "screen_init");
            this.screenRenderFnId = entities.exportFnId("Overlay", "screen_render");
            this.screenClickFnId = entities.exportFnId("Overlay", "screen_click");
            this.screenKeyFnId = entities.exportFnId("Overlay", "screen_key");
            this.screenScrollFnId = entities.exportFnId("Overlay", "screen_scroll");
            this.screenWidgetChangedFnId = entities.exportFnId("Overlay", "screen_widget_changed");
            this.screenListRowFnId = entities.exportFnId("Overlay", "screen_list_row");
            this.screenClosedFnId = entities.exportFnId("Overlay", "screen_closed");
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
     * The mod of each live script, by the entity handle a call is made on, so {@link #modOfEntity}
     * answers with a lookup rather than a search: every caller of {@link GrugOverlay#callExport}
     * already holds a handle a live script made, so a search could only end without a match on a
     * handle no caller passes.
     */
    private static final Map<Long, String> modsByHandle = new TreeMap<>();

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
     * The overlays of {@code mod} that may answer its declared key bindings, in clean-name order,
     * which the caller asks in turn and takes the first answer from.
     *
     * <p>The engine looks an export id up by entity type and function name, not by file, so the id
     * cannot say which file of the mod exports {@code world_binding}: when one file of the program
     * has the export every script carries its id, and calling it on a file without the export
     * answers false without running anything. Asking the mod's files in order is therefore what
     * makes its second overlay the handler when the first one does not export it (#1749's review).
     */
    public static List<Script> bindingScripts(String mod) {
        List<Script> answering = new ArrayList<>();
        for (Script script : scripts()) {
            if (!mod.equals(script.mod)) continue;
            if (script.worldBindingFnId == Grug.INVALID_GRUG_EXPORT_FN_ID) continue;
            answering.add(script);
        }
        return answering;
    }

    /**
     * The mod whose overlay script {@code entityHandle} is, or null when no live overlay script has
     * that handle.
     *
     * <p>It is what a host entry with no mod-relative literal of its own, such as {@code
     * String.translate}, uses to learn which mod is calling: the dispatch passes only the entity
     * handle, which names the script and so the mod it was made for. A handle from a test entity or
     * a block entity belongs to no overlay script and answers null; a test's own dispatch sets the
     * mod through {@link GrugScriptDispatch#begin} instead.
     */
    public static String modOfEntity(long entityHandle) {
        return modsByHandle.get(entityHandle);
    }

    /**
     * Every overlay that may answer a declared key binding, in clean-name order. A test presses a
     * declared key through these, and a loader that registers no binding polls them; the first
     * overlay of a mod that answers is the one whose handler runs.
     */
    public static List<Script> bindingScripts() {
        List<Script> answering = new ArrayList<>();
        for (Script script : scripts()) {
            if (script.worldBindingFnId == Grug.INVALID_GRUG_EXPORT_FN_ID) continue;
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
        Script script = new Script(name, 0, owner, mods.getOrDefault(name, ""));
        GrugOwnedScreens.beginScriptCreation(script);
        long handle;
        try {
            handle = entities.create(fileId, owner);
        } finally {
            GrugOwnedScreens.endScriptCreation();
        }
        if (handle == 0) {
            // The member scope may have made screens before it failed; the entity that would have
            // called their exports does not exist, so they are dropped without asking.
            GrugOwnedScreens.scriptDropped(script);
            return;
        }
        script.entityHandle = handle;
        modsByHandle.put(handle, script.mod);
        script.lookUpExports(entities);
        int at = 0;
        while (at < scripts.size() && scripts.get(at).name.compareTo(name) < 0) at++;
        scripts.add(at, script);
    }

    /** Drops the one entity made from {@code name}'s file, if it is there. */
    private static void dropScript(String name) {
        for (Iterator<Script> iterator = scripts.iterator(); iterator.hasNext(); ) {
            Script script = iterator.next();
            if (!script.name.equals(name)) continue;
            GrugOwnedScreens.scriptDropped(script);
            modsByHandle.remove(script.entityHandle);
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
            GrugOwnedScreens.scriptDropped(script);
            entities.destroy(script.entityHandle);
            script.owner.clear();
        }
        entities = NATIVE;
        fileIds = new TreeMap<>();
        mods = new TreeMap<>();
        modsByHandle.clear();
        scripts.clear();
        pending.clear();
        created = false;
        GrugOwnedScreens.resetForTest();
    }
}
