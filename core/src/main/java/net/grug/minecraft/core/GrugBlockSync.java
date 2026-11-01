package net.grug.minecraft.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The block edits a test has made that its client has not answered with yet.
 *
 * <p>A test's {@code place_block} edits the world the game simulates. On 1.20.6 that is the
 * integrated server's level, and the client learns of the edit only from a packet it reads on a
 * later frame; on the other four loaders the client and the server share the world, so the edit is
 * there already but its section still has to be rebuilt. The settle wait used to look only at the
 * light and at the frames drawn, so it could answer settled over a client that had not received the
 * test's own edit, and a capture following it could read a frame from before that edit. See #804.
 *
 * <p>A loader records every block it places as one the client is owed, and its frame hook then
 * reads the client's copy of each position. Only when a frame has seen the client hold what the
 * simulated world holds at every one of them is the world settled again; that frame counts as busy,
 * so the two idle frames a capture waits for are frames drawn after the client received the edit,
 * not frames drawn before it.
 *
 * <p>What the client owes is the simulated world's <em>current</em> block, not the one the test
 * placed: a clear can be refilled by water, and the game itself can replace a block after a test
 * put it there. Comparing against the block the test asked for would wait forever for a world that
 * has legitimately moved on, so the expected block is refreshed from the simulated world on the
 * thread that owns it before every answer.
 */
public final class GrugBlockSync {
    /** Reads the block name a level holds at a position, or null when it cannot answer. */
    @FunctionalInterface
    public interface Blocks {
        String blockNameAt(int x, int y, int z);
    }

    /**
     * The block the simulated world held the last time the loader looked, for each position it is
     * owed, in the order the edits were made.
     *
     * <p>Each key packs x, y and z, because a level's coordinates fit in 26, 12 and 26 signed bits
     * and the game refuses a write outside them. An edit made twice at one position replaces the
     * earlier one, so the client is only owed what the world ended up with there.
     */
    private final Map<Long, String> owed = new LinkedHashMap<>();

    /**
     * Records that the client has to hold {@code blockName} at this position before the world is
     * settled again. Called after the edit has reached the world the game simulates.
     */
    public synchronized void owe(int x, int y, int z, String blockName) {
        owed.put(pack(x, y, z), blockName);
    }

    /**
     * Reads the simulated world's current block at every owed position, so the frame hook compares
     * the client against what the world holds now rather than what the test put there.
     *
     * <p>Called from {@code isWorldSettled}, which runs on the thread that owns the simulated
     * world: the server thread on 1.20.6 and the client's own on the loaders that share one world.
     * The frame hook only ever reads the client's copy.
     */
    public synchronized void refresh(Blocks simulated) {
        for (Map.Entry<Long, String> edit : owed.entrySet()) {
            long at = edit.getKey();
            edit.setValue(simulated.blockNameAt(unpackX(at), unpackY(at), unpackZ(at)));
        }
    }

    /**
     * One completed frame: reads the client's copy of every owed position and forgets the edits
     * once the client holds all of them.
     *
     * <p>The frame answers busy whenever an edit was still owed when its hook ran, even if the
     * client has just received it. A capture reads the frame before the last one drawn, and that
     * earlier frame may predate the packet's arrival, so the frames after this one are the first a
     * capture may read.
     *
     * @param client the client's own level, read on the thread the frame ran on
     * @return whether this frame still had a test edit the client had been owed
     */
    public synchronized boolean onFrame(Blocks client) {
        if (owed.isEmpty()) {
            return false;
        }
        String[] expected = new String[owed.size()];
        String[] found = new String[owed.size()];
        int i = 0;
        for (Map.Entry<Long, String> edit : owed.entrySet()) {
            expected[i] = edit.getValue();
            long at = edit.getKey();
            found[i] = client.blockNameAt(unpackX(at), unpackY(at), unpackZ(at));
            i++;
        }
        if (everyEditArrived(expected, found)) {
            owed.clear();
        }
        return true;
    }

    /** Whether every edit a test made has been seen in the client's copy. */
    public synchronized boolean settled() {
        return owed.isEmpty();
    }

    /**
     * Whether the client holds every block the simulated world owes it, given the block each
     * position should hold and the answer the client gave for it, in the same order.
     *
     * <p>Both arrays hold one entry per owed edit, and a null client answer is a miss like a
     * different block: a level or chunk the client has not loaded cannot prove it received the
     * edit. This is a pure function so that the rule is covered in every combination by {@code
     * GrugBlockSyncTest}, rather than being a branch only a packet landing at the right moment
     * meets. See #804.
     */
    public static boolean everyEditArrived(String[] owedBlocks, String[] clientBlocks) {
        if (owedBlocks.length != clientBlocks.length) {
            throw new IllegalArgumentException(
                    "Every owed edit needs one client answer, got "
                            + owedBlocks.length
                            + " edits and "
                            + clientBlocks.length
                            + " answers.");
        }
        for (int i = 0; i < owedBlocks.length; i++) {
            if (!Objects.equals(owedBlocks[i], clientBlocks[i])) {
                return false;
            }
        }
        return true;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int unpackX(long key) {
        return (int) (key >> 38);
    }

    private static int unpackY(long key) {
        return (int) (key << 26 >> 52);
    }

    private static int unpackZ(long key) {
        return (int) (key << 38 >> 38);
    }
}
