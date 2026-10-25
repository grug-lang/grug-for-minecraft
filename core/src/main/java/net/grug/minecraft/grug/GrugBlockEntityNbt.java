package net.grug.minecraft.grug;

import net.grug.minecraft.core.GrugCore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Test only: edits to a block entity's own NBT, staged and then loaded in one go.
 *
 * <p>A recreation test sets up the port's state through its grug saved data, which the reference
 * mod's block entities do not have. What every block entity keeps, on every loader and in every
 * mod, is the NBT it writes and reads, which holds everything a player sees after the world is
 * loaded again. So a reference branch names keys of that NBT, and the block entity reads the edited
 * NBT back through its own load, which keeps whatever the mod derives from a key consistent with
 * it. A grug block entity's NBT is edited the same way, which is how every loader's side of this is
 * tested without a reference.
 *
 * <p>The edits are staged rather than each loaded on its own, because a load can rebuild what it
 * reads: BuildCraft's pipe makes a new transport and only adopts the loaded items on its next tick,
 * so a second load in the same tick would write the tile without them.
 *
 * <p>A path is the keys from the block entity's compound to the one set, joined by dots. A part
 * made only of digits is an index into a list, a list's size appends a new compound to it, and an
 * index is always followed by a key, since the lists this reaches hold compounds. A missing key on
 * the way is created as the compound or list the next part needs.
 */
public final class GrugBlockEntityNbt {

    /**
     * A loader's NBT, as the few operations the path walk needs. A compound or list is the loader's
     * own object. Getting a compound or list from a key or index that holds something else throws
     * {@link ClassCastException}.
     */
    public interface Access {
        Object write(Object blockEntity);

        void read(Object blockEntity, Object compound);

        Object newCompound();

        Object newList();

        boolean contains(Object compound, String key);

        Object getCompound(Object compound, String key);

        Object getList(Object compound, String key);

        void put(Object compound, String key, Object element);

        /** Puts a number as the NBT type named, one of {@link #TYPES}. */
        void putNumber(Object compound, String key, String type, double value);

        /** Puts a stack of one of the item, undamaged, as the compound the game saves it as. */
        void putItem(Object compound, String key, Object item);

        int size(Object list);

        Object getCompound(Object list, int index);

        void add(Object list, Object element);
    }

    /** One edit: the path of the key it sets, and either a number of an NBT type or an item. */
    static final class Edit {
        final List<Object> path;

        /** One of {@link #TYPES}, or null for an item. */
        final String type;

        final double number;

        final Object item;

        Edit(List<Object> path, String type, double number, Object item) {
            this.path = path;
            this.type = type;
            this.number = number;
            this.item = item;
        }
    }

    /** The NBT numeric types a number can be stored as. */
    public static final List<String> TYPES =
            Collections.unmodifiableList(
                    Arrays.asList("byte", "short", "int", "long", "float", "double"));

    private static final Map<Object, List<Edit>> STAGED = new IdentityHashMap<>();

    /**
     * Where {@link #report} sends a problem: the grug call that asked, as a host function error.
     */
    static Consumer<String> reporter =
            message -> Grug.hostFunctionErrorHappened(Grug.statePtr, message);

    private GrugBlockEntityNbt() {}

    /** Stages a number, or returns why it cannot be. */
    public static synchronized String stageNumber(
            Object blockEntity, String path, String type, double value) {
        if (!TYPES.contains(type)) {
            return "The type has to be one of " + TYPES + ", not \"" + type + "\".";
        }
        String problem = checkValue(type, value);
        if (problem != null) {
            return problem;
        }
        return stage(blockEntity, path, type, value, null);
    }

    /** Stages an item, or returns why it cannot be. */
    public static synchronized String stageItem(Object blockEntity, String path, Object item) {
        return stage(blockEntity, path, null, 0, item);
    }

    /** Loads every staged edit into the block entity through the running loader's NBT. */
    public static String load(Object blockEntity) {
        return load(blockEntity, GrugCore.getAdapter().blockEntityNbt());
    }

    /**
     * Writes the block entity's NBT, applies every staged edit in order and has the block entity
     * read the result, or returns why an edit does not fit. Nothing is read when one does not.
     */
    static synchronized String load(Object blockEntity, Access nbt) {
        List<Edit> edits = STAGED.remove(blockEntity);
        if (edits == null) {
            return "Nothing was staged for this block entity.";
        }
        Object root = nbt.write(blockEntity);
        for (Edit edit : edits) {
            String problem = apply(nbt, root, edit);
            if (problem != null) {
                return problem;
            }
        }
        nbt.read(blockEntity, root);
        return null;
    }

    /** Reports the problem one of the three calls returned, as coming from that grug function. */
    public static void report(String function, String problem) {
        if (problem != null) {
            reporter.accept(function + ": " + problem);
        }
    }

    private static String apply(Access nbt, Object root, Edit edit) {
        Object compound = root;
        int last = edit.path.size() - 1;
        for (int i = 0; i < last; i++) {
            String key = (String) edit.path.get(i);
            Object next = edit.path.get(i + 1);
            try {
                if (next instanceof Integer) {
                    compound = childOfList(nbt, compound, key, (Integer) next, edit);
                    i++;
                } else {
                    compound = childCompound(nbt, compound, key);
                }
            } catch (ClassCastException e) {
                return "The key \""
                        + key
                        + "\" in "
                        + describe(edit)
                        + " does not hold a "
                        + (next instanceof Integer ? "list" : "compound")
                        + ".";
            } catch (IndexOutOfBoundsException e) {
                return e.getMessage();
            }
        }
        String key = (String) edit.path.get(last);
        if (edit.type == null) {
            nbt.putItem(compound, key, edit.item);
        } else {
            nbt.putNumber(compound, key, edit.type, edit.number);
        }
        return null;
    }

    private static Object childCompound(Access nbt, Object compound, String key) {
        if (nbt.contains(compound, key)) {
            return nbt.getCompound(compound, key);
        }
        Object child = nbt.newCompound();
        nbt.put(compound, key, child);
        return child;
    }

    /** The compound at the index of the list under the key, appended when the index is its size. */
    private static Object childOfList(
            Access nbt, Object compound, String key, int index, Edit edit) {
        Object list;
        if (nbt.contains(compound, key)) {
            list = nbt.getList(compound, key);
        } else {
            list = nbt.newList();
            nbt.put(compound, key, list);
        }
        int size = nbt.size(list);
        if (index < size) {
            return nbt.getCompound(list, index);
        }
        if (index > size) {
            throw new IndexOutOfBoundsException(
                    "The index "
                            + index
                            + " in "
                            + describe(edit)
                            + " is past the end of a list of "
                            + size
                            + ", and only its size appends.");
        }
        Object child = nbt.newCompound();
        nbt.add(list, child);
        return child;
    }

    private static String describe(Edit edit) {
        StringBuilder path = new StringBuilder();
        for (Object part : edit.path) {
            if (path.length() > 0) {
                path.append('.');
            }
            path.append(part);
        }
        return "the path \"" + path + "\"";
    }

    private static String stage(
            Object blockEntity, String path, String type, double number, Object item) {
        List<Object> parsed = new ArrayList<>();
        String problem = parsePath(path, parsed);
        if (problem != null) {
            return problem;
        }
        List<Edit> edits = STAGED.get(blockEntity);
        if (edits == null) {
            edits = new ArrayList<>();
            STAGED.put(blockEntity, edits);
        }
        edits.add(new Edit(Collections.unmodifiableList(parsed), type, number, item));
        return null;
    }

    /** Splits a dotted path into its keys and indices, or returns why it is not a path. */
    static String parsePath(String path, List<Object> parts) {
        String[] pieces = path.split("\\.", -1);
        for (String piece : pieces) {
            if (piece.isEmpty()) {
                return "The path \"" + path + "\" has an empty part.";
            }
            boolean index = piece.matches("[0-9]+");
            boolean afterIndex = !parts.isEmpty() && parts.get(parts.size() - 1) instanceof Integer;
            if (index && (parts.isEmpty() || afterIndex)) {
                return "The path \"" + path + "\" has an index that does not follow a key.";
            }
            if (index && piece.length() > 9) {
                return "The index " + piece + " in the path \"" + path + "\" is too large.";
            }
            parts.add(index ? (Object) Integer.valueOf(piece) : piece);
        }
        if (parts.get(parts.size() - 1) instanceof Integer) {
            return "The path \"" + path + "\" has to end in a key, not an index.";
        }
        return null;
    }

    /** A float or a double takes any number, and an integer type a whole one in its range. */
    static String checkValue(String type, double value) {
        double min;
        double max;
        switch (type) {
            case "byte":
                min = Byte.MIN_VALUE;
                max = Byte.MAX_VALUE;
                break;
            case "short":
                min = Short.MIN_VALUE;
                max = Short.MAX_VALUE;
                break;
            case "int":
                min = Integer.MIN_VALUE;
                max = Integer.MAX_VALUE;
                break;
            case "long":
                min = Long.MIN_VALUE;
                max = Long.MAX_VALUE;
                break;
            default:
                return null;
        }
        if (value != Math.rint(value) || value < min || value > max) {
            return "A "
                    + type
                    + " has to be a whole number from "
                    + (long) min
                    + " to "
                    + (long) max
                    + ", not "
                    + value
                    + ".";
        }
        return null;
    }
}
