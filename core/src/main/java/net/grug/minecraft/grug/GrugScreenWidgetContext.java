package net.grug.minecraft.grug;

/**
 * The context a screen's {@code screen_widget_changed} is passed, naming the widget the player used
 * and holding what its action left.
 *
 * <p>A context exists only while its call runs, so one saved in a member variable and used later is
 * refused.
 */
public final class GrugScreenWidgetContext {
    private final GrugOwnedScreen screen;
    private final GrugWidget widget;

    GrugScreenWidgetContext(GrugOwnedScreen screen, GrugWidget widget) {
        this.screen = screen;
        this.widget = widget;
    }

    /** The screen the used widget belongs to. */
    public GrugOwnedScreen screen() {
        return screen;
    }

    /** The Widget the player used. */
    public GrugWidget widget() {
        return widget;
    }
}
