package net.grug.minecraft.forge125;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.GuiContainer;

import java.lang.reflect.Field;

/**
 * Reads the protected members of a container screen for grug's overlay.
 *
 * <p>1.2.5 has no access widener or Mixin, and its {@code GuiContainer} keeps the GUI rectangle
 * protected. Reflection is the one route to it that does not patch the game classes. This is a
 * loader class and stays in the loader's own package, because a class in the game's package would
 * be reobfuscated to the jar root.
 */
public final class GrugGuiAccess {

    private static final Field GUI_LEFT = field("guiLeft", "e");
    private static final Field GUI_TOP = field("guiTop", "f");
    private static final Field X_SIZE = field("xSize", "b");
    private static final Field Y_SIZE = field("ySize", "c");

    @GrugGenerated("utility class: never instantiated")
    private GrugGuiAccess() {}

    /**
     * The field by its MCP name, or by the name the pinned 1.2.5 snapshot reobfuscates it to. The
     * runtime jar is launched reobfuscated, so the MCP name is not there; a development run that
     * kept the names resolves the first one. The pairs are taken from the pinned snapshot's own
     * {@code client.srg}, which names the four fields this class needs.
     */
    private static Field field(String mcpName, String obfuscatedName) {
        try {
            Field field;
            try {
                field = GuiContainer.class.getDeclaredField(mcpName);
            } catch (NoSuchFieldException missing) {
                field = GuiContainer.class.getDeclaredField(obfuscatedName);
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int read(Field field, GuiContainer screen) {
        try {
            return field.getInt(screen);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The screen's left edge, the x its GUI is drawn from. */
    public static int guiLeft(GuiContainer screen) {
        return read(GUI_LEFT, screen);
    }

    /** The screen's top edge, the y its GUI is drawn from. */
    public static int guiTop(GuiContainer screen) {
        return read(GUI_TOP, screen);
    }

    /** The width of the screen's GUI. */
    public static int xSize(GuiContainer screen) {
        return read(X_SIZE, screen);
    }

    /** The height of the screen's GUI. */
    public static int ySize(GuiContainer screen) {
        return read(Y_SIZE, screen);
    }
}
