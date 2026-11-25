package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.World;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

/**
 * Which name a game method is found by (#1522).
 *
 * <p>The Alpha adapter resolves {@code World.getBlock} and {@code World.getBlockMetadata} by
 * reflection, because a name held in a string is not remapped with the code around it: the
 * development classpath names the two reads after the source and an installed jar names them in
 * Ornithe's intermediary. The first test below reads the real {@code World} the adapter passes, so
 * the lookup is proven against the class itself rather than only against fixtures, and the others
 * pin the order and the missing-name arm.
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

    /** The same methods as an installed jar names them, with no development name at all. */
    private static final class InstalledJar {
        public int m_33234383(int x, int y, int z) {
            return x * 100 + y * 10 + z;
        }

        public int m_06541281(int x, int y, int z) {
            return x + y + z;
        }
    }

    /**
     * Both names of one read, standing in for a jar that carries the named and the intermediary.
     */
    private static final class BothNames {
        public int getBlock(int x, int y, int z) {
            return 1;
        }

        public int m_33234383(int x, int y, int z) {
            return 2;
        }
    }

    @Test
    void theRealWorldCarriesTheNamesTheAdapterLooksUp() throws NoSuchMethodException {
        // The class the adapter passes on both sides is net.minecraft.world.World, not a fixture.
        Method block =
                GrugMethodAccess.method(
                        World.class, "getBlock", "m_33234383", int.class, int.class, int.class);
        Method metadata =
                GrugMethodAccess.method(
                        World.class,
                        "getBlockMetadata",
                        "m_06541281",
                        int.class,
                        int.class,
                        int.class);

        assertEquals("getBlock", block.getName(), "World should carry the development name");
        assertEquals(
                "getBlockMetadata", metadata.getName(), "World should carry the development name");
        assertTrue(
                block.getDeclaringClass().isAssignableFrom(World.class),
                "the block read found on World should be the world's own or an inherited one");
    }

    @Test
    void theDevelopmentNameWinsWhenBothNamesExist() throws NoSuchMethodException {
        Method method =
                GrugMethodAccess.method(
                        BothNames.class, "getBlock", "m_33234383", int.class, int.class, int.class);

        assertEquals(
                "getBlock",
                method.getName(),
                "a jar that carries both names should be read by the development one");
    }

    @Test
    void theIntermediaryNameIsUsedWhenTheDevelopmentOneIsMissing()
            throws ReflectiveOperationException {
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

        assertEquals("m_33234383", block.getName(), "an installed jar is read by its own name");
        assertEquals(123, block.invoke(installed, 1, 2, 3), "the block read has to answer here");
        assertEquals(
                6,
                metadata.invoke(installed, 1, 2, 3),
                "the server's name for the metadata read has to answer here");
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
