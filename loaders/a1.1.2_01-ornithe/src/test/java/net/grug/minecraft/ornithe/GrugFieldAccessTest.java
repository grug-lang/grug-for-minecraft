package net.grug.minecraft.ornithe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Which name a game field is found by (#1486).
 *
 * <p>The dedicated server crashed before it read its world, on every run of the proof dispatch of
 * #1392: the adapter resolved {@code WorldChunkPacket.compressedSize} by that one name, which is
 * the a1.1.2_01 development name, while the installed a0.2.1 server jar names the field {@code
 * f_62093482} in Ornithe's intermediary. The two fixtures below stand in for the two jars, so both
 * lookups are proven without a game.
 */
class GrugFieldAccessTest {

    /** A field as the development jar names it. */
    private static final class DevelopmentJar {
        private int compressedSize;

        /** The field this fixture holds, so the resolved one can be compared with it. */
        int compressedSize() {
            return compressedSize;
        }
    }

    /** The same field as the installed jar names it. */
    private static final class InstalledJar {
        private int f_62093482;

        /** The field this fixture holds, so the resolved one can be compared with it. */
        int compressedSize() {
            return f_62093482;
        }
    }

    @Test
    void aDevelopmentJarIsReadByItsOwnName() throws NoSuchFieldException {
        Field field = GrugFieldAccess.field(DevelopmentJar.class, "compressedSize", "f_62093482");

        assertEquals("compressedSize", field.getName());
        assertEquals(int.class, field.getType());
        assertTrue(Modifier.isPrivate(field.getModifiers()));
    }

    @Test
    void anInstalledJarIsReadByItsIntermediaryName() throws NoSuchFieldException {
        Field field = GrugFieldAccess.field(InstalledJar.class, "compressedSize", "f_62093482");

        assertEquals("f_62093482", field.getName());
        assertEquals(int.class, field.getType());
    }

    @Test
    void eitherNameIsFoundWhereTheOtherIsMissing()
            throws IllegalAccessException, NoSuchFieldException {
        InstalledJar installed = new InstalledJar();
        GrugFieldAccess.field(InstalledJar.class, "compressedSize", "f_62093482")
                .setInt(installed, 7);
        assertEquals(7, installed.compressedSize(), "the client's name has to be servable here");

        DevelopmentJar development = new DevelopmentJar();
        GrugFieldAccess.field(DevelopmentJar.class, "compressedSize", "f_62093482")
                .setInt(development, 9);
        assertEquals(9, development.compressedSize(), "the installed name has to be servable here");
    }

    @Test
    void aJarWithNeitherNameReportsTheMissingField() {
        assertThrows(
                NoSuchFieldException.class,
                () -> GrugFieldAccess.field(InstalledJar.class, "noSuchField", "alsoMissing"));
    }
}
