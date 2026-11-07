package net.grug.minecraft.core;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugPower;
import net.grug.minecraft.grug.GrugSavedLists;
import net.grug.minecraft.grug.GrugSavedNumbers;

import java.util.List;
import java.util.Map;
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

        /** Sends the block update that makes those clients build the block's mesh again. */
        void redraw(Object blockEntity);
    }

    /** What was last sent to each block entity, keyed weakly so a broken one is collected. */
    private static final Map<Object, String> LAST_SENT = new WeakHashMap<>();

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

    /** Installs the side that sends, which a dedicated server does once it is up. */
    public static synchronized void install(Transport installed) {
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
        String now = snapshot(blockEntity);
        if (now.equals(LAST_SENT.get(blockEntity))) {
            return;
        }
        LAST_SENT.put(blockEntity, now);
        installed.send(blockEntity);
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
     */
    public static synchronized void redrawn(Object blockEntity) {
        Transport installed = transport;
        if (installed == null) {
            return;
        }
        if (!installed.onServer(blockEntity)) {
            return;
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
