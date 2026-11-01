package net.minecraft.src;

import net.grug.minecraft.grug.GrugGenerated;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads and drives the protected members of a container screen for grug's overlay.
 *
 * <p>1.2.5 has no access widener or Mixin, and its {@code GuiContainer} keeps the GUI rectangle and
 * the press handlers protected. This loader's classes are defined by ModLoader's class loader, so a
 * class in the game's package is still a different runtime package and cannot read those members
 * directly; reflection is the one route that does not patch the game classes.
 */
public final class GrugGuiAccess {

    private static final Field GUI_LEFT = field("guiLeft", "e");
    private static final Field GUI_TOP = field("guiTop", "f");
    private static final Field X_SIZE = field("xSize", "b");
    private static final Field Y_SIZE = field("ySize", "c");
    private static final Method MOUSE_CLICKED = method("mouseClicked", "a");
    private static final Method MOUSE_MOVED_OR_UP = method("mouseMovedOrUp", "b");

    @GrugGenerated("utility class: never instantiated")
    private GrugGuiAccess() {}

    /**
     * The field by its MCP name, or by the name the pinned 1.2.5 snapshot reobfuscates it to. The
     * runtime jar is launched reobfuscated, so the MCP name is not there; a development run that
     * kept the names resolves the first one. The pairs are taken from the pinned snapshot's own
     * {@code client.srg}, which names the four fields and two methods this class needs.
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

    /** The method by its MCP name, or by the name the pinned snapshot reobfuscates it to. */
    private static Method method(String mcpName, String obfuscatedName) {
        try {
            Method method;
            try {
                method =
                        GuiScreen.class.getDeclaredMethod(mcpName, int.class, int.class, int.class);
            } catch (NoSuchMethodException missing) {
                method =
                        GuiScreen.class.getDeclaredMethod(
                                obfuscatedName, int.class, int.class, int.class);
            }
            method.setAccessible(true);
            return method;
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

    /** Presses a mouse button on the screen, as its own input handler would. */
    public static void mouseClicked(GuiScreen screen, int x, int y, int button) {
        invoke(MOUSE_CLICKED, screen, x, y, button);
    }

    /** Releases or moves the mouse on the screen, as its own input handler would. */
    public static void mouseMovedOrUp(GuiScreen screen, int x, int y, int button) {
        invoke(MOUSE_MOVED_OR_UP, screen, x, y, button);
    }

    private static void invoke(Method method, GuiScreen screen, int x, int y, int button) {
        try {
            method.invoke(screen, x, y, button);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
