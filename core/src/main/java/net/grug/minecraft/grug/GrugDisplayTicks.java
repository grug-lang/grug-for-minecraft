package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * Runs a block's {@code display_tick} export, the game's random display tick, and spawns the
 * particles it asks for.
 *
 * <p>The game calls a block's display tick on the client for blocks chosen at random around the
 * player, many times a second, and hands it the random number generator the block is to draw from.
 * That generator is what a port has to consume in the order its reference does, so the script reads
 * it through {@code DisplayTickContext.random()} and is not given one of its own: a test that seeds
 * the generator then gets the same particles from a port and from its reference.
 *
 * <p>A block that never called {@code set_display_tick} costs one map lookup per sampled block, and
 * only a declared block enters the VM.
 */
public final class GrugDisplayTicks {
    /** The particles a script may name: only what a committed port asks for. */
    private static final Set<String> PARTICLE_NAMES =
            Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList("reddust")));

    /** One particle a display tick asked for. */
    public static final class Particle {
        public final String name;
        public final double x;
        public final double y;
        public final double z;
        public final double vx;
        public final double vy;
        public final double vz;

        Particle(String name, double x, double y, double z, double vx, double vy, double vz) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
        }
    }

    /**
     * The context one {@code display_tick()} call was passed, which is what the context's methods
     * check before they act. A new one is made for every call and cleared when it returns, so a
     * context saved in a member variable and used later is refused.
     */
    public static final class Context {
        private final Object world;
        private final DoubleSupplier random;

        Context(Object world, DoubleSupplier random) {
            this.world = world;
            this.random = random;
        }

        /** The next number of the game's generator for this tick, from 0 up to but not 1. */
        public double random() {
            return random.getAsDouble();
        }

        /** Spawns one particle in the world and records it for a test that asked. */
        public void spawn(
                String name, double x, double y, double z, double vx, double vy, double vz) {
            record(name, x, y, z, vx, vy, vz);
            GrugCore.getAdapter().addRedstoneDust(world, x, y, z);
        }
    }

    private static final List<Particle> RECORDED = new ArrayList<>();
    private static boolean recording = false;
    private static Context context = null;

    /**
     * The blocks already reported, so a mod author hears about a broken block once rather than on
     * every sampled block. Concurrent for the reason {@code GrugShapes} gives: both game threads
     * can reach a broken block, and a check followed by an insert would report it twice.
     */
    private static final Set<String> REPORTED_BLOCKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @GrugGenerated("utility class: never instantiated")
    private GrugDisplayTicks() {}

    /** The context of the {@code display_tick()} call that is open right now, or null. */
    public static Context context() {
        return context;
    }

    /** Opens the context of one {@code display_tick()} call. */
    static Context openContext(Object world, DoubleSupplier random) {
        context = new Context(world, random);
        return context;
    }

    /** Closes it again, so a saved context no longer matches. */
    static void closeContext() {
        context = null;
    }

    /**
     * What is wrong with a particle a script asked for, or null when nothing is: a name that is
     * none of the particles the games all have, or a position or motion that is not finite.
     */
    static String particleProblem(
            String name, double x, double y, double z, double vx, double vy, double vz) {
        if (!PARTICLE_NAMES.contains(name)) {
            return "There is no particle called \""
                    + name
                    + "\". The particles are "
                    + String.join(", ", new java.util.TreeSet<>(PARTICLE_NAMES))
                    + ".";
        }
        for (double number : new double[] {x, y, z, vx, vy, vz}) {
            if (!Double.isFinite(number)) {
                return "The position and the motion must be finite numbers.";
            }
        }
        return null;
    }

    /** Keeps one spawned particle when a test is recording. */
    static void record(String name, double x, double y, double z, double vx, double vy, double vz) {
        synchronized (RECORDED) {
            if (recording) {
                RECORDED.add(new Particle(name, x, y, z, vx, vy, vz));
            }
        }
    }

    /** Starts recording the particles spawned from here on, for a test. */
    static void startRecording() {
        synchronized (RECORDED) {
            RECORDED.clear();
            recording = true;
        }
    }

    /** Stops recording. */
    static void stopRecording() {
        synchronized (RECORDED) {
            recording = false;
        }
    }

    /** How many particles were spawned since the last {@link #startRecording}. */
    static int recordedCount() {
        synchronized (RECORDED) {
            return RECORDED.size();
        }
    }

    /** The {@code index}th recorded particle, or null when there is no such one. */
    static Particle recorded(int index) {
        synchronized (RECORDED) {
            if (index < 0 || index >= RECORDED.size()) {
                return null;
            }
            return RECORDED.get(index);
        }
    }

    /**
     * Runs {@code blockFileId}'s display tick at one position.
     *
     * @param world the loader's Level object, which is what the script reads its neighbours through
     * @param random the game's generator for this tick, as a number from 0 up to but not 1
     */
    public static void run(
            long blockFileId, Object world, int x, int y, int z, DoubleSupplier random) {
        GrugBlockData data = GrugDeclarations.blockDataByFileId.get(blockFileId);
        if (data == null || !data.hasDisplayTick) {
            return;
        }
        ask(blockFileId, world, x, y, z, random, data.id + " at " + x + ", " + y + ", " + z);
    }

    /**
     * The report for a block that declared a display tick and exports none, once per block.
     *
     * @return whether it reported, which is false for a call that ran or aborted from an error of
     *     its own, and for a block already reported
     */
    static boolean reportMissingExport(boolean ran, boolean errored, String description) {
        if (ran || errored || !REPORTED_BLOCKS.add(description)) {
            return false;
        }
        GrugModTreeDefect.report(
                "set_display_tick: "
                        + description
                        + " declared a display tick, but its block script exports no"
                        + " display_tick(), so nothing happens on it. Add an export"
                        + " display_tick(ctx: DisplayTickContext, world: Level, x: number, y:"
                        + " number, z: number), or drop the set_display_tick() call.");
        return true;
    }

    /**
     * The call into the VM, its context and world entities.
     *
     * <p>Excluded because every path in it needs a live grug entity, which only a game has. The
     * decision not to ask at all lives in {@link #run}, the report in {@link #reportMissingExport},
     * and both stay measured.
     *
     * <p>The whole call runs under the state lock, for the reason {@link GrugShapes} gives.
     */
    @GrugGenerated(
            "the call into the VM and its context and world entities: exercising any of them needs"
                    + " a live grug entity")
    private static void ask(
            long blockFileId,
            Object world,
            int x,
            int y,
            int z,
            DoubleSupplier random,
            String description) {
        GrugStateLock.lock();
        try {
            long blockHandle = Grug.createEntity(blockFileId);
            if (blockHandle == 0) {
                return;
            }

            Context openContext = openContext(world, random);
            long contextHandle =
                    GrugEntities.addEntity(GrugEntityType.DisplayTickContext, openContext);
            long worldId = GrugEntities.addEntity(GrugEntityType.Level, world);
            boolean[] ran = new boolean[1];
            int errorsBefore = GrugTestState.runtimeErrorCount;
            try {
                GrugEntities.withFreshOwner(
                        () ->
                                ran[0] =
                                        ExportFns.Block_display_tick(
                                                blockHandle, contextHandle, worldId, x, y, z));
            } finally {
                GrugEntities.removeEntity(worldId);
                GrugEntities.removeEntity(contextHandle);
                closeContext();
                Grug.destroyEntity(blockHandle);
            }

            reportMissingExport(
                    ran[0], GrugTestState.runtimeErrorCount != errorsBefore, description);
        } finally {
            GrugStateLock.unlock();
        }
    }
}
