import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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
 * Patches the runtime's {@code EntityRenderer.setupFog} so a grug run can render with no distance
 * fog.
 *
 * <p>1.2.5's Forge predates the coremod API, so a runtime transformer cannot reach the game
 * classes, and the run launches through launchwrapper's default tweaker. The class is rewritten
 * here instead, in MCP names, before {@code obfuscateRuntime} reobfuscates it.
 *
 * <p>The injected code guards on the {@code grug.disable.fog} system property rather than calling a
 * grug class: the game classes are loaded by launchwrapper's parent loader and cannot see the mod's
 * child loader, so a reference to one would not resolve at run time. The loader sets the property
 * when a run starts and clears it when the run ends.
 *
 * <p>Usage: DisableFog &lt;in.jar&gt; &lt;out.jar&gt;
 */
public final class DisableFog {

    private static final String TARGET_CLASS = "net/minecraft/src/EntityRenderer";
    private static final String TARGET_METHOD = "setupFog";
    private static final String TARGET_DESCRIPTOR = "(IF)V";

    /** The system property the loader sets while a run owns the client. */
    private static final String PROPERTY = "grug.disable.fog";

    private static final int GL_FOG_START = 2915;
    private static final int GL_FOG_END = 2916;
    private static final int GL_FOG_MODE = 2917;
    private static final int GL_LINEAR = 9729;

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

    /** Returns the class with the fog-disabling guard injected at the start of setupFog. */
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

        LabelNode run = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new LdcInsnNode(PROPERTY));
        guard.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "getProperty",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false));
        guard.add(new JumpInsnNode(Opcodes.IFNULL, run));
        guard.add(new LdcInsnNode(GL_FOG_MODE));
        guard.add(new LdcInsnNode(GL_LINEAR));
        guard.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glFogi", "(II)V", false));
        guard.add(new LdcInsnNode(GL_FOG_START));
        guard.add(new LdcInsnNode(1.0E9F));
        guard.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glFogf", "(IF)V", false));
        guard.add(new LdcInsnNode(GL_FOG_END));
        guard.add(new LdcInsnNode(1.0E9F));
        guard.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glFogf", "(IF)V", false));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(run);

        target.instructions.insert(guard);

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

    private DisableFog() {}
}
