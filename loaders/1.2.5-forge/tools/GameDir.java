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
import org.objectweb.asm.tree.TypeInsnNode;
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
 * Patches the runtime's {@code Minecraft.getMinecraftDir} to answer the game directory the launch
 * command names, before the original static lookup.
 *
 * <p>1.2.5 launches through launchwrapper 1.5, whose {@code VanillaTweakInjector} stores the {@code
 * --gameDir} value in {@code Minecraft.minecraftDir} in {@code Minecraft.main}'s epilogue. The game
 * thread starts earlier: FML's {@code preGameLoad} constructs the client and starts "Minecraft main
 * thread", whose {@code startGame} reads the field once, after {@code Display.create}. The epilogue
 * then calls {@code inject}, whose icon work also touches {@code Display} and waits behind that
 * same window creation, so a loaded runner can let the read happen before the write and the client
 * resolves {@code %APPDATA%\.minecraft} (on Windows) or {@code ~/.minecraft} instead of the run
 * directory. FML then scans the wrong mods directory, finds no grug jar and the client sits on the
 * title screen with no mod loaded. That is the {@code 1.2.5-forge (Windows)} boot timeout in #1184:
 * the failed job's log says {@code Loading mods from
 * C:\Users\runneradmin\AppData\Roaming\.minecraft\mods} and {@code Forge Mod Loader has loaded 1
 * mods}, while the passing rerun of the same commit says {@code ...\run\mods}, {@code 2 mods},
 * {@code Successfully loaded grug (Forge 1.2.5)!} and {@code [GRUG CI] GRUG LOADED}.
 *
 * <p>The injected code reads the {@code grug.gameDir} system property, which the launch command
 * passes from the same value as {@code --gameDir}, and returns it when set. The game directory
 * therefore no longer depends on which thread wins, and the launcher's directory is the one the
 * game reads.
 *
 * <p>Usage: GameDir &lt;in.jar&gt; &lt;out.jar&gt;
 */
public final class GameDir {

    private static final String TARGET_CLASS = "net/minecraft/client/Minecraft";
    private static final String TARGET_METHOD = "getMinecraftDir";
    private static final String TARGET_DESCRIPTOR = "()Ljava/io/File;";

    /** The system property the launch command sets to the game directory it passed. */
    private static final String PROPERTY = "grug.gameDir";

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
     * Returns the class with a property check placed at the start of {@code getMinecraftDir}, which
     * answers the launch command's directory when it is set and falls through to the original
     * static lookup otherwise.
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

        target.instructions.insert(override());

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
     * The guarded answer: when the property is set, return a File for it without touching the
     * launcher-written static field; otherwise fall through to the original method.
     */
    private static InsnList override() {
        LabelNode original = new LabelNode();
        InsnList override = new InsnList();
        override.add(new LdcInsnNode(PROPERTY));
        override.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/System",
                        "getProperty",
                        "(Ljava/lang/String;)Ljava/lang/String;",
                        false));
        override.add(new VarInsnNode(Opcodes.ASTORE, 0));
        override.add(new VarInsnNode(Opcodes.ALOAD, 0));
        override.add(new JumpInsnNode(Opcodes.IFNULL, original));
        override.add(new TypeInsnNode(Opcodes.NEW, "java/io/File"));
        override.add(new InsnNode(Opcodes.DUP));
        override.add(new VarInsnNode(Opcodes.ALOAD, 0));
        override.add(
                new MethodInsnNode(
                        Opcodes.INVOKESPECIAL,
                        "java/io/File",
                        "<init>",
                        "(Ljava/lang/String;)V",
                        false));
        override.add(new InsnNode(Opcodes.ARETURN));
        override.add(original);
        return override;
    }

    private GameDir() {}
}
