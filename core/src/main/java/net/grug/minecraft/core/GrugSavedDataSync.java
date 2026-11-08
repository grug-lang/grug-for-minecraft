package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugPower;
import net.grug.minecraft.grug.GrugSavedLists;
import net.grug.minecraft.grug.GrugSavedNumbers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

/**
 * Sends a grug block entity's saved data to the client's copy of it when the script changes it or
 * asks for a redraw, so that a {@code set_saved_number}, a saved list or the energy store reaches
 * the frame pass or chunk mesh that reads it.
 *
 * <p>Every loader's grug block entity writes that data into its NBT, and the client's copy reads it
 * from the chunk when it arrives. On a dedicated server the script runs on the server's copy, so
 * nothing the client holds changes with it, and data that the frame pass draws is never meshed
 * again for it: the packet has to be sent by itself. The rule is the same on every loader, so it
 * lives here and each loader only supplies the things only it can answer ({@link Transport}).
 *
 * <p>A block entity that may draw per frame is sent when its data changes, because its frame pass
 * reads the numbers on the next frame and nothing meshes the block again for them. The change send
 * never rebuilds a mesh: on 1.20.6 it is the game's own data packet without the redraw flag, and on
 * the four older loaders the packet's redraw boolean is false. {@link #redrawn} is the other path,
 * for the geometry in the chunk's mesh: the client's copy is given the data and the mark that makes
 * it build the mesh again, and a change to data that only the mesh draws therefore waits for the
 * script's {@code redraw}. On a client that shares the world (singleplayer and the integrated
 * server) {@code redraw} re-meshes that one world and the number is already there.
 *
 * <p>A send is left out when the data is what was last sent to that block entity, because a script
 * may save the same value every tick, and a machine's tick is not a reason for a packet. The data
 * is compared as a canonical string of what the client's frame pass reads, not as the loader's NBT:
 * the older loaders' NBT has no value equality, and a copy of it is a new object every time.
 *
 * <p>A redraw waits for the flush on a deferring side too, not only a change. A client drops a
 * saved-data packet whose block is not at the position yet, and a block placed in the same tick as
 * the script's {@code redraw} reaches the client in the flush's block changes; sending the redraw
 * first would leave the client's copy waiting for a first data packet that never comes (#1211).
 */
public final class GrugSavedDataSync {

    /**
     * What only a loader can answer about the block entity whose data changed.
     *
     * <p>{@link #send} is installed by the side that owns the block entity, which for the older
     * loaders is a dedicated server alone: their singleplayer has no second copy of the world to
     * send to, and their server's player list is in classes a client never loads.
     */
    public interface Transport {
        /** Whether this process simulates the block entity, rather than holding a client's copy. */
        boolean onServer(Object blockEntity);

        /**
         * Whether the block entity may draw per frame, so a saved change reaches the client's frame
         * pass without the block's mesh being built again.
         *
         * <p>Every loader answers with whether the block draws its own geometry, which is what
         * makes the game call {@code render_frame} at all. The {@code render_frame} export id
         * cannot answer it: grug builds its table of export ids from {@code mod_api.json} rather
         * than from the files that are loaded, so the id resolves for every script and a check
         * against it has an arm no test can take. A block whose geometry is in its chunk's mesh
         * answers yes too, so it is sent the data on a change, which arrives without a rebuild and
         * is drawn when its mesh is next built.
         */
        boolean drawsFrame(Object blockEntity);

        /** Sends the block entity's saved data to the clients that hold it. */
        void send(Object blockEntity);

        /**
         * Sends the block update that makes those clients build the block's mesh again.
         *
         * <p>On a side that defers sends this waits for the flush, like {@link #send}: a block
         * placed in the same tick reaches the client in that flush's block changes, so the redraw
         * has to follow them or the client drops it for a block that is not there yet (#1211).
         */
        void redraw(Object blockEntity);

        /**
         * Whether {@link #send} may wait for {@link GrugSavedDataSync#flush} instead of going out
         * on every save, which is what a loader whose connection has a small outbound queue wants.
         *
         * <p>A test can tick a level inside one of its own ticks (#780), a thousand times in a
         * burst, and a script that saves a travelling item every tick would otherwise queue a
         * packet per saved field per tick: the a1 and b1.7.3 connections drop the player when a
         * burst crosses the megabyte their outbound queue holds, which is #1191. The client's copy
         * only ever needs the latest state, so a loader that answers true here is sent one packet
         * per block entity, at the flush. A redraw is held the same way, so it is sent after the
         * flush's block changes.
         *
         * <p>False by default, so a loader that installs no flush keeps sending at once.
         */
        default boolean defersSends() {
            return false;
        }

        /**
         * The level the block entity belongs to, which scopes everything held about it: a flush of
         * one level sends and drops only that level's changes, and the level's record is held
         * weakly so a level that goes away takes its held changes with it.
         *
         * <p>Null when this side does not name its levels, which keeps one record for the whole
         * side, as every loader had before a side could defer. A side that defers and answers null
         * here is sent its change at once rather than held for a flush no level could reach.
         */
        default Object levelOf(Object blockEntity) {
            return null;
        }
    }

    /**
     * One level's saved-data state: what was last sent to each of its block entities, and the
     * changes a deferring side is holding for its next flush.
     *
     * <p>The map is keyed weakly by the level and the block entities in a record are weak too, so
     * neither a level that went away nor one of its broken block entities is kept alive by a held
     * change. A block entity in a loaded level is strongly reachable from its chunk, so a weak hold
     * cannot lose a change that still has a client copy to reach; only a change whose block entity
     * was removed from the world goes, and that one has nowhere to go.
     */
    private static final Map<Object, Pending> LEVELS = new WeakHashMap<>();

    private static final class Pending {
        /** What was last sent to each block entity, keyed weakly so a broken one is collected. */
        private final Map<Object, String> lastSent = new WeakHashMap<>();

        /** The block entities this level is waiting to send, at most once each. */
        private final Set<Object> held = Collections.newSetFromMap(new WeakHashMap<>());

        /**
         * The block entities this level is waiting to send with their mesh rebuilt, at the flush. A
         * redraw carries the data itself, so one of these replaces a held change of the same block
         * entity rather than being sent after it.
         */
        private final Set<Object> heldRedraws = Collections.newSetFromMap(new WeakHashMap<>());
    }

    /**
     * The joined client's copies that have not been sent their data yet, keyed weakly so a copy
     * that is gone is collected.
     *
     * <p>A client joined to a server has no copy of a block entity's saved data until the server
     * sends it: these loaders' chunk packets carry no tile entity data, and the client's copy is
     * made from the block alone. Its render pass therefore reads empty numbers and lists for a
     * moment, and a block whose geometry depends on them draws nothing, which {@code
     * set_custom_render} would report as a defect. The copy is marked here when it is made, the
     * first data, from the loader's own block entity update or from the chunk's NBT, clears it, and
     * the render path draws nothing and reports nothing while it is set.
     *
     * <p>It is only set for a joined client, never for the server's own copy or a singleplayer
     * world, where the copy the render pass reads is the one the script wrote.
     */
    private static final Set<Object> AWAITING_FIRST_DATA =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static Transport transport = null;

    @GrugGenerated("utility class: never instantiated")
    private GrugSavedDataSync() {}

    /**
     * Whether this side applies a saved-data packet, which is the client alone: the data travels
     * server to client, so a dedicated server that applied one would write a client's copy into the
     * world it simulates. Every loader's apply path starts with this, which is also what refuses a
     * packet a client spoofs.
     */
    public static boolean appliesOn(GrugSide side) {
        return !side.isDedicatedServer();
    }

    /**
     * Installs the side that sends, which a dedicated server does once it is up.
     *
     * <p>A different side starts with nothing: what the last one held was for its own client
     * copies, and sending one of those through the new side would be a packet for the wrong world.
     */
    public static synchronized void install(Transport installed) {
        if (installed != transport) {
            LEVELS.clear();
        }
        transport = installed;
    }

    /**
     * A saved number, a saved list or the energy store changed, so the client's copy is sent the
     * block entity's data unless it already holds it.
     */
    public static synchronized void changed(Object blockEntity) {
        Transport installed = transport;
        if (installed == null) {
            return;
        }
        if (!installed.onServer(blockEntity)) {
            return;
        }
        if (!installed.drawsFrame(blockEntity)) {
            return;
        }
        Object level = installed.levelOf(blockEntity);
        Pending pending = LEVELS.computeIfAbsent(level, unused -> new Pending());
        String now = snapshot(blockEntity);
        if (now.equals(pending.lastSent.get(blockEntity))) {
            return;
        }
        pending.lastSent.put(blockEntity, now);
        if (!installed.defersSends()) {
            installed.send(blockEntity);
        } else if (level == null) {
            // A deferring side that does not say which level holds the change has nowhere to
            // flush it from, so it is sent at once rather than held forever.
            installed.send(blockEntity);
        } else if (!pending.heldRedraws.contains(blockEntity)) {
            // A held redraw is built from the block entity at the flush, so it carries this newer
            // data too, and a second plain packet would only repeat it.
            pending.held.add(blockEntity);
        }
    }

    /**
     * Sends every change a deferring transport has held for any level, one packet per block entity
     * carrying the data it holds now, and forgets what it sent.
     *
     * <p>A loader calls this where a batch of world work ends, which for a server is the end of its
     * tick: one tick covers every world it runs, so every held level is due. A loader with one
     * world can name it with {@link #flush(Object)} instead.
     *
     * <p>Nothing pending sends nothing, and a side that was never installed (or was taken away)
     * only forgets what it held. What was last sent is kept per level, so a script that saves the
     * same value again after a flush is still not sent a second time.
     */
    public static synchronized void flush() {
        for (Object level : new ArrayList<>(LEVELS.keySet())) {
            flush(level);
        }
    }

    /**
     * Sends and forgets the changes held for one level, and leaves every other level alone: a flush
     * of the server's world must neither send nor drop a change of a client's level, or of a second
     * world in the same process.
     *
     * <p>The held changes go before the held redraws, because a redraw rebuilds a mesh and the
     * client's copy has to hold the data the mesh then reads.
     */
    public static synchronized void flush(Object level) {
        Pending pending = LEVELS.get(level);
        if (pending == null) {
            return;
        }
        List<Object> ready = new ArrayList<>(pending.held);
        pending.held.clear();
        List<Object> rebuilds = new ArrayList<>(pending.heldRedraws);
        pending.heldRedraws.clear();
        // A record only exists for the installed side: install clears them when the side changes.
        for (Object blockEntity : ready) {
            transport.send(blockEntity);
        }
        for (Object blockEntity : rebuilds) {
            transport.redraw(blockEntity);
        }
    }

    /**
     * A script asked for the block's geometry to be built again, which a block in its chunk's mesh
     * needs before it reads saved data the client's copy may not hold yet. The side that owns the
     * block entity sends the clients that hold it the data, and each client's handler marks its own
     * section and builds the mesh again; a server that sends a block update instead would not, as
     * the client drops a same-state update. A client's own copy rebuilds its mesh locally, through
     * the loader's mark, and sends nothing, because the data travels server to client only. That is
     * the same side check {@link #changed} makes, so no loader's transport can turn a client's
     * redraw into a packet even if it installed one.
     *
     * <p>A side that defers sends is asked for the redraw at its flush, not here: the block that
     * the redraw is for may be placed in the same tick, and its block change only reaches the
     * client at that flush. A redraw sent now would arrive first and be dropped for a position the
     * client still holds the previous block at, leaving its new copy waiting for data forever
     * (#1211).
     */
    public static synchronized void redrawn(Object blockEntity) {
        Transport installed = transport;
        if (installed == null) {
            return;
        }
        if (!installed.onServer(blockEntity)) {
            return;
        }
        // The redraw packet carries the data too, so a held change of the same block entity is
        // what it replaces rather than something to send after it, which could be older.
        Object level = installed.levelOf(blockEntity);
        if (installed.defersSends() && level != null) {
            Pending pending = LEVELS.computeIfAbsent(level, unused -> new Pending());
            pending.held.remove(blockEntity);
            pending.heldRedraws.add(blockEntity);
            return;
        }
        Pending pending = LEVELS.get(level);
        if (pending != null) {
            pending.held.remove(blockEntity);
        }
        installed.redraw(blockEntity);
    }

    /**
     * Runs a client's own rebuild when this process holds a client's copy of the block entity.
     *
     * <p>A 1.20.6 client marks its own level's section, which is also how the block entity packet's
     * handler rebuilds the mesh; a server's transport sends the data and never a block update. The
     * answer is a plain boolean here so a core unit test covers both, and the client's rebuilt mesh
     * is drawn by redraw_custom_render.
     */
    public static void onClientLevel(boolean clientLevel, Runnable rebuild) {
        if (clientLevel) {
            rebuild.run();
        }
    }

    /**
     * Marks a client's copy of a block entity as made but not yet sent its data, so its render pass
     * waits instead of drawing and reporting a block that has nothing to draw.
     *
     * <p>Only a joined client is marked: a client alone in a singleplayer world holds the copy the
     * script writes, and a server's own copy is not drawn at all. The loader answers whether this
     * world is one it joined, because only it knows how its game spells that.
     */
    public static synchronized void clientCopyCreated(Object blockEntity, boolean joinedClient) {
        if (joinedClient) {
            AWAITING_FIRST_DATA.add(blockEntity);
        }
    }

    /**
     * The client's copy has been given the data the server holds for it, from a block entity update
     * or from the chunk's NBT, so its render pass may draw and report again.
     */
    public static synchronized void clientCopyReceived(Object blockEntity) {
        AWAITING_FIRST_DATA.remove(blockEntity);
    }

    /**
     * Whether a joined client's copy is still waiting for its first data, which the render path
     * reads before it draws or reports the block.
     */
    public static synchronized boolean awaitingFirstData(Object blockEntity) {
        return AWAITING_FIRST_DATA.contains(blockEntity);
    }

    /**
     * Everything the frame pass reads from the block entity, as text: the numbers and lists by
     * name, sorted so the same data always reads the same, and the energy store.
     */
    static String snapshot(Object blockEntity) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Double> entry : GrugSavedNumbers.snapshot(blockEntity).entrySet()) {
            text.append("n ")
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue())
                    .append(';');
        }
        for (Map.Entry<String, List<GrugSavedLists.Record>> entry :
                GrugSavedLists.snapshot(blockEntity).entrySet()) {
            text.append("l ").append(entry.getKey()).append('=');
            for (GrugSavedLists.Record record : entry.getValue()) {
                // A record's own numbers are unordered, so they are sorted before they are written.
                for (Map.Entry<String, Double> number :
                        new TreeMap<>(record.numbers()).entrySet()) {
                    text.append(number.getKey()).append('=').append(number.getValue()).append(',');
                }
                text.append("item ").append(record.item()).append('|');
            }
            text.append(';');
        }
        text.append("e=").append(GrugPower.stored(blockEntity));
        return text.toString();
    }
}
