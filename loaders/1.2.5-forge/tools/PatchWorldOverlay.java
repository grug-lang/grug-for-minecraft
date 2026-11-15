import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
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
 * Patches the runtime so the loader can draw the overlays' world and HUD exports in the game's own
 * frame (#1353).
 *
 * <p>1.2.5's game classes are loaded by launchwrapper's parent loader and cannot see the mod's
 * child loader, so, as {@code PatchGuiInput} does, the loader puts its handlers into static fields
 * added to the game's classes and the injected code calls them through JDK functional interfaces
 * both loaders can see.
 *
 * <p>{@code EntityRenderer.renderRainSnow(F)V} is the last world draw before the clouds, the depth
 * clear and the hand, so its start calls {@code grugWorldHook} with the partial tick, which is
 * where a line is depth-tested against the level. {@code GuiIngame.renderGameOverlay(FZII)V} calls
 * {@code grugHudHook} at each return, after the game's own HUD, in the GUI's projection.
 *
 * <p>Usage: PatchWorldOverlay &lt;in.jar&gt; &lt;out.jar&gt;
 */
public final class PatchWorldOverlay {

    private static final String ENTITY_RENDERER = "net/minecraft/src/EntityRenderer";
    private static final String GUI_INGAME = "net/minecraft/src/GuiIngame";

    private static final String WORLD_HOOK = "grugWorldHook";
    private static final String HUD_HOOK = "grugHudHook";

    private static final String DOUBLE_CONSUMER = "Ljava/util/function/DoubleConsumer;";
    private static final String RUNNABLE = "Ljava/lang/Runnable;";

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

        entries.put(
                ENTITY_RENDERER + ".class",
                patchEntityRenderer(entries.get(ENTITY_RENDERER + ".class")));
        entries.put(GUI_INGAME + ".class", patchGuiIngame(entries.get(GUI_INGAME + ".class")));

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outJar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static byte[] patchEntityRenderer(byte[] original) {
        if (original == null) {
            throw new IllegalStateException("No " + ENTITY_RENDERER + " in the runtime jar");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, ClassReader.SKIP_FRAMES);
        node.fields.add(
                new FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        WORLD_HOOK,
                        DOUBLE_CONSUMER,
                        null,
                        null));

        MethodNode method = findMethod(node, "renderRainSnow", "(F)V");
        LabelNode none = new LabelNode();
        InsnList call = new InsnList();
        call.add(
                new FieldInsnNode(Opcodes.GETSTATIC, ENTITY_RENDERER, WORLD_HOOK, DOUBLE_CONSUMER));
        call.add(new JumpInsnNode(Opcodes.IFNULL, none));
        call.add(
                new FieldInsnNode(Opcodes.GETSTATIC, ENTITY_RENDERER, WORLD_HOOK, DOUBLE_CONSUMER));
        call.add(new VarInsnNode(Opcodes.FLOAD, 1));
        call.add(new InsnNode(Opcodes.F2D));
        call.add(
                new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/function/DoubleConsumer",
                        "accept",
                        "(D)V",
                        true));
        call.add(none);
        method.instructions.insert(call);
        return write(node);
    }

    private static byte[] patchGuiIngame(byte[] original) {
        if (original == null) {
            throw new IllegalStateException("No " + GUI_INGAME + " in the runtime jar");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, ClassReader.SKIP_FRAMES);
        node.fields.add(
                new FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, HUD_HOOK, RUNNABLE, null, null));

        MethodNode method = findMethod(node, "renderGameOverlay", "(FZII)V");
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            LabelNode none = new LabelNode();
            InsnList call = new InsnList();
            call.add(new FieldInsnNode(Opcodes.GETSTATIC, GUI_INGAME, HUD_HOOK, RUNNABLE));
            call.add(new JumpInsnNode(Opcodes.IFNULL, none));
            call.add(new FieldInsnNode(Opcodes.GETSTATIC, GUI_INGAME, HUD_HOOK, RUNNABLE));
            call.add(
                    new MethodInsnNode(
                            Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true));
            call.add(none);
            method.instructions.insertBefore(instruction, call);
        }
        return write(node);
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        for (Object methodObject : node.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + descriptor + " not found");
    }

    private static byte[] write(ClassNode node) {
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

    private PatchWorldOverlay() {}
}
