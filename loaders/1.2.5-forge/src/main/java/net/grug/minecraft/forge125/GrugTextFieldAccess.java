package net.grug.minecraft.forge125;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.GuiTextField;

import java.lang.reflect.Field;

/**
 * Writes the {@code GuiTextField} members the screen API needs that the game keeps private.
 *
 * <p>1.2.5's text field has no setter for the colour it draws its text in and no setter for whether
 * it is enabled, which are the two things {@code Widget.set_text_colour} and {@code
 * Widget.set_active} change. Reflection is the one route that does not patch the game class. This
 * is a loader class and stays in the loader's own package, because a class in the game's package
 * would be reobfuscated to the jar root.
 */
public final class GrugTextFieldAccess {

    private static final Field ENABLED_COLOR = field("enabledColor", "q");
    private static final Field DISABLED_COLOR = field("disabledColor", "r");
    private static final Field IS_ENABLED = field("isEnabled", "m");

    @GrugGenerated("utility class: never instantiated")
    private GrugTextFieldAccess() {}

    /**
     * The field by its MCP name, or by the name the pinned 1.2.5 snapshot reobfuscates it to. The
     * runtime jar is launched reobfuscated, so the MCP name is not there; a development run that
     * kept the names resolves the first one. The pairs are taken from the pinned snapshot's own
     * {@code client.srg}.
     */
    private static Field field(String mcpName, String obfuscatedName) {
        try {
            Field field;
            try {
                field = GuiTextField.class.getDeclaredField(mcpName);
            } catch (NoSuchFieldException missing) {
                field = GuiTextField.class.getDeclaredField(obfuscatedName);
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Sets the colour the field draws its text in, whose alpha the API ignores. Both colours are
     * set, because the mod names one colour for its field whether or not the field is active.
     */
    public static void setTextColour(GuiTextField field, int rgb) {
        try {
            ENABLED_COLOR.setInt(field, rgb & 0xFFFFFF);
            DISABLED_COLOR.setInt(field, rgb & 0xFFFFFF);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Makes the field take the keyboard and the mouse, or neither. */
    public static void setEnabled(GuiTextField field, boolean enabled) {
        try {
            IS_ENABLED.setBoolean(field, enabled);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
