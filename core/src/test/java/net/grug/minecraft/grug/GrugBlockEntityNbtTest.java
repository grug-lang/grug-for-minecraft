package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Covers {@link GrugBlockEntityNbt}'s staging and path walk against a fake NBT of maps and lists.
 * Each loader's {@link GrugBlockEntityNbt.Access} is covered by mods/coverage's nbt tests instead.
 */
class GrugBlockEntityNbtTest {
    private final Object blockEntity = new Object();
    private final FakeNbt nbt = new FakeNbt();
    private final Consumer<String> originalReporter = GrugBlockEntityNbt.reporter;

    @AfterEach
    void dropWhateverATestLeftStaged() {
        GrugBlockEntityNbt.load(blockEntity, nbt);
        GrugBlockEntityNbt.reporter = originalReporter;
    }

    @Test
    void aLoadAppliesEveryEditInOrderAndReadsOnce() {
        List<Object> records = new ArrayList<>();
        records.add(new LinkedHashMap<String, Object>());
        nbt.saved.put("travelingEntities", records);
        nbt.saved.put("engine", new LinkedHashMap<String, Object>());
        Object item = new Object();

        assertNull(GrugBlockEntityNbt.stageNumber(blockEntity, "orientation", "int", 3));
        assertNull(GrugBlockEntityNbt.stageNumber(blockEntity, "engine.progress", "float", 0.5));
        assertNull(GrugBlockEntityNbt.stageItem(blockEntity, "travelingEntities.1.Item", item));
        assertNull(
                GrugBlockEntityNbt.stageNumber(blockEntity, "travelingEntities.1.x", "double", 2));
        assertNull(GrugBlockEntityNbt.stageNumber(blockEntity, "travelingEntities.0.y", "byte", 1));
        assertNull(GrugBlockEntityNbt.stageNumber(blockEntity, "new.inner.z", "long", 4));
        assertNull(GrugBlockEntityNbt.stageNumber(blockEntity, "made.0.w", "short", 5));
        assertNull(GrugBlockEntityNbt.load(blockEntity, nbt));

        assertEquals(1, nbt.reads);
        Map<?, ?> read = nbt.read;
        assertEquals("int 3.0", read.get("orientation"));
        assertEquals("float 0.5", ((Map<?, ?>) read.get("engine")).get("progress"));
        List<?> travelling = (List<?>) read.get("travelingEntities");
        assertEquals(2, travelling.size());
        assertEquals("byte 1.0", ((Map<?, ?>) travelling.get(0)).get("y"));
        assertSame(item, ((Map<?, ?>) travelling.get(1)).get("Item"));
        assertEquals("double 2.0", ((Map<?, ?>) travelling.get(1)).get("x"));
        assertEquals("long 4.0", ((Map<?, ?>) ((Map<?, ?>) read.get("new")).get("inner")).get("z"));
        assertEquals("short 5.0", ((Map<?, ?>) ((List<?>) read.get("made")).get(0)).get("w"));

        assertEquals(
                "Nothing was staged for this block entity.",
                GrugBlockEntityNbt.load(blockEntity, nbt));
    }

    @Test
    void anEditThatDoesNotFitIsReturnedAndNothingIsRead() {
        nbt.saved.put("energy", "int 1.0");
        GrugBlockEntityNbt.stageNumber(blockEntity, "energy.x", "int", 1);
        assertEquals(
                "The key \"energy\" in the path \"energy.x\" does not hold a compound.",
                GrugBlockEntityNbt.load(blockEntity, nbt));

        GrugBlockEntityNbt.stageNumber(blockEntity, "energy.0.x", "int", 1);
        assertEquals(
                "The key \"energy\" in the path \"energy.0.x\" does not hold a list.",
                GrugBlockEntityNbt.load(blockEntity, nbt));

        nbt.saved.put("items", new ArrayList<>());
        GrugBlockEntityNbt.stageNumber(blockEntity, "items.1.x", "int", 1);
        assertEquals(
                "The index 1 in the path \"items.1.x\" is past the end of a list of 0, and only its"
                        + " size appends.",
                GrugBlockEntityNbt.load(blockEntity, nbt));

        assertEquals(0, nbt.reads);
    }

    @Test
    void onlyAnNbtNumericTypeIsTaken() {
        assertEquals(
                "The type has to be one of [byte, short, int, long, float, double], not \"bool\".",
                GrugBlockEntityNbt.stageNumber(blockEntity, "energy", "bool", 1));
    }

    @Test
    void anIntegerTypeTakesOnlyAWholeNumberInItsRange() {
        assertNull(GrugBlockEntityNbt.checkValue("byte", -128));
        assertNull(GrugBlockEntityNbt.checkValue("byte", 127));
        assertEquals(
                "A byte has to be a whole number from -128 to 127, not 128.0.",
                GrugBlockEntityNbt.checkValue("byte", 128));
        assertEquals(
                "A short has to be a whole number from -32768 to 32767, not -32769.0.",
                GrugBlockEntityNbt.checkValue("short", -32769));
        assertEquals(
                "A int has to be a whole number from -2147483648 to 2147483647, not 0.5.",
                GrugBlockEntityNbt.stageNumber(blockEntity, "energy", "int", 0.5));
        assertNull(GrugBlockEntityNbt.checkValue("long", 1e15));
        assertTrue(GrugBlockEntityNbt.checkValue("long", Double.NaN).startsWith("A long has"));
        assertNull(GrugBlockEntityNbt.checkValue("float", 0.1));
        assertNull(GrugBlockEntityNbt.checkValue("double", 0.1));
    }

    @Test
    void aPathIsKeysWithAnIndexOnlyAfterAKeyAndEndingInAKey() {
        List<Object> parts = new ArrayList<>();
        assertNull(GrugBlockEntityNbt.parsePath("a.12.b", parts));
        assertEquals(Arrays.asList("a", 12, "b"), parts);

        assertEquals(
                "The path \"a..b\" has an empty part.",
                GrugBlockEntityNbt.stageItem(blockEntity, "a..b", new Object()));
        assertEquals(
                "The path \"\" has an empty part.",
                GrugBlockEntityNbt.parsePath("", new ArrayList<>()));
        assertEquals(
                "The path \"0.a\" has an index that does not follow a key.",
                GrugBlockEntityNbt.parsePath("0.a", new ArrayList<>()));
        assertEquals(
                "The path \"a.0.1.b\" has an index that does not follow a key.",
                GrugBlockEntityNbt.parsePath("a.0.1.b", new ArrayList<>()));
        assertEquals(
                "The path \"a.0\" has to end in a key, not an index.",
                GrugBlockEntityNbt.parsePath("a.0", new ArrayList<>()));
        assertEquals(
                "The index 1234567890 in the path \"a.1234567890.b\" is too large.",
                GrugBlockEntityNbt.parsePath("a.1234567890.b", new ArrayList<>()));
    }

    @Test
    void aProblemIsReportedAsTheFunctionsAndNothingElseIs() {
        List<String> reported = new ArrayList<>();
        GrugBlockEntityNbt.reporter = reported::add;
        GrugBlockEntityNbt.report("Test.load_nbt", null);
        GrugBlockEntityNbt.report("Test.load_nbt", "Nothing was staged.");
        assertEquals(Arrays.asList("Test.load_nbt: Nothing was staged."), reported);
    }

    /**
     * NBT as maps for compounds, lists for lists and "type value" strings for numbers, which casts
     * a key or index of another kind and so throws as a loader's NBT does.
     */
    @SuppressWarnings("unchecked")
    private static final class FakeNbt implements GrugBlockEntityNbt.Access {
        final Map<String, Object> saved = new LinkedHashMap<>();
        Map<String, Object> read;
        int reads;

        @Override
        public Object write(Object blockEntity) {
            return saved;
        }

        @Override
        public void read(Object blockEntity, Object compound) {
            read = (Map<String, Object>) compound;
            reads++;
        }

        @Override
        public Object newCompound() {
            return new LinkedHashMap<String, Object>();
        }

        @Override
        public Object newList() {
            return new ArrayList<>();
        }

        @Override
        public boolean contains(Object compound, String key) {
            return ((Map<String, Object>) compound).containsKey(key);
        }

        @Override
        public Object getCompound(Object compound, String key) {
            return (Map<String, Object>) ((Map<String, Object>) compound).get(key);
        }

        @Override
        public Object getList(Object compound, String key) {
            return (List<Object>) ((Map<String, Object>) compound).get(key);
        }

        @Override
        public void put(Object compound, String key, Object element) {
            ((Map<String, Object>) compound).put(key, element);
        }

        @Override
        public void putNumber(Object compound, String key, String type, double value) {
            ((Map<String, Object>) compound).put(key, type + " " + value);
        }

        @Override
        public void putItem(Object compound, String key, Object item) {
            ((Map<String, Object>) compound).put(key, item);
        }

        @Override
        public int size(Object list) {
            return ((List<Object>) list).size();
        }

        @Override
        public Object getCompound(Object list, int index) {
            return (Map<String, Object>) ((List<Object>) list).get(index);
        }

        @Override
        public void add(Object list, Object element) {
            ((List<Object>) list).add(element);
        }
    }
}
