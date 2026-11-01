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
 * Patches the runtime so the overlay can ask about one mouse or key event without draining the
 * game's queue, and so a newly opened screen clears the overlay's drawn mark before its first
 * frame.
 *
 * <p>1.2.5's game classes are loaded by launchwrapper's parent loader and cannot see the mod's
 * child loader, so the injected code cannot call the loader directly. The loader instead puts two
 * handlers into static fields added to {@code GuiScreen}; the game calls them from its own input
 * loop and from {@code Minecraft.displayGuiScreen}. The field type is a JDK functional interface,
 * which both class loaders can see.
 *
 * <p>{@code GuiScreen.handleInput} asks the mouse handler after {@code Mouse.next()} has selected
 * an event and before {@code handleMouseInput} runs. A true answer skips the screen's handler for
 * that one event; a false answer lets the screen handle it exactly as vanilla would, so the wheel,
 * key state and release events are not removed from the game's input pass.
 *
 * <p>The key handler is asked the same way, after {@code Keyboard.next()} and before {@code
 * handleKeyboardInput}.
 *
 * <p>{@code Minecraft.displayGuiScreen} calls the screen-open handler right after it assigns {@code
 * currentScreen}, including a null assignment, so a test cannot read the previous screen's drawn
 * mark while the new screen waits for its first frame.
 *
 * <p>Usage: PatchGuiInput &lt;in.jar&gt; &lt;out.jar&gt;
 */
public final class PatchGuiInput {

    private static final String GUI_SCREEN = "net/minecraft/src/GuiScreen";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";

    private static final String MOUSE_HOOK = "grugMouseHook";
    private static final String KEY_HOOK = "grugKeyHook";
    private static final String OPEN_HOOK = "grugScreenOpenHook";

    private static final String PREDICATE = "Ljava/util/function/Predicate;";
    private static final String CONSUMER = "Ljava/util/function/Consumer;";

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

        entries.put(GUI_SCREEN + ".class", patchGuiScreen(entries.get(GUI_SCREEN + ".class")));
        entries.put(MINECRAFT + ".class", patchMinecraft(entries.get(MINECRAFT + ".class")));

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outJar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static byte[] patchGuiScreen(byte[] original) {
        if (original == null) {
            throw new IllegalStateException("No " + GUI_SCREEN + " in the runtime jar");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, ClassReader.SKIP_FRAMES);

        node.fields.add(
                new FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        MOUSE_HOOK,
                        PREDICATE,
                        null,
                        null));
        node.fields.add(
                new FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, OPEN_HOOK, CONSUMER, null, null));
        node.fields.add(
                new FieldNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, KEY_HOOK, PREDICATE, null, null));
        node.methods.add(mouseHelper());
        node.methods.add(keyHelper());
        node.methods.add(openHelper());

        MethodNode input = findMethod(node, "handleInput", "()V");
        guardLoop(input, "org/lwjgl/input/Mouse", "handleMouseInput", "grugHandleMouse");
        guardLoop(input, "org/lwjgl/input/Keyboard", "handleKeyboardInput", "grugHandleKey");

        return write(node);
    }

    /**
     * Makes one of {@code handleInput}'s event loops ask a hook first: after {@code
     * <device>.next()} has selected an event and before the loop body calls {@code handler}, a true
     * answer from the static helper {@code guard} jumps back to the loop head, which skips that one
     * event.
     */
    private static void guardLoop(MethodNode input, String device, String handler, String guard) {
        AbstractInsnNode next = null;
        AbstractInsnNode handlerCall = null;
        for (AbstractInsnNode instruction = input.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals(device) && call.name.equals("next")) {
                    next = instruction;
                }
            } else if (instruction.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals(GUI_SCREEN)
                        && call.name.equals(handler)
                        && call.desc.equals("()V")) {
                    handlerCall = instruction;
                }
            }
        }
        if (next == null || handlerCall == null) {
            throw new IllegalStateException("GuiScreen.handleInput has no " + device + " loop");
        }
        LabelNode loop = new LabelNode();
        input.instructions.insertBefore(next, loop);

        AbstractInsnNode selfRead = handlerCall.getPrevious();
        if (!(selfRead instanceof VarInsnNode) || selfRead.getOpcode() != Opcodes.ALOAD) {
            throw new IllegalStateException(
                    "GuiScreen.handleInput's " + device + " handler has no receiver");
        }
        InsnList check = new InsnList();
        check.add(new VarInsnNode(Opcodes.ALOAD, 0));
        check.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, GUI_SCREEN, guard, "(L" + GUI_SCREEN + ";)Z", false));
        check.add(new JumpInsnNode(Opcodes.IFNE, loop));
        input.instructions.insertBefore(selfRead, check);
    }

    private static byte[] patchMinecraft(byte[] original) {
        if (original == null) {
            throw new IllegalStateException("No " + MINECRAFT + " in the runtime jar");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, ClassReader.SKIP_FRAMES);

        MethodNode display = findMethod(node, "displayGuiScreen", "(L" + GUI_SCREEN + ";)V");
        AbstractInsnNode assignment = null;
        for (AbstractInsnNode instruction = display.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction.getOpcode() == Opcodes.PUTFIELD) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (field.owner.equals(MINECRAFT)
                        && field.name.equals("currentScreen")
                        && field.desc.equals("L" + GUI_SCREEN + ";")) {
                    assignment = instruction;
                    break;
                }
            }
        }
        if (assignment == null) {
            throw new IllegalStateException(
                    "Minecraft.displayGuiScreen never assigns currentScreen");
        }
        InsnList opened = new InsnList();
        opened.add(new VarInsnNode(Opcodes.ALOAD, 1));
        opened.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        GUI_SCREEN,
                        "grugScreenOpened",
                        "(L" + GUI_SCREEN + ";)V",
                        false));
        display.instructions.insert(assignment, opened);

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

    private static MethodNode mouseHelper() {
        return predicateHelper("grugHandleMouse", MOUSE_HOOK);
    }

    private static MethodNode keyHelper() {
        return predicateHelper("grugHandleKey", KEY_HOOK);
    }

    private static MethodNode predicateHelper(String name, String hook) {
        MethodNode method =
                new MethodNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        name,
                        "(L" + GUI_SCREEN + ";)Z",
                        null,
                        null);
        LabelNode none = new LabelNode();
        InsnList code = method.instructions;
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, GUI_SCREEN, hook, PREDICATE));
        code.add(new JumpInsnNode(Opcodes.IFNULL, none));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, GUI_SCREEN, hook, PREDICATE));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(
                new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/function/Predicate",
                        "test",
                        "(Ljava/lang/Object;)Z",
                        true));
        code.add(new InsnNode(Opcodes.IRETURN));
        code.add(none);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        return method;
    }

    private static MethodNode openHelper() {
        MethodNode method =
                new MethodNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "grugScreenOpened",
                        "(L" + GUI_SCREEN + ";)V",
                        null,
                        null);
        LabelNode none = new LabelNode();
        InsnList code = method.instructions;
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, GUI_SCREEN, OPEN_HOOK, CONSUMER));
        code.add(new JumpInsnNode(Opcodes.IFNULL, none));
        code.add(new FieldInsnNode(Opcodes.GETSTATIC, GUI_SCREEN, OPEN_HOOK, CONSUMER));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(
                new MethodInsnNode(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/function/Consumer",
                        "accept",
                        "(Ljava/lang/Object;)V",
                        true));
        code.add(none);
        code.add(new InsnNode(Opcodes.RETURN));
        return method;
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

    private PatchGuiInput() {}
}
