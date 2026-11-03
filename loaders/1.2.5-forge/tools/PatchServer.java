import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Hooks Forge Mod Loader into the vanilla 1.2.5 dedicated server, in searge names.
 *
 * <p>FML ships its server hooks as source patches against a Fernflower decompile, and this build
 * never decompiles the server (the client's MCP snapshot already holds a compiled client, but the
 * server was never built). The few calls those patches add to {@code MinecraftServer} are inserted
 * into the bytecode here instead, at the same places:
 *
 * <ul>
 *   <li>{@code FMLServerHandler.onPreLoad(this)} right after the "Starting minecraft server
 *       version" line of {@code startServer}, which finds and loads every mod;
 *   <li>{@code FMLServerHandler.onLoadComplete()} just before the server builds its {@code
 *       ServerConfigurationManager}, which initialises them;
 *   <li>{@code rescheduleTicks()} then {@code onPreServerTick()} and {@code onPostServerTick()} around the per-tick method.
 * </ul>
 *
 * <p>FML's patches also add a few public methods to vanilla classes, which FML's own sources call
 * (the registration tables a mod's {@code registerTileEntity} and {@code registerEntityID} reach,
 * {@code StatBase.getName}, the language getters). Those are added here as forwarders to the
 * private members the patches forwarded to. {@code WorldType.addNewBiome} and {@code removeBiome}
 * are empty: the patched world type keeps a biome array that the vanilla server's does not have,
 * and nothing here adds a biome.
 *
 * <p>The other FML server patches (login and packet handling, spawn tables, and so on) are for
 * features a mod using them needs; nothing here reaches them.
 *
 * <p>Usage: PatchServer &lt;in.jar&gt; &lt;out.jar&gt;, both in searge names.
 */
public final class PatchServer {

    private static final String SERVER = "net/minecraft/server/MinecraftServer";
    private static final String HANDLER = "cpw/mods/fml/server/FMLServerHandler";
    private static final String START = "func_6008_d";
    private static final String TICK = "func_6018_h";
    private static final String STARTING = "Starting minecraft server version 1.2.5";
    private static final String MANAGER = "net/minecraft/src/ServerConfigurationManager";

    public static void main(String[] args) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new FileInputStream(args[0]))) {
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
        String target = SERVER + ".class";
        byte[] original = entries.get(target);
        if (original == null) {
            throw new IllegalStateException("No " + target + " in " + args[0]);
        }
        entries.put(target, patch(original));
        addForwarders(entries);
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }

    private static void addForwarders(Map<String, byte[]> entries) {
        String src = "net/minecraft/src/";
        String cls = "Ljava/lang/Class;";
        String str = "Ljava/lang/String;";
        // StatBase.getName(): the stat's name field.
        edit(
                entries,
                src + "StatBase",
                node -> {
                    InsnList code = new InsnList();
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "field_25062_e", str));
                    code.add(new InsnNode(Opcodes.ARETURN));
                    add(node, Opcodes.ACC_PUBLIC, "getName", "()" + str, code);
                });
        edit(
                entries,
                src + "StringTranslate",
                node -> {
                    InsnList language = new InsnList();
                    language.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    language.add(
                            new FieldInsnNode(Opcodes.GETFIELD, node.name, "field_44012_d", str));
                    language.add(new InsnNode(Opcodes.ARETURN));
                    add(node, Opcodes.ACC_PUBLIC, "getCurrentLanguage", "()" + str, language);
                    InsnList table = new InsnList();
                    table.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    table.add(
                            new FieldInsnNode(
                                    Opcodes.GETFIELD,
                                    node.name,
                                    "field_25081_b",
                                    "Ljava/util/Properties;"));
                    table.add(new InsnNode(Opcodes.ARETURN));
                    add(
                            node,
                            Opcodes.ACC_PUBLIC,
                            "getCurrentLanguageTable",
                            "()Ljava/util/Properties;",
                            table);
                });
        edit(
                entries,
                src + "TileEntity",
                node -> {
                    InsnList code = new InsnList();
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    code.add(
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    node.name,
                                    "func_187_a",
                                    "(" + cls + str + ")V",
                                    false));
                    code.add(new InsnNode(Opcodes.RETURN));
                    add(
                            node,
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "addNewTileEntityMapping",
                            "(" + cls + str + ")V",
                            code);
                });
        edit(
                entries,
                src + "EntityList",
                node -> {
                    InsnList plain = new InsnList();
                    plain.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    plain.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    plain.add(new VarInsnNode(Opcodes.ILOAD, 2));
                    plain.add(
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    node.name,
                                    "func_563_a",
                                    "(" + cls + str + "I)V",
                                    false));
                    plain.add(new InsnNode(Opcodes.RETURN));
                    add(
                            node,
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "addNewEntityListMapping",
                            "(" + cls + str + "I)V",
                            plain);
                    InsnList egg = new InsnList();
                    egg.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    egg.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    for (int i = 2; i <= 4; i++) {
                        egg.add(new VarInsnNode(Opcodes.ILOAD, i));
                    }
                    egg.add(
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    node.name,
                                    "func_46122_a",
                                    "(" + cls + str + "III)V",
                                    false));
                    egg.add(new InsnNode(Opcodes.RETURN));
                    add(
                            node,
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "addNewEntityListMapping",
                            "(" + cls + str + "III)V",
                            egg);
                    InsnList map = new InsnList();
                    map.add(
                            new FieldInsnNode(
                                    Opcodes.GETSTATIC,
                                    node.name,
                                    "field_849_a",
                                    "Ljava/util/Map;"));
                    map.add(new InsnNode(Opcodes.ARETURN));
                    add(
                            node,
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                            "getEntityToClassMapping",
                            "()Ljava/util/Map;",
                            map);
                    // The vanilla field is a raw Map, and FML's source reads it as a Map of
                    // classes.
                    ((MethodNode) node.methods.get(node.methods.size() - 1)).signature =
                            "()Ljava/util/Map<Ljava/lang/String;Ljava/lang/Class<+L"
                                    + src
                                    + "Entity;>;>;";
                });
        edit(
                entries,
                src + "WorldType",
                node -> {
                    String biome = "(L" + src + "BiomeGenBase;)V";
                    for (String name : new String[] {"addNewBiome", "removeBiome"}) {
                        InsnList code = new InsnList();
                        code.add(new InsnNode(Opcodes.RETURN));
                        add(node, Opcodes.ACC_PUBLIC, name, biome, code);
                    }
                });
    }

    private static void edit(
            Map<String, byte[]> entries,
            String name,
            java.util.function.Consumer<ClassNode> change) {
        byte[] original = entries.get(name + ".class");
        if (original == null) {
            throw new IllegalStateException("No " + name + ".class in the server jar");
        }
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        change.accept(node);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        entries.put(name + ".class", writer.toByteArray());
    }

    private static void add(ClassNode node, int access, String name, String desc, InsnList code) {
        MethodNode method = new MethodNode(access, name, desc, null, null);
        method.instructions = code;
        node.methods.add(method);
    }

    private static byte[] patch(byte[] original) {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        MethodNode start = find(node, START, "()Z");
        MethodNode tick = find(node, TICK, "()V");

        // After the info call that follows the "Starting minecraft server version" constant.
        AbstractInsnNode starting = null;
        for (AbstractInsnNode i = start.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i instanceof LdcInsnNode && STARTING.equals(((LdcInsnNode) i).cst)) {
                starting = i;
                break;
            }
        }
        if (starting == null) {
            throw new IllegalStateException(START + " never logs \"" + STARTING + "\"");
        }
        AbstractInsnNode info = starting.getNext();
        if (!(info instanceof MethodInsnNode) || !((MethodInsnNode) info).name.equals("info")) {
            throw new IllegalStateException(
                    START + " does not call info after \"" + STARTING + "\"");
        }
        InsnList preLoad = new InsnList();
        preLoad.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HANDLER, "instance", "()L" + HANDLER + ";", false));
        preLoad.add(new VarInsnNode(Opcodes.ALOAD, 0));
        preLoad.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, HANDLER, "onPreLoad", "(L" + SERVER + ";)V", false));
        start.instructions.insert(info, preLoad);

        // Before `new ServerConfigurationManager(this)`.
        AbstractInsnNode manager = null;
        for (AbstractInsnNode i = start.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i.getOpcode() == Opcodes.NEW && ((TypeInsnNode) i).desc.equals(MANAGER)) {
                manager = i;
                break;
            }
        }
        if (manager == null) {
            throw new IllegalStateException(START + " never creates a " + MANAGER);
        }
        start.instructions.insertBefore(manager, handlerCall("onLoadComplete"));

        tick.instructions.insert(handlerCall("onPreServerTick"));
        // FML's own patch schedules the tick handlers first; without it none is ever due.
        InsnList reschedule = new InsnList();
        reschedule.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "cpw/mods/fml/common/FMLCommonHandler",
                        "instance",
                        "()Lcpw/mods/fml/common/FMLCommonHandler;",
                        false));
        reschedule.add(
                new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        "cpw/mods/fml/common/FMLCommonHandler",
                        "rescheduleTicks",
                        "()V",
                        false));
        tick.instructions.insert(reschedule);
        for (AbstractInsnNode i = tick.instructions.getFirst(); i != null; i = i.getNext()) {
            if (i.getOpcode() == Opcodes.RETURN) {
                tick.instructions.insertBefore(i, handlerCall("onPostServerTick"));
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static InsnList handlerCall(String name) {
        InsnList list = new InsnList();
        list.add(
                new MethodInsnNode(
                        Opcodes.INVOKESTATIC, HANDLER, "instance", "()L" + HANDLER + ";", false));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, HANDLER, name, "()V", false));
        return list;
    }

    private static MethodNode find(ClassNode node, String name, String desc) {
        for (Object o : node.methods) {
            MethodNode method = (MethodNode) o;
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new IllegalStateException(SERVER + "." + name + desc + " not found");
    }
}
