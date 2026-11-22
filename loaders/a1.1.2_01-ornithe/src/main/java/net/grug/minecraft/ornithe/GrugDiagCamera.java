package net.grug.minecraft.ornithe;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.mob.player.PlayerEntity;

import org.lwjgl.opengl.Display;

/** Diagnostic only, never to be merged. */
public final class GrugDiagCamera {
    private static String frame = "no frame yet";

    private GrugDiagCamera() {}

    private static String d(double v) {
        return v + "/" + Long.toHexString(Double.doubleToRawLongBits(v));
    }

    private static String f(float v) {
        return v + "/" + Integer.toHexString(Float.floatToRawIntBits(v));
    }

    /** Records the state the frame about to be drawn reads; the capture reads the latest one. */
    public static void record(Minecraft mc, float partial) {
        PlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            frame = "player or world missing";
            return;
        }
        frame =
                "partial="
                        + f(partial)
                        + " x="
                        + d(p.x)
                        + " y="
                        + d(p.y)
                        + " z="
                        + d(p.z)
                        + " lastX="
                        + d(p.lastX)
                        + " lastY="
                        + d(p.lastY)
                        + " lastZ="
                        + d(p.lastZ)
                        + " yaw="
                        + f(p.yaw)
                        + " pitch="
                        + f(p.pitch)
                        + " lastYaw="
                        + f(p.lastYaw)
                        + " lastPitch="
                        + f(p.lastPitch)
                        + " bobbing="
                        + mc.options.viewBobbing
                        + " bob="
                        + f(p.bob)
                        + " lastBob="
                        + f(p.lastBob)
                        + " walk="
                        + f(p.walkDistance)
                        + " lastWalk="
                        + f(p.lastWalkDistance)
                        + " clock="
                        + mc.world.ticks
                        + " window="
                        + mc.width
                        + "x"
                        + mc.height
                        + " display="
                        + Display.getWidth()
                        + "x"
                        + Display.getHeight();
    }

    public static void capture(int waited) {
        System.out.println("DIAG camera at capture (client tick " + waited + ") " + frame);
    }

    /** The pixel hash of the image just read back, so captures can be told apart in the log. */
    public static void hash(java.awt.image.BufferedImage image) {
        long h = 1125899906842597L;
        if (image != null) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    h = 31 * h + image.getRGB(x, y);
                }
            }
        }
        System.out.println("DIAG capture pixel hash " + Long.toHexString(h));
    }

    private static final java.util.Map<Integer, String> seen = new java.util.HashMap<>();
    private static final java.util.Set<Integer> reportedDying = new java.util.HashSet<>();
    private static int previousCount = -1;

    private static String describe(net.minecraft.entity.Entity e) {
        String size = "";
        if (e instanceof net.minecraft.entity.mob.monster.SlimeEntity) {
            size = " size=" + ((net.minecraft.entity.mob.monster.SlimeEntity) e).size;
        }
        String mob = "";
        if (e instanceof net.minecraft.entity.mob.MobEntity) {
            net.minecraft.entity.mob.MobEntity m = (net.minecraft.entity.mob.MobEntity) e;
            mob = " health=" + m.health + " deathTicks=" + m.deathTicks;
        }
        return "id="
                + System.identityHashCode(e)
                + " class="
                + e.getClass().getSimpleName()
                + " pos="
                + e.x
                + ","
                + e.y
                + ","
                + e.z
                + " ticks="
                + e.ticks
                + mob
                + size
                + " removed="
                + e.removed
                + " inChunk="
                + e.inChunk
                + " chunk="
                + e.chunkX
                + ","
                + e.chunkZ;
    }

    private static final java.util.List<net.minecraft.entity.Entity> sample =
            new java.util.ArrayList<>();
    private static long sampledTick = -1;
    private static java.lang.reflect.Field fall;

    private static float fallOf(net.minecraft.entity.Entity e) {
        try {
            if (fall == null) {
                fall = net.minecraft.entity.Entity.class.getDeclaredField("fallDistance");
                fall.setAccessible(true);
            }
            return fall.getFloat(e);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** Five slimes chosen at the first hostile count, logged once per world tick until gone. */
    private static void sampleTick(net.minecraft.world.World world, java.util.List<Object> all) {
        if (sample.isEmpty() && all.size() >= 100) {
            int step = Math.max(1, all.size() / 5);
            for (int i = 0; i < all.size() && sample.size() < 5; i += step) {
                sample.add((net.minecraft.entity.Entity) all.get(i));
            }
        }
        if (sample.isEmpty() || world.ticks == sampledTick) {
            return;
        }
        sampledTick = world.ticks;
        for (net.minecraft.entity.Entity e : sample) {
            String mob = "";
            if (e instanceof net.minecraft.entity.mob.MobEntity) {
                net.minecraft.entity.mob.MobEntity m = (net.minecraft.entity.mob.MobEntity) e;
                mob =
                        " health="
                                + m.health
                                + " deathTicks="
                                + m.deathTicks
                                + " damagedTimer="
                                + m.damagedTimer
                                + " invulnerableTicks="
                                + m.invulnerableTicks;
            }
            System.out.println(
                    "DIAG sample clock="
                            + world.ticks
                            + " id="
                            + System.identityHashCode(e)
                            + " ticks="
                            + e.ticks
                            + " y="
                            + e.y
                            + " velY="
                            + e.velocityY
                            + " onGround="
                            + e.onGround
                            + " fall="
                            + fallOf(e)
                            + " removed="
                            + e.removed
                            + mob);
        }
    }

    /** Called on every hostile count: reports each entity that left the list or is dying. */
    public static void mobs(net.minecraft.world.World world, Class<?> type) {
        java.util.Map<Integer, String> now = new java.util.HashMap<>();
        int count = 0;
        for (Object o : new java.util.ArrayList<Object>(world.entities)) {
            if (!type.isAssignableFrom(o.getClass())) {
                continue;
            }
            count++;
            net.minecraft.entity.Entity e = (net.minecraft.entity.Entity) o;
            int id = System.identityHashCode(e);
            now.put(id, describe(e));
            if (e instanceof net.minecraft.entity.mob.MobEntity
                    && ((net.minecraft.entity.mob.MobEntity) e).health <= 0
                    && reportedDying.add(id)) {
                System.out.println("DIAG hostile dying " + describe(e));
            }
        }
        if (previousCount >= 0 && count < previousCount) {
            String player = "no player";
            if (!world.players.isEmpty()) {
                net.minecraft.entity.Entity p = (net.minecraft.entity.Entity) world.players.get(0);
                player = p.x + "," + p.y + "," + p.z;
            }
            System.out.println(
                    "DIAG hostile count dropped "
                            + previousCount
                            + " to "
                            + count
                            + " clock="
                            + world.ticks
                            + " difficulty="
                            + world.difficulty
                            + " player="
                            + player);
            for (java.util.Map.Entry<Integer, String> g : seen.entrySet()) {
                if (!now.containsKey(g.getKey())) {
                    System.out.println("DIAG hostile gone " + g.getValue());
                }
            }
        }
        previousCount = count;
        java.util.List<Object> all = new java.util.ArrayList<>();
        for (Object o : world.entities) {
            if (type.isAssignableFrom(o.getClass())) {
                all.add(o);
            }
        }
        sampleTick(world, all);
        seen.clear();
        seen.putAll(now);
    }
}
