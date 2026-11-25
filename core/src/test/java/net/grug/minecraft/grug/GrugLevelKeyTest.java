package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** Covers the key of every dimension and every arm of a level's name. */
class GrugLevelKeyTest {

    @Test
    void aNumberedDimensionKeepsTheThreeNamesTheGameShips() {
        assertEquals("overworld", GrugLevelKey.ofNumber(0));
        assertEquals("the_nether", GrugLevelKey.ofNumber(-1));
        assertEquals("the_end", GrugLevelKey.ofNumber(1));
    }

    @Test
    void anyOtherNumberedDimensionIsItsOwnKey() {
        assertEquals("dimension_2", GrugLevelKey.ofNumber(2));
        assertEquals("dimension_-2", GrugLevelKey.ofNumber(-2));
        assertEquals("dimension_255", GrugLevelKey.ofNumber(255));
    }

    @Test
    void aNamedDimensionKeepsTheThreeNamesTheGameShips() {
        assertEquals("overworld", GrugLevelKey.ofKey("minecraft", "overworld"));
        assertEquals("the_nether", GrugLevelKey.ofKey("minecraft", "the_nether"));
        assertEquals("the_end", GrugLevelKey.ofKey("minecraft", "the_end"));
    }

    @Test
    void anyOtherNamedDimensionIsOnePathSegment() {
        assertEquals(
                "dimension_minecraft~caves+deep", GrugLevelKey.ofKey("minecraft", "caves/deep"));
        assertEquals("dimension_example~caves_deep", GrugLevelKey.ofKey("example", "caves_deep"));
        assertEquals("dimension_a_b~c", GrugLevelKey.ofKey("a_b", "c"));
        assertEquals("dimension_a~b_c", GrugLevelKey.ofKey("a", "b_c"));
    }

    @Test
    void twoDifferentNamedDimensionsNeverShareAKey() {
        // A slash turned into an underscore would make these one dimension, as would a namespace
        // boundary that a plain join loses.
        assertNotEquals(
                GrugLevelKey.ofKey("example", "caves/deep"),
                GrugLevelKey.ofKey("example", "caves_deep"));
        assertNotEquals(GrugLevelKey.ofKey("a_b", "c"), GrugLevelKey.ofKey("a", "b_c"));
    }

    @Test
    void aDialledAddressIsTheNameAClientKnows() {
        assertEquals("1.2.3.4:25565", GrugLevelKey.name("1.2.3.4:25565", "a save"));
    }

    @Test
    void withoutAnAddressTheLevelsOwnNameAnswers() {
        assertEquals("a save", GrugLevelKey.name("", "a save"));
        assertEquals("a save", GrugLevelKey.name(null, "a save"));
    }

    @Test
    void aLevelWithNoNameAtAllAnswersWorld() {
        assertEquals("world", GrugLevelKey.name("", ""));
        assertEquals("world", GrugLevelKey.name(null, null));
        assertEquals("world", GrugLevelKey.name("", null));
    }
}
