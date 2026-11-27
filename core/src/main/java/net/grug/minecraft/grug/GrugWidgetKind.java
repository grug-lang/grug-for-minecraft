package net.grug.minecraft.grug;

/**
 * The five kinds a mod-owned screen's widget can be, each named as the mod-facing API names it, so
 * a method a widget's kind does not have can say what the widget is.
 */
public enum GrugWidgetKind {
    BUTTON("button"),
    CHECKBOX("checkbox"),
    LIST("list"),
    SLIDER("slider"),
    TEXT_FIELD("text field");

    private final String description;

    GrugWidgetKind(String description) {
        this.description = description;
    }

    /** The kind as {@code Widget}'s errors and the API's prose name it. */
    public String description() {
        return description;
    }
}
