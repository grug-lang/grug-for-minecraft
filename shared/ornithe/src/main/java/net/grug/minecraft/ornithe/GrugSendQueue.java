package net.grug.minecraft.ornithe;

import net.grug.minecraft.grug.Grug;
import net.minecraft.network.Connection;

import java.lang.reflect.Field;

/**
 * What a player's connection still holds unwritten, which is what core's {@code GrugSendBuffer}
 * paces the world on (#1802).
 *
 * <p>Alpha and Beta keep the count in a private field of {@code Connection}, and the write thread
 * subtracts from it as it writes, so the field is the game's own answer to how far behind a client
 * is. It is read rather than recomputed from the queues the connection holds, because the game's
 * count is the one its own overflow check compares against, and a second count could disagree with
 * it.
 *
 * <p>The field is private and a run installs a jar whose names are the mappings', so it is looked
 * up by name: a development run keeps the game's own name, and the layer jar a run installs has the
 * field's intermediary name, which Ornithe's mappings give for this version. Every version this
 * layer builds for is pinned, so a jar with neither is a broken assumption rather than a game that
 * moved, and it is fatal: reading nothing would leave the leg flaky again, and reading a wrong
 * field would pace the world on a number that means something else.
 *
 * <p>The class names no server class, so a client compiles it and never calls it.
 */
public final class GrugSendQueue {
    /** The field's name in a development jar, where the game's own names are used. */
    private static final String DEVELOPMENT_NAME = "sendQueueSize";

    /**
     * Its intermediary name in the jars a run installs. Ornithe's gen 2 mappings give a field the
     * same name for every version it exists in, so Alpha's and Beta's are the same string.
     */
    private static final String RUN_NAME = "f_46631004";

    /** The field, looked up on the first read of a run and kept after that. */
    private static Field field = null;

    private GrugSendQueue() {}

    /**
     * What the connection holds unwritten in bytes, which is 0 for a connection that has written
     * everything it queued.
     */
    public static int queuedBytes(Connection connection) {
        if (connection == null) {
            return 0;
        }
        try {
            return field().getInt(connection);
        } catch (IllegalAccessException e) {
            throw Grug.fatal("the connection's send queue size cannot be read", e);
        }
    }

    private static synchronized Field field() {
        if (field != null) {
            return field;
        }
        for (String name : new String[] {DEVELOPMENT_NAME, RUN_NAME}) {
            try {
                Field found = Connection.class.getDeclaredField(name);
                found.setAccessible(true);
                field = found;
                return found;
            } catch (NoSuchFieldException ignored) {
                // The other name is the one this jar has, if either is.
            }
        }
        throw Grug.fatal(
                "the connection has no "
                        + DEVELOPMENT_NAME
                        + " field, in the game's own names or in the run's");
    }
}
