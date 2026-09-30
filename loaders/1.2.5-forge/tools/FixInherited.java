import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Makes a mod's classes inheritance-aware before MCP reobfuscation.
 *
 * <p>Obfuscation renames Minecraft's members to one or two letters, and a mod is expected to
 * override them. Remapping the mod by name alone does not do that, because the mod's own classes
 * have no mapping entries: a method that overrides an obfuscated member keeps its development name
 * and stops overriding, and a reference to an inherited member names the mod's own class and keeps
 * its development name too. Both fail at run time (the override is never called, or a
 * NoSuchFieldError/NoSuchMethodError).
 *
 * <p>This pass consults the srg and the development class hierarchy:
 *
 * <ul>
 *   <li>a method declaration that overrides a mapped member is renamed to the mapped name;
 *   <li>a reference the mod's own class owns is either renamed alongside its declaration, when the
 *       mod declares it, or repointed at the mapped declaring class, when it is inherited.
 * </ul>
 *
 * <p>References the mapping already covers (owned by a Minecraft class) are left alone for
 * SpecialSource.
 *
 * <p>Usage: FixInherited &lt;in.jar&gt; &lt;minecraft-dev-classes-dir&gt; &lt;reobf.srg&gt;
 * &lt;out.jar&gt;
 */
public final class FixInherited {

    private static final Map<String, ClassNode> classes = new HashMap<>();
    private static final Set<String> modClasses = new HashSet<>();

    /** dev owner -> (method name + desc -> obfuscated name). */
    private static final Map<String, Map<String, String>> methodMap = new HashMap<>();

    /** dev owner -> (field name -> obfuscated name). */
    private static final Map<String, Map<String, String>> fieldMap = new HashMap<>();

    public static void main(String[] args) throws IOException {
        File inJar = new File(args[0]);
        File minecraftDir = new File(args[1]);
        File srg = new File(args[2]);
        File outJar = new File(args[3]);

        loadMapping(srg);
        loadDirectory(minecraftDir);

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

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            ClassNode node = new ClassNode();
            new ClassReader(entry.getValue()).accept(node, 0);
            classes.put(node.name, node);
            modClasses.add(node.name);
        }

        for (String name : new ArrayList<>(modClasses)) {
            rewrite(classes.get(name));
        }

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outJar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                byte[] bytes = entry.getValue();
                String entryName = entry.getKey();
                if (entryName.endsWith(".class")) {
                    ClassNode node = classes.get(entryName.substring(0, entryName.length() - 6));
                    ClassWriter writer = new ClassWriter(0);
                    node.accept(writer);
                    bytes = writer.toByteArray();
                }
                out.putNextEntry(new ZipEntry(entryName));
                out.write(bytes);
                out.closeEntry();
            }
        }
    }

    private static void rewrite(ClassNode node) {
        // 1. Rename the mod's own methods that override a mapped member, so they keep overriding.
        for (Object methodObject : node.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (isInitializer(method.name)) continue;
            String mapped = mappedDeclaringClass(node.name, method.name, method.desc);
            if (mapped != null) {
                method.name = methodMap.get(mapped).get(method.name + method.desc);
            }
        }

        // 2. Fix references this class owns.
        for (Object methodObject : node.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode) {
                    rewriteField(node, (FieldInsnNode) insn);
                } else if (insn instanceof MethodInsnNode) {
                    rewriteCall(node, (MethodInsnNode) insn);
                }
            }
        }
    }

    private static void rewriteField(ClassNode node, FieldInsnNode field) {
        // If the owner's mapping already names this field, SpecialSource will handle it.
        if (hasField(field.owner, field.name)) return;
        String declaring = mappedDeclaringField(field.owner, field.name);
        if (declaring == null) return;
        field.owner = declaring;
        field.name = fieldMap.get(declaring).get(field.name);
    }

    private static void rewriteCall(ClassNode node, MethodInsnNode call) {
        if (isInitializer(call.name)) return;
        String key = call.name + call.desc;

        // If the owner's mapping already names this method, SpecialSource will handle it.
        if (hasMethod(call.owner, call.name, call.desc)) return;

        String declaring = mappedDeclaringClass(call.owner, call.name, call.desc);
        if (declaring == null) return;

        if (modClasses.contains(call.owner)) {
            // A method the mod's own class declares. Its declaration was renamed in step 1, so the
            // call only has to be renamed too; the owner and the call kind stay as they are.
            call.name = methodMap.get(declaring).get(key);
            return;
        }

        // Inherited: point the call at the mapped declaring class, keeping the call kind valid.
        call.owner = declaring;
        call.name = methodMap.get(declaring).get(key);
        // ASM picks Methodref vs InterfaceMethodref from the node's itf flag, so keep the opcode
        // and the flag in step with what the declaring class actually is.
        call.itf = isInterface(declaring);
        if (call.getOpcode() == Opcodes.INVOKEINTERFACE && !call.itf) {
            call.setOpcode(Opcodes.INVOKEVIRTUAL);
        } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.itf) {
            call.setOpcode(Opcodes.INVOKEINTERFACE);
        }
    }

    private static boolean hasField(String owner, String name) {
        Map<String, String> fields = fieldMap.get(owner);
        return fields != null && fields.containsKey(name);
    }

    private static boolean hasMethod(String owner, String name, String desc) {
        Map<String, String> methods = methodMap.get(owner);
        return methods != null && methods.containsKey(name + desc);
    }

    private static String mappedDeclaringClass(String owner, String name, String desc) {
        for (String candidate : hierarchy(owner)) {
            ClassNode node = classes.get(candidate);
            if (node == null) continue;
            if (!declaresMethod(node, name, desc)) continue;
            Map<String, String> methods = methodMap.get(candidate);
            if (methods != null && methods.containsKey(name + desc)) {
                return candidate;
            }
        }
        return null;
    }

    private static String mappedDeclaringField(String owner, String name) {
        for (String candidate : hierarchy(owner)) {
            ClassNode node = classes.get(candidate);
            if (node == null) continue;
            if (!declaresField(node, name)) continue;
            Map<String, String> fields = fieldMap.get(candidate);
            if (fields != null && fields.containsKey(name)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isInterface(String internalName) {
        ClassNode node = classes.get(internalName);
        return node != null && (node.access & Opcodes.ACC_INTERFACE) != 0;
    }

    private static boolean isInitializer(String name) {
        return "<init>".equals(name) || "<clinit>".equals(name);
    }

    /** {@code owner} then its superclasses and interfaces, nearest first. */
    private static List<String> hierarchy(String owner) {
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!seen.add(current)) continue;
            order.add(current);
            ClassNode node = classes.get(current);
            if (node == null) continue;
            if (node.superName != null) queue.add(node.superName);
            if (node.interfaces != null) {
                for (Object iface : node.interfaces) {
                    queue.add((String) iface);
                }
            }
        }
        return order;
    }

    private static boolean declaresMethod(ClassNode node, String name, String desc) {
        for (Object methodObject : node.methods) {
            MethodNode method = (MethodNode) methodObject;
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean declaresField(ClassNode node, String name) {
        for (Object fieldObject : node.fields) {
            FieldNode field = (FieldNode) fieldObject;
            if (field.name.equals(name)) return true;
        }
        return false;
    }

    private static void loadMapping(File srg) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(
                        new java.io.InputStreamReader(
                                new FileInputStream(srg), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("MD: ")) {
                    // reobf.srg is dev-first:
                    // <dev owner>/<dev name> <dev desc> <obf owner>/<obf name> <obf desc>
                    String[] parts = line.substring(4).split(" ");
                    String dev = parts[0];
                    String devDesc = parts[1];
                    String obf = parts[2];
                    String devOwner = dev.substring(0, dev.lastIndexOf('/'));
                    String devName = dev.substring(dev.lastIndexOf('/') + 1);
                    String obfName = obf.substring(obf.lastIndexOf('/') + 1);
                    methodMap
                            .computeIfAbsent(devOwner, k -> new HashMap<>())
                            .put(devName + devDesc, obfName);
                } else if (line.startsWith("FD: ")) {
                    // reobf.srg is dev-first: <dev owner>/<dev field> <obf owner>/<obf field>
                    String[] parts = line.substring(4).split(" ");
                    String dev = parts[0];
                    String obf = parts[1];
                    String devOwner = dev.substring(0, dev.lastIndexOf('/'));
                    String devName = dev.substring(dev.lastIndexOf('/') + 1);
                    String obfName = obf.substring(obf.lastIndexOf('/') + 1);
                    fieldMap.computeIfAbsent(devOwner, k -> new HashMap<>()).put(devName, obfName);
                }
            }
        }
    }

    private static void loadDirectory(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                loadDirectory(child);
            } else if (child.getName().endsWith(".class")) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(child.toPath())).accept(node, 0);
                classes.put(node.name, node);
            }
        }
    }

    private FixInherited() {}
}
