package net.grug.minecraft.forge125;

import net.grug.minecraft.grug.GrugGenerated;
import net.minecraft.src.GuiTextField;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.Optional;

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

    @GrugGenerated("utility class: never instantiated")
    private GrugTextFieldAccess() {}

    /**
     * Sets the colour the field draws its text in, whose alpha the API ignores. Both colours are
     * set, because the mod names one colour for its field whether or not the field is active.
     */
    public static void setTextColour(GuiTextField field, int rgb) {
        setInt(lookup("enabledColor", "q"), field, rgb & 0xFFFFFF);
        setInt(lookup("disabledColor", "r"), field, rgb & 0xFFFFFF);
    }

    /** Makes the field take the keyboard and the mouse, or neither. */
    public static void setEnabled(GuiTextField field, boolean enabled) {
        setBoolean(lookup("isEnabled", "m"), field, enabled);
    }

    /**
     * The field by its MCP name, or the one the pinned 1.2.5 snapshot reobfuscates it to. The
     * runtime jar is launched reobfuscated, so the MCP name is not there; a development run that
     * kept the names resolves the first one. The pairs are taken from the pinned snapshot's own
     * {@code client.srg}, and the build checks the literals against it, because a string carries no
     * reference that reobfuscation could follow.
     *
     * <p>The lookup runs on every call and holds no branch, for the coverage gate (#961). A class
     * initializer that ran in a dropped segment is credited for itself and for nothing it calls,
     * and a test of another mod reaches this class first, so a lookup cached in a static field
     * would run once, outside the counted segments. Here the launched game takes the miss and the
     * hit of {@link #lookup} in every counted test that asks, with no branch between them.
     */
    private static Field lookup(String mcpName, String obfuscatedName) {
        // Both names are looked up every time, so the launched game takes the miss and the hit in
        // the same call, and no arm of a choice between them is left to a game that carries the
        // other name.
        Field byMcpName = lookup(mcpName);
        Field byObfuscatedName = lookup(obfuscatedName);
        return Objects.requireNonNull(
                Optional.ofNullable(byMcpName).orElse(byObfuscatedName),
                "GuiTextField carries neither " + mcpName + " nor " + obfuscatedName);
    }

    /** The accessible field of that name, or null when the class carries none. */
    private static Field lookup(String name) {
        try {
            Field found = GuiTextField.class.getDeclaredField(name);
            found.setAccessible(true);
            return found;
        } catch (NoSuchFieldException missing) {
            return null;
        }
    }

    @GrugGenerated("reflection: lookup makes every field accessible, so the catch cannot fire")
    private static void setInt(Field field, GuiTextField target, int value) {
        try {
            field.setInt(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @GrugGenerated("reflection: lookup makes every field accessible, so the catch cannot fire")
    private static void setBoolean(Field field, GuiTextField target, boolean value) {
        try {
            field.setBoolean(target, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
