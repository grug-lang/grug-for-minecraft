import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Patches the runtime's {@code EntityRenderer.updateCameraAndRender} so a grug run can leave a
 * frame undrawn while the running test has asked for none.
 *
 * <p>1.2.5's Forge predates the coremod API, so a runtime transformer cannot reach the game
 * classes, and the run launches through launchwrapper's default tweaker. The class is rewritten
 * here instead, in MCP names, before {@code obfuscateRuntime} reobfuscates it. The other loaders
 * cancel their draw with a Mixin; this is the same cancellation for the one loader that has none.
 * See #770.
 *
 * <p>The guard is inserted after the lightmap update and the mouse work and before the game's own
 * {@code if (!Minecraft.skipRenderWorld)} block, which is where the level, its chunk rebuilds, the
 * GUI and any screen are drawn. Those first parts are per-frame state a drawn frame still needs
 * (the lightmap's pinned flicker is set in the render tick hook), so they keep running; only the
 * draw is left out. The frame's own end is reported by the mod's render tick hook, which asks the
 * same flag before it samples the settle answer, so a skipped frame is never counted as one a
 * capture could read.
 *
 * <p>The injected code guards on the {@code grug.skip.frame} system property rather than calling a
 * grug class: the game classes are loaded by launchwrapper's parent loader and cannot see the mod's
 * child loader, so a reference to one would not resolve at run time. The mod sets the property from
 * the runner's frame request on every frame a test run owns the client.
 *
 * <p>Usage: SkipFrame &lt;in.jar&gt; &lt;out.jar&gt;
 */
public final class SkipFrame {

    private static final String TARGET_CLASS = "net/minecraft/src/EntityRenderer";
    private static final String TARGET_METHOD = "updateCameraAndRender";
    private static final String TARGET_DESCRIPTOR = "(F)V";

    /** The field the game itself checks before it draws the world, which the guard precedes. */
    private static final String SKIP_FIELD_OWNER = "net/minecraft/client/Minecraft";

    private static final String SKIP_FIELD = "skipRenderWorld";

    /** The renderer's own field holding the client, whose read comes just before the check. */
    private static final String CLIENT_FIELD_OWNER = "net/minecraft/src/EntityRenderer";

    private static final String CLIENT_FIELD = "mc";

    /** The system property the loader sets while a frame is to be left undrawn. */
    private static final String PROPERTY = "grug.skip.frame";

    public static void main(String[] args) throws IOException {
        File inJar = new File(args[0]);
        File outJar = new File(args[1]);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(inJar))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }
                entries.put(entry.getName(), bytes.toByteArray());
            }
        }

        String targetEntry = TARGET_CLASS + ".class";
        byte[] original = entries.get(targetEntry);
        if (original == null) {
            throw new IllegalStateException("No " + targetEntry + " in " + inJar);
        }
        entries.put(targetEntry, patch(original));

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outJar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    /**
     * Returns the class with the frame guard injected before the game's own skip check, which is
     * the last thing that runs before the level and GUI are drawn.
     */
    private static byte[] patch(byte[] original) {
        ClassNode node = new ClassNode();
        // The frames are recomputed below, so the original ones are not read.
        new ClassReader(original).accept(node, ClassReader.SKIP_FRAMES);

        MethodNode target = null;
        for (Object methodObject : node.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (method.name.equals(TARGET_METHOD) && method.desc.equals(TARGET_DESCRIPTOR)) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException(
                    TARGET_CLASS + "." + TARGET_METHOD + TARGET_DESCRIPTOR + " not found");
        }

        AbstractInsnNode check = null;
        AbstractInsnNode clientRead = null;
        AbstractInsnNode selfRead = null;
        for (AbstractInsnNode instruction = target.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.GETFIELD) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.owner.equals(SKIP_FIELD_OWNER) && field.name.equals(SKIP_FIELD)) {
                check = field;
                clientRead = field.getPrevious();
                selfRead = clientRead == null ? null : clientRead.getPrevious();
                break;
            }
        }
        if (check == null) {
            throw new IllegalStateException(
                    TARGET_CLASS
                            + "."
                            + TARGET_METHOD
                            + TARGET_DESCRIPTOR
                            + " never reads "
                            + SKIP_FIELD);
        }
        // The read of skipRenderWorld is preceded by `aload_0; getfield mc`, and the guard has to
        // run before the level block it guards, not in the middle of that expression.
        if (!(clientRead instanceof FieldInsnNode)
                || !((FieldInsnNode) clientRead).owner.equals(CLIENT_FIELD_OWNER)
                || !((FieldInsnNode) clientRead).name.equals(CLIENT_FIELD)
                || !(selfRead instanceof VarInsnNode)
                || selfRead.getOpcode() != Opcodes.ALOAD) {
            throw new IllegalStateException(
                    TARGET_CLASS
                            + "."
                            + TARGET_METHOD
                            + TARGET_DESCRIPTOR
                            + " reads "
                            + SKIP_FIELD
                            + " through something other than the renderer's own client field");
        }
        target.instructions.insertBefore(selfRead, skipGuard());

        ClassWriter writer =
                new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                    @Override
                    protected String getCommonSuperClass(String type1, String type2) {
                        // The classes cannot be loaded here, and Object is always a valid answer.
                        return "java/lang/Object";
                    }
                };
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * The guarded return: when the property is set, the method returns before it draws the level,
     * the GUI and any screen. Everything before it has run, and the property is absent for a frame
     * that is meant to be drawn, so the method runs as it always did.
     */
    private static InsnList skipGuard() {
        LabelNode draw = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new LdcInsnNode(PROPERTY));
        guard.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "getProperty",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false));
        guard.add(new JumpInsnNode(Opcodes.IFNULL, draw));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(draw);
        return guard;
    }

    private SkipFrame() {}
}
