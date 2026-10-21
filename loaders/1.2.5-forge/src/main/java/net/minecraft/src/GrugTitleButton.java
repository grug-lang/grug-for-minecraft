package net.minecraft.src;

/**
 * Adds a button to a screen's control list, which 1.2.5 keeps in a protected field.
 *
 * <p>It lives in the game's own package, as {@link GrugTorchFlicker} does, so that it can reach the
 * field. The main menu has no hook a mod can add a button through, so the client hooks add it
 * themselves once the title screen is up (#335).
 */
public final class GrugTitleButton {
    private GrugTitleButton() {}

    /** Whether the screen's control list holds a button of this class. */
    public static boolean has(GuiScreen screen, Class<? extends GuiButton> type) {
        for (Object control : screen.controlList) {
            if (type.isInstance(control)) return true;
        }
        return false;
    }

    public static void add(GuiScreen screen, GuiButton button) {
        screen.controlList.add(button);
    }
}
