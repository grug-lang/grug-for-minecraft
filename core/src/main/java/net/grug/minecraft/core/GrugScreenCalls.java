package net.grug.minecraft.core;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * The screen calls a test makes, in one place, so the client that performs them itself and the
 * client a dedicated server forwards them to run the same code (#1094).
 *
 * <p>Two halves share this class. The static methods are the call on the side that owns the screen,
 * which is a client: both {@code HostFunctions} and the forwarded answer of {@link
 * GrugControlClient} go through them, so the bookkeeping a screen call does with the settle wait
 * cannot differ between a singleplayer run and a forwarded one. {@link Forwarded} is the other end,
 * the same calls made by a server that has no screen of its own and asks the client of the player
 * who started the run.
 */
public final class GrugScreenCalls {
    /** The read names a {@link GrugControlWire#CALL_SCREEN_READ} may name. */
    public static final String SCREEN_WIDTH = "screen_width";

    public static final String GUI_LEFT = "gui_left";
    public static final String GUI_TOP = "gui_top";
    public static final String GUI_WIDTH = "gui_width";
    public static final String GUI_HEIGHT = "gui_height";

    /**
     * Every read a test may name, in the order a {@link GrugControlWire#CALL_SCREEN_GEOMETRY}
     * answer carries them, each with the measurement the client that owns the screen makes for it.
     * One table holds the names, their places in an answer and the adapter call each one makes, so
     * a name cannot be in one of those and missing from another: it is either here or nowhere
     * (#1289).
     */
    private static final Read[] READS = {
        new Read(GUI_LEFT, ModLoaderAdapter::guiLeftForTest),
        new Read(GUI_TOP, ModLoaderAdapter::guiTopForTest),
        new Read(GUI_WIDTH, ModLoaderAdapter::guiWidthForTest),
        new Read(GUI_HEIGHT, ModLoaderAdapter::guiHeightForTest),
        new Read(SCREEN_WIDTH, ModLoaderAdapter::screenWidthForTest),
    };

    /** One screen read: the name a test says and the measurement the client makes for it. */
    private static final class Read {
        final String name;
        final ToDoubleFunction<ModLoaderAdapter> measurement;

        Read(String name, ToDoubleFunction<ModLoaderAdapter> measurement) {
            this.name = name;
            this.measurement = measurement;
        }
    }

    /**
     * The highest mouse button a screen press may carry: 0 is the left button, 1 the right and 2
     * the middle, which are the three a click means on every loader. The 1.2.5 client writes the
     * button as one byte, so a 256 arrived as the left button and a 255 as -1 (#1217).
     */
    public static final int MAX_BUTTON = 2;

    /**
     * Why a screen press cannot carry this button, or null when it can. The client that makes the
     * press and the server that forwards one check it through this answer, so the two cannot
     * disagree about the range a button may have (#1217).
     */
    public static String buttonProblem(double button) {
        if (!Double.isFinite(button)) {
            return "The button has to be finite.";
        }
        if (button != Math.floor(button) || button < 0 || button > MAX_BUTTON) {
            return "The button has to be a whole number from 0 to "
                    + MAX_BUTTON
                    + ", where 0 is the left button, 1 the right and 2 the middle, and was "
                    + button
                    + ".";
        }
        return null;
    }

    /**
     * The identity of the last {@link #screenIdentity} call, 0 when it saw no screen, so the call
     * can tell a screen that has just appeared, or one that replaced another while the panel stayed
     * open, from the screen it already saw.
     */
    private static long openIdentity;

    private GrugScreenCalls() {}

    /**
     * The identity of the screen that is open, and 0 when none is, which is what {@code
     * screen_open} answers on a client that a dedicated server forwards the call to (#1289).
     *
     * <p>A screen that has just appeared forgets the frames counted so far, as does one that
     * replaced another while the panel stayed open. {@code screen_open} alone only says the screen
     * object exists, and {@code world_settled} only says the last frames were idle, so the two
     * together could still let a capture read a frame drawn before the screen opened once the world
     * was already settled. Forgetting the frames here means a following {@code world_settled} wait
     * can only be answered by frames drawn after the screen opened, or after the one this call
     * found was replaced. See #421.
     */
    public static long screenIdentity(ModLoaderAdapter adapter) {
        long identity = adapter.screenIdentityForTest();
        if (identity != 0 && identity != openIdentity) {
            adapter.forgetSettledFrames();
        }
        openIdentity = identity;
        return identity;
    }

    /**
     * Whether a screen is open, which on a dedicated server that forwards is the joined client's
     * own screen. See {@link #screenIdentity}.
     */
    public static boolean screenOpen(ModLoaderAdapter adapter) {
        return screenIdentity(adapter) != 0;
    }

    /**
     * Closes whatever screen is open, and forgets which one it was, so the next screen to open is
     * seen as a fresh one and a following {@link #screenOpen} forgets the settled frames for it.
     */
    public static void closeScreen(ModLoaderAdapter adapter) {
        adapter.closeOpenScreenForTest();
        openIdentity = 0;
    }

    /**
     * One of the screen's measurements, named as a {@link GrugControlWire#CALL_SCREEN_READ} names
     * it. The name and the adapter call both come from {@link #READS}, so a name {@link
     * #readProblem} accepts is always measured here.
     */
    public static double read(ModLoaderAdapter adapter, String read) {
        for (Read measurement : READS) {
            if (measurement.name.equals(read)) {
                return measurement.measurement.applyAsDouble(adapter);
            }
        }
        throw new IllegalArgumentException(readProblem(read));
    }

    /**
     * Every measurement a {@link GrugControlWire#CALL_SCREEN_GEOMETRY} answer carries, each read
     * through {@code source} on the side that owns the screen, in the order {@link #READS} lists
     * them, so a batch and a single read can never disagree about a value or its place (#1277).
     */
    public static double[] geometry(ToDoubleFunction<String> source) {
        double[] values = new double[GrugControlWire.GEOMETRY_COUNT];
        for (int at = 0; at < READS.length; at++) {
            values[at] = source.applyAsDouble(READS[at].name);
        }
        return values;
    }

    /**
     * One measurement out of a {@link #geometry} answer, named as {@link #read} names it. A name
     * that is not a read is a {@link GrugControlServer.ControlException}, which a host function
     * reports as the test's error, rather than an unchecked exception out of it (#1289).
     */
    static double fromGeometry(double[] geometry, String read)
            throws GrugControlServer.ControlException {
        for (int at = 0; at < READS.length; at++) {
            if (READS[at].name.equals(read)) {
                return geometry[at];
            }
        }
        // {@link #readProblem} loops the same table, so a name it accepts is never missing here
        // and this refuses exactly the names it refuses.
        throw new GrugControlServer.ControlException(readProblem(read));
    }

    /** Why {@code read} is not a measurement a screen has, or null when it is one. */
    public static String readProblem(String read) {
        if (read == null) return "The screen read has no name.";
        for (Read measurement : READS) {
            if (measurement.name.equals(read)) return null;
        }
        return "There is no screen read named " + read + ".";
    }

    /**
     * The screen calls of a dedicated server, which has no screen of its own: each one is made by
     * the client of the player who started the run, and a failure is reported as the host
     * function's error, naming the function that failed.
     *
     * <p>The server comes in as a supplier rather than as a server, because a loader creates it on
     * the first use and the adapter that holds this object outlives that.
     */
    public static final class Forwarded {
        private final Supplier<GrugControlServer> server;
        private final Consumer<String> failed;

        public Forwarded(Supplier<GrugControlServer> server, Consumer<String> failed) {
            this.server = server;
            this.failed = failed;
        }

        /** Whether a screen is open on the client, with the world frozen while it is asked. */
        public boolean screenOpen() {
            try {
                return server.get().screenOpen();
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.screen_open: " + e.getMessage());
                return false;
            }
        }

        /** One of the client's screen measurements, 0 when it could not be asked. */
        public double read(String read) {
            try {
                return server.get().screenRead(read);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test." + read + ": " + e.getMessage());
                return 0.0;
            }
        }

        /** Whether the client's overlay took the key, false when the client could not be asked. */
        public boolean pressScreenKey(int key, boolean ctrl, boolean release) {
            try {
                return server.get().pressScreenKey(key, ctrl, release);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.press_screen_key: " + e.getMessage());
                return false;
            }
        }

        /**
         * Whether the client's overlay took the wheel, false when the client could not be asked.
         */
        public boolean scrollScreen(double x, double y, double delta) {
            try {
                return server.get().scrollScreen(x, y, delta);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.scroll_screen: " + e.getMessage());
                return false;
            }
        }

        /** Closes the client's screen, reporting a client that could not be asked. */
        public void closeScreen() {
            try {
                server.get().closeScreen();
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.close_screen: " + e.getMessage());
            }
        }

        /**
         * Whether the client's overlay took the press, false when the client could not be asked.
         * The client's click packet is applied by the next {@link
         * GrugControlServer#applyClientWork} (#1094).
         */
        public boolean clickScreen(double x, double y, int button) {
            try {
                return server.get().clickScreen(x, y, button);
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.click_screen: " + e.getMessage());
                return false;
            }
        }

        /** Opens the client's own inventory, reporting a client that could not be asked. */
        public void openInventory() {
            try {
                server.get().openInventory();
            } catch (GrugControlServer.ControlException e) {
                failed.accept("Test.open_inventory: " + e.getMessage());
            }
        }
    }
}
