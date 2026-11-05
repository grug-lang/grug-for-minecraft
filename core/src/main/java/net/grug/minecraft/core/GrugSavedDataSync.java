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
 * Sends a grug block entity's saved data to the client's copy of it when the script changes it, so
 * that a {@code set_saved_number}, a saved list or the energy store reaches the frame pass that
 * reads it.
 *
 * <p>Every loader's grug block entity writes that data into its NBT, and the client's copy reads it
 * from the chunk when it arrives. On a dedicated server the script runs on the server's copy, so
 * nothing the client holds changes with it, and a block that draws only per frame is never meshed
 * again for it: the packet has to be sent by itself. The rule is the same on every loader, so it
 * lives here and each loader only supplies the three things only it can answer ({@link Transport}).
 *
 * <p>Only a block that draws per frame is sent. A block whose geometry is in its chunk's mesh reads
 * the numbers when the mesh is built, and the script asks for that with {@code redraw}; a send here
 * would arrive without the mesh that shows it. On a client that shares the world (singleplayer and
 * the integrated server) {@code redraw} re-meshes that one world and the number is already there.
 * On 1.20.6 the server's {@code redraw} also sends the client the block entity's update packet,
 * which re-meshes the client's copy, so a chunk-mesh block's number does reach a joined client
 * there. The older loaders' {@code redraw} only re-meshes the local world and sends nothing, so the
 * gap (#1101) is theirs.
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
    }

    /** What was last sent to each block entity, keyed weakly so a broken one is collected. */
    private static final Map<Object, String> LAST_SENT = new WeakHashMap<>();

    private static Transport transport = null;

    @GrugGenerated("utility class: never instantiated")
    private GrugSavedDataSync() {}

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
