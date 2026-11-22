package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The String functions a mod edits text with, such as the text of a search field (#865). */
class GrugStringFunctionsTest {
    @Test
    void concatJoinsInOrder() {
        assertEquals("abc", HostFunctions.String_concat("ab", "c"));
        assertEquals("c", HostFunctions.String_concat("", "c"));
        assertEquals("", HostFunctions.String_concat("", ""));
    }

    @Test
    void lengthCountsACharacterOutsideTheBasicPlaneOnce() {
        assertEquals(0, HostFunctions.String_length(""));
        assertEquals(3, HostFunctions.String_length("abc"));
        assertEquals(2, HostFunctions.String_length("a😀"));
    }

    @Test
    void aCharacterCodeMustBeAWholeCodePointThatIsNotASurrogate() {
        assertNull(HostFunctions.characterProblem(0));
        assertNull(HostFunctions.characterProblem(97));
        assertNull(HostFunctions.characterProblem(0x1F600));
        assertNull(HostFunctions.characterProblem(Character.MAX_CODE_POINT));
        assertTrue(HostFunctions.characterProblem(-1).contains("whole number"));
        assertTrue(HostFunctions.characterProblem(0.5).contains("whole number"));
        assertTrue(HostFunctions.characterProblem(0xD800).contains("surrogate"));
        assertTrue(HostFunctions.characterProblem(0xDFFF).contains("surrogate"));
        assertTrue(HostFunctions.characterProblem(0x110000).contains("whole number"));
        assertTrue(HostFunctions.characterProblem(Double.NaN).contains("whole number"));
    }

    @Test
    void aSubstringRangeMustBeWholeAndInsideTheText() {
        assertNull(HostFunctions.substringProblem("abc", 0, 3));
        assertNull(HostFunctions.substringProblem("abc", 1, 1));
        assertNull(HostFunctions.substringProblem("", 0, 0));
        assertTrue(HostFunctions.substringProblem("abc", -1, 2).contains("0 <= start <= end <= 3"));
        assertTrue(HostFunctions.substringProblem("abc", 2, 1).contains("whole numbers"));
        assertTrue(HostFunctions.substringProblem("abc", 0, 4).contains("whole numbers"));
        assertTrue(HostFunctions.substringProblem("abc", 0.5, 2).contains("whole numbers"));
        assertTrue(HostFunctions.substringProblem("abc", 0, 2.5).contains("whole numbers"));
        assertTrue(HostFunctions.substringProblem("abc", Double.NaN, 2).contains("whole"));
    }
}
