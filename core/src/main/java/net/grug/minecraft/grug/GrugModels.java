package net.grug.minecraft.grug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a block model JSON's elements into the textured faces a loader draws, which is how the
 * draw_model host function draws a model on a version that has no model format of its own.
 *
 * <p>This is the game-independent half of Minecraft's own model baker, the part 1.20.6 does in
 * {@code BlockModel} and {@code FaceBakery}: follow the {@code parent} chain, merge the {@code
 * textures} maps with the child winning, take the elements of the nearest model that has any, and
 * turn each element face into four corners and their texture coordinates in the order the baker
 * puts them. Every loader, 1.20.6 included, draws from this, so a model draws the same faces on all
 * five.
 *
 * <p>What the model format has and this does not is refused rather than approximated: an element
 * rotated by anything but 0 degrees, and a face rotation. Drawing one unrotated would be a shape
 * the author did not write.
 */
public final class GrugModels {

    /** How far a chain of parents may go before it counts as a cycle. */
    public static final int MAX_DEPTH = 8;

    /**
     * The face names a model uses, indexed by side: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
     */
    private static final String[] SIDE_NAMES = {"down", "up", "north", "south", "west", "east"};

    /**
     * For each side, which of the element's bounds each corner takes on x, y and z: 0 is the
     * minimum and 1 the maximum. This is the game's {@code FaceInfo} table, so the corners come out
     * counter-clockwise seen from outside, in the same order the 1.20.6 baker gives them.
     */
    private static final int[][] CORNERS = {
        {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1},
        {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
        {1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0},
        {0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1},
        {0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1},
        {1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0}
    };

    /** The item models already reported as undrawable, so a frame does not report again. */
    private static final Set<String> REPORTED_ITEMS = new HashSet<>();

    /** Parsed models by the file they start from, so a chunk rebuild does not re-read them. */
    private static final Map<File, Cached> CACHE = new HashMap<>();

    @GrugGenerated("utility class: never instantiated")
    private GrugModels() {}

    /** A model that cannot be drawn, with the reason an author reads. */
    public static final class Defect extends Exception {
        public Defect(String message) {
            super(message);
        }
    }

    /**
     * The faces of the model at {@code resourcePath}, a path relative to the mods directory as a
     * grug resource string hands it over, such as {@code
     * buildcraft/assets/grug/models/block/redstone_engine.json}.
     */
    public static List<GrugQuad> load(File modsDir, String resourcePath) throws Defect {
        File file = new File(resourcePath);
        if (!file.isAbsolute()) {
            file = new File(modsDir, resourcePath);
        }
        return loadFile(modsDir, file);
    }

    /**
     * The faces of the model named {@code modelId}, such as {@code grug:block/redstone_engine},
     * looked for in every mod the way a parent is.
     */
    public static List<GrugQuad> loadById(File modsDir, String modelId) throws Defect {
        return loadFile(modsDir, modelFile(modsDir, modelId));
    }

    /**
     * The faces of the item model of the block {@code blockId}, such as {@code
     * grug:redstone_engine}, which is {@code grug:item/redstone_engine}: what a loader with no
     * model format of its own draws a custom-rendered block's item from. The game draws an item
     * many times a frame, so a model that cannot be drawn is reported once, as a mod-tree defect,
     * and null is returned for the loader to draw the block's ordinary item instead.
     */
    public static List<GrugQuad> itemModel(File modsDir, String blockId) {
        int colon = blockId.indexOf(':');
        String modelId = blockId.substring(0, colon + 1) + "item/" + blockId.substring(colon + 1);
        try {
            return loadById(modsDir, modelId);
        } catch (Defect defect) {
            synchronized (REPORTED_ITEMS) {
                if (REPORTED_ITEMS.add(blockId)) {
                    GrugModTreeDefect.report(
                            "The item form of "
                                    + blockId
                                    + " cannot be drawn: "
                                    + defect.getMessage());
                }
            }
            return null;
        }
    }

    /**
     * Where a texture reference's file lives in a mod: {@code grug:block/foo} is {@code
     * assets/grug/textures/block/foo.png}, and a reference with no namespace is a vanilla one.
     */
    public static String texturePath(String textureRef) {
        int colon = textureRef.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : textureRef.substring(0, colon);
        String path = colon < 0 ? textureRef : textureRef.substring(colon + 1);
        return "assets/" + namespace + "/textures/" + path + ".png";
    }

    /** Forgets every parsed model, so the next draw reads the files again. */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    private static synchronized List<GrugQuad> loadFile(File modsDir, File file) throws Defect {
        Cached cached = CACHE.get(file);
        if (cached != null && cached.isCurrent()) {
            return cached.quads;
        }

        List<File> chain = new ArrayList<>();
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        File current = file;
        String name = file.getPath();
        for (int depth = 0; ; depth++) {
            if (depth == MAX_DEPTH) {
                throw new Defect(
                        "The model "
                                + name
                                + " has a parent chain longer than "
                                + MAX_DEPTH
                                + " models, which is a cycle.");
            }
            JsonObject model = read(current, name);
            chain.add(current);

            JsonElement texturesElement = model.get("textures");
            if (texturesElement != null) {
                mergeTextures(current, texturesElement, textures);
            }

            JsonElement elementsElement = model.get("elements");
            if (elements == null && elementsElement != null) {
                if (!elementsElement.isJsonArray()) {
                    throw new Defect(
                            "The model " + current + " has 'elements' that is not an array.");
                }
                elements = elementsElement.getAsJsonArray();
            }

            JsonElement parent = model.get("parent");
            if (parent == null) {
                break;
            }
            if (!parent.isJsonPrimitive() || !parent.getAsJsonPrimitive().isString()) {
                throw new Defect("The model " + current + " has a 'parent' that is not a string.");
            }
            String parentId = parent.getAsString();
            if (parentId.indexOf(':') < 0 || parentId.startsWith("minecraft:")) {
                // A vanilla parent is not a file any mod ships. The vanilla models a block parents
                // carry display settings and texture keys, not elements, so the chain ends here.
                break;
            }
            current = modelFile(modsDir, parentId);
        }

        if (elements == null) {
            throw new Defect("The model " + name + " has no elements to draw.");
        }

        List<GrugQuad> quads = new ArrayList<>();
        for (JsonElement element : elements) {
            if (!element.isJsonObject()) {
                throw new Defect("The model " + name + " has an element that is not an object.");
            }
            addElement(name, element.getAsJsonObject(), textures, quads);
        }

        quads = Collections.unmodifiableList(quads);
        CACHE.put(file, new Cached(chain, quads));
        return quads;
    }

    /**
     * Merges a model's {@code textures} into {@code into}. The child is read first, so {@code
     * putIfAbsent} lets its entries win over a parent's, which is how the game reads the chain.
     */
    private static void mergeTextures(
            File file, JsonElement texturesElement, Map<String, String> into) throws Defect {
        if (!texturesElement.isJsonObject()) {
            throw new Defect("The model " + file + " has 'textures' that is not an object.");
        }
        for (Map.Entry<String, JsonElement> entry : texturesElement.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new Defect(
                        "The model "
                                + file
                                + " gives the texture key '"
                                + entry.getKey()
                                + "' a value that is not a string.");
            }
            into.putIfAbsent(entry.getKey(), value.getAsString());
        }
    }

    private static File modelFile(File modsDir, String modelId) throws Defect {
        int colon = modelId.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : modelId.substring(0, colon);
        String path = colon < 0 ? modelId : modelId.substring(colon + 1);
        String relative = "assets/" + namespace + "/models/" + path + ".json";
        File file = GrugResourceIndex.findDirectResource(modsDir, relative);
        if (file == null) {
            throw new Defect("No mod ships the model " + modelId + " at " + relative + ".");
        }
        return file;
    }

    private static JsonObject read(File file, String name) throws Defect {
        if (!file.isFile()) {
            throw new Defect("There is no model at " + name + ".");
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new Defect("The model " + file + " is not a JSON object.");
            }
            return root.getAsJsonObject();
        } catch (Defect defect) {
            throw defect;
        } catch (Exception e) {
            throw new Defect("The model " + file + " cannot be read: " + e.getMessage());
        }
    }

    private static void addElement(
            String name, JsonObject element, Map<String, String> textures, List<GrugQuad> quads)
            throws Defect {
        double[] from = vector(name, element, "from", 3);
        double[] to = vector(name, element, "to", 3);

        JsonElement rotation = element.get("rotation");
        if (rotation != null) {
            if (!rotation.isJsonObject()) {
                throw new Defect(
                        "The model " + name + " has an element 'rotation' that is not an object.");
            }
            double angle = number(name, rotation.getAsJsonObject().get("angle"), "rotation angle");
            if (angle != 0.0) {
                throw new Defect(
                        "The model "
                                + name
                                + " rotates an element by "
                                + angle
                                + " degrees, and draw_model only draws unrotated elements.");
            }
        }

        JsonElement facesElement = element.get("faces");
        if (facesElement == null || !facesElement.isJsonObject()) {
            throw new Defect("The model " + name + " has an element without a 'faces' object.");
        }
        JsonObject faces = facesElement.getAsJsonObject();

        for (int side = 0; side < SIDE_NAMES.length; side++) {
            JsonElement faceElement = faces.get(SIDE_NAMES[side]);
            if (faceElement == null) {
                continue;
            }
            if (!faceElement.isJsonObject()) {
                throw new Defect(
                        "The model "
                                + name
                                + " has a '"
                                + SIDE_NAMES[side]
                                + "' face that is not an object.");
            }
            quads.add(face(name, side, from, to, faceElement.getAsJsonObject(), textures));
        }
    }

    private static GrugQuad face(
            String name,
            int side,
            double[] from,
            double[] to,
            JsonObject face,
            Map<String, String> textures)
            throws Defect {
        JsonElement faceRotation = face.get("rotation");
        if (faceRotation != null && number(name, faceRotation, "face rotation") != 0.0) {
            throw new Defect(
                    "The model "
                            + name
                            + " rotates a face's texture, and draw_model only draws unrotated"
                            + " faces.");
        }

        String texture = texture(name, face, textures);

        double[] uv = face.has("uv") ? vector(name, face, "uv", 4) : defaultUv(side, from, to);

        int cullSide = -1;
        JsonElement cullface = face.get("cullface");
        if (cullface != null) {
            cullSide = cullface.isJsonPrimitive() ? sideNamed(cullface.getAsString()) : -1;
            if (cullSide < 0) {
                throw new Defect(
                        "The model "
                                + name
                                + " names the cullface "
                                + cullface
                                + ", which is not a side.");
            }
        }

        double[] positions = new double[12];
        double[] uvs = new double[8];
        for (int corner = 0; corner < 4; corner++) {
            for (int axis = 0; axis < 3; axis++) {
                double[] bound = CORNERS[side][corner * 3 + axis] == 0 ? from : to;
                positions[corner * 3 + axis] = bound[axis] / 16.0;
            }
            // The baker's BlockFaceUV.getU and getV for an unrotated face: the first two corners
            // take the first u and the last two the second, and the middle two take the second v.
            uvs[corner * 2] = (corner < 2 ? uv[0] : uv[2]) / 16.0;
            uvs[corner * 2 + 1] = (corner == 1 || corner == 2 ? uv[3] : uv[1]) / 16.0;
        }
        return new GrugQuad(texture, side, cullSide, positions, uvs);
    }

    /**
     * The texture coordinates the game gives a face with no explicit {@code uv}, from {@code
     * BlockElement.uvsByFace}: the element's own extent on the face, so the texture is clipped to
     * it rather than stretched across it.
     */
    static double[] defaultUv(int side, double[] from, double[] to) {
        switch (side) {
            case 0:
                return new double[] {from[0], 16.0 - to[2], to[0], 16.0 - from[2]};
            case 1:
                return new double[] {from[0], from[2], to[0], to[2]};
            case 2:
                return new double[] {16.0 - to[0], 16.0 - to[1], 16.0 - from[0], 16.0 - from[1]};
            case 3:
                return new double[] {from[0], 16.0 - to[1], to[0], 16.0 - from[1]};
            case 4:
                return new double[] {from[2], 16.0 - to[1], to[2], 16.0 - from[1]};
            default:
                return new double[] {16.0 - to[2], 16.0 - to[1], 16.0 - from[2], 16.0 - from[1]};
        }
    }

    private static String texture(String name, JsonObject face, Map<String, String> textures)
            throws Defect {
        JsonElement textureElement = face.get("texture");
        if (textureElement == null
                || !textureElement.isJsonPrimitive()
                || !textureElement.getAsJsonPrimitive().isString()) {
            throw new Defect("The model " + name + " has a face without a 'texture' string.");
        }
        String written = textureElement.getAsString();
        String ref;
        if (written.startsWith("#")) {
            ref = GrugFaceTextures.followKey(textures, written.substring(1));
        } else {
            ref = written.indexOf(':') < 0 ? "minecraft:" + written : written;
        }
        if (ref == null) {
            throw new Defect(
                    "The model "
                            + name
                            + " uses the texture '"
                            + written
                            + "', which its textures do not resolve.");
        }
        return ref;
    }

    private static int sideNamed(String sideName) {
        for (int side = 0; side < SIDE_NAMES.length; side++) {
            if (SIDE_NAMES[side].equals(sideName)) {
                return side;
            }
        }
        return -1;
    }

    private static double[] vector(String name, JsonObject owner, String key, int size)
            throws Defect {
        JsonElement value = owner.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != size) {
            throw new Defect(
                    "The model "
                            + name
                            + " needs '"
                            + key
                            + "' to be an array of "
                            + size
                            + " numbers.");
        }
        double[] result = new double[size];
        for (int i = 0; i < size; i++) {
            result[i] = number(name, value.getAsJsonArray().get(i), key);
        }
        return result;
    }

    private static double number(String name, JsonElement value, String what) throws Defect {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new Defect("The model " + name + " has a " + what + " that is not a number.");
        }
        return value.getAsDouble();
    }

    /** A parsed model, and the files it was read from with when each was last changed. */
    private static final class Cached {
        private final List<File> files;
        private final long[] modified;
        private final List<GrugQuad> quads;

        Cached(List<File> files, List<GrugQuad> quads) {
            this.files = files;
            this.modified = new long[files.size()];
            for (int i = 0; i < files.size(); i++) {
                modified[i] = files.get(i).lastModified();
            }
            this.quads = quads;
        }

        /** Whether every file is as it was when it was read, so a hot reload is seen. */
        boolean isCurrent() {
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).lastModified() != modified[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
