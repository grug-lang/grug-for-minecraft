package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * Which name a game method is found by (#1522).
 *
 * <p>The Alpha adapter resolves {@code World.getBlock} and {@code World.getBlockMetadata} by
 * reflection, because the a0.2.1 dedicated server names them in Ornithe's intermediary where the
 * a1.1.2_01 development jar names them after the source. A lookup by one name alone works on only
 * one of the two jars, which is the defect #1522's third finding names: the region packet's {@code
 * WorldSource} called {@code World.getBlock} directly and would throw {@code NoSuchMethodError} on
 * the server. The two fixtures below stand in for the two jars, so both lookups are proven without
 * a game.
 */
class GrugMethodAccessTest {

    /** A method as the development jar names it. */
    private static final class DevelopmentJar {
        public int getBlock(int x, int y, int z) {
            return x * 100 + y * 10 + z;
        }

        public int getBlockMetadata(int x, int y, int z) {
            return x + y + z;
        }
    }

    /** The same methods as the installed jar names them. */
    private static final class InstalledJar {
        public int m_33234383(int x, int y, int z) {
            return x * 100 + y * 10 + z;
        }

        public int m_06541281(int x, int y, int z) {
            return x + y + z;
        }
    }

    @Test
    void aDevelopmentJarIsReadByItsOwnName() throws NoSuchMethodException {
        Method method =
                GrugMethodAccess.method(
                        DevelopmentJar.class,
                        "getBlock",
                        "m_33234383",
                        int.class,
                        int.class,
                        int.class);

        assertEquals("getBlock", method.getName());
    }

    @Test
    void theIntermediaryNameIsUsedWhenTheDevelopmentOneIsMissing() throws NoSuchMethodException {
        Method method =
                GrugMethodAccess.method(
                        InstalledJar.class,
                        "getBlock",
                        "m_33234383",
                        int.class,
                        int.class,
                        int.class);

        assertEquals(
                "m_33234383",
                method.getName(),
                "the installed jar is read by its intermediary name");
    }

    @Test
    void eitherNameIsFoundWhereTheOtherIsMissing() throws ReflectiveOperationException {
        Method block =
                GrugMethodAccess.method(
                        InstalledJar.class,
                        "getBlock",
                        "m_33234383",
                        int.class,
                        int.class,
                        int.class);
        Method metadata =
                GrugMethodAccess.method(
                        InstalledJar.class,
                        "getBlockMetadata",
                        "m_06541281",
                        int.class,
                        int.class,
                        int.class);
        InstalledJar installed = new InstalledJar();

        assertEquals(
                123,
                block.invoke(installed, 1, 2, 3),
                "the server's name for the block read has to be servable here");
        assertEquals(
                6,
                metadata.invoke(installed, 1, 2, 3),
                "the server's name for the metadata read has to be servable here");

        Method developmentBlock =
                GrugMethodAccess.method(
                        DevelopmentJar.class,
                        "getBlock",
                        "m_33234383",
                        int.class,
                        int.class,
                        int.class);

        assertEquals(123, developmentBlock.invoke(new DevelopmentJar(), 1, 2, 3));
    }

    @Test
    void aJarWithNeitherNameReportsTheMissingMethod() {
        assertThrows(
                NoSuchMethodException.class,
                () ->
                        GrugMethodAccess.method(
                                DevelopmentJar.class,
                                "noSuchMethod",
                                "alsoMissing",
                                int.class,
                                int.class,
                                int.class));
    }
}
