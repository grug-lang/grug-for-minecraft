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
 * nothing the client holds changes with it, and a block that draws only per frame is never meshed
 * again for it: the packet has to be sent by itself. The rule is the same on every loader, so it
 * lives here and each loader only supplies the things only it can answer ({@link Transport}).
 *
 * <p>Only a block that draws all of its own geometry per frame is sent when its data changes: it
 * reads the numbers on the next frame, and nothing meshes it again for them. A block whose geometry
 * is in its chunk's mesh reads them when the mesh is built, which the script asks for with {@code
 * redraw}; a send on every change would be a packet without the mesh that shows it, and on 1.20.6
 * the game's own update packet re-meshes, so a block that also draws per frame would rebuild its
 * mesh every time that frame state changed. {@link #redrawn} is where such a block is sent: the
 * client's copy is given the data and the block update that makes it build the mesh again. On a
 * client that shares the world (singleplayer and the integrated server) {@code redraw} re-meshes
 * that one world and the number is already there.
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

        /** Whether the block draws only per frame, so nothing meshes it again for this data. */
        boolean frameOnly(Object blockEntity);

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
        if (!installed.frameOnly(blockEntity)) {
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
     * block entity sends the clients that hold it the data and the block update that builds the
     * mesh again; a client builds its own mesh again and sends nothing, because the data travels
     * server to client only. That is the same side check {@link #changed} makes, so no loader's
     * transport can turn a client's redraw into a packet even if it installed one.
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
