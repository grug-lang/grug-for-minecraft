package net.grug.minecraft.grug;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One screen a mod created with {@code Screen.new()}: the widgets its {@code screen_init} declared,
 * whether it is the screen that is open, and the overlay script it belongs to.
 *
 * <p>The model is the same on every loader. A loader mirrors it into its own widgets (a vanilla
 * button, checkbox, slider, text field or its list) and reports a player's action back, which is
 * what asks {@code screen_widget_changed}. A mod's own {@code set_*} and {@code select} change the
 * model only, so writing a value never asks the export.
 *
 * <p>The widgets are keyed by the name their declaration gave them and keep the order they were
 * first declared in, which is the order the frame draws them. A {@code screen_init} after a resize
 * answers the same Widget for a name it declares again; an open time that ends drops them all, so a
 * screen that is opened again starts with fresh widgets, as {@code screen_closed} documents.
 */
public final class GrugOwnedScreen {

    /** What one declaration did: the widget it answered, or why it could not be declared. */
    static final class Declared {
        final GrugWidget widget;
        final String problem;

        private Declared(GrugWidget widget, String problem) {
            this.widget = widget;
            this.problem = problem;
        }

        static Declared of(GrugWidget widget) {
            return new Declared(widget, null);
        }

        static Declared refused(String problem) {
            return new Declared(null, problem);
        }
    }

    private final GrugOverlayScripts.Script script;
    private final List<GrugObject> roots;
    private final long entityId;

    private final Map<String, GrugWidget> widgets = new LinkedHashMap<>();
    private final Set<String> declaredThisInit = new HashSet<>();

    private boolean open;

    /**
     * How many open times this screen has started. A loader's screen host records it when it is
     * made, so a host the game removed after the mod reopened the screen can tell that its removal
     * is stale: the game's removal of the previous host must not end the open time that came after
     * it (#1871's review).
     */
    private long generation;

    GrugOwnedScreen(GrugOverlayScripts.Script script) {
        this.script = script;
        this.roots = script.owner;
        this.entityId = GrugEntities.addEntity(GrugEntityType.Screen, this, roots);
    }

    public long entityId() {
        return entityId;
    }

    /** The overlay script this screen belongs to, whose exports are the screen's own. */
    public GrugOverlayScripts.Script script() {
        return script;
    }

    public boolean open() {
        return open;
    }

    /**
     * Which open time this is, from 1 up. A loader's host keeps the value it was made with and
     * hands it back with the removal the game reported, so a host replaced by a reopened screen
     * does not end the newer open time.
     */
    public long generation() {
        return generation;
    }

    void setOpen(boolean nowOpen) {
        if (nowOpen && !open) generation++;
        open = nowOpen;
    }

    /** The widgets in declaration order, which is the order the frame draws them. */
    public List<GrugWidget> widgets() {
        return new ArrayList<>(widgets.values());
    }

    /** The widget a name declared, or null when no declaration has that name. */
    public GrugWidget widget(String name) {
        return widgets.get(name);
    }

    void beginInit() {
        declaredThisInit.clear();
    }

    /**
     * Ends one {@code screen_init}: the widgets a screen has are the ones that call declared, so a
     * widget it did not declare this time is taken off the screen and its handle stops resolving.
     * That is what makes a narrower layout after a resize drop the widgets it no longer shows. A
     * call that could not be asked at all keeps the widgets declared before it, because nothing
     * said they were gone.
     */
    void endInit(boolean asked) {
        if (asked) {
            Iterator<Map.Entry<String, GrugWidget>> iterator = widgets.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, GrugWidget> entry = iterator.next();
                if (declaredThisInit.contains(entry.getKey())) continue;
                GrugWidget widget = entry.getValue();
                GrugOwnedScreens.widgetDropped(widget.entityId, widget.name());
                GrugEntities.removeEntity(widget.entityId, roots);
                iterator.remove();
            }
        }
        declaredThisInit.clear();
    }

    /**
     * Ends this screen's open time: the widgets it declared are no longer on it, so the mod's saved
     * handles stop resolving, as {@code screen_closed} documents.
     */
    void dropWidgets() {
        for (GrugWidget widget : widgets.values()) {
            GrugOwnedScreens.widgetDropped(widget.entityId, widget.name());
            GrugEntities.removeEntity(widget.entityId, roots);
        }
        widgets.clear();
        declaredThisInit.clear();
    }

    private GrugWidget reuse(
            GrugWidgetKind kind, String name, double x, double y, double width, double height) {
        GrugWidget widget = widgets.get(name);
        if (widget == null) {
            widget = new GrugWidget(this, kind, name);
            widget.entityId = GrugEntities.addEntity(GrugEntityType.Widget, widget, roots);
            widgets.put(name, widget);
        }
        widget.x = x;
        widget.y = y;
        widget.width = width;
        widget.height = height;
        declaredThisInit.add(name);
        return widget;
    }

    /**
     * The answer a declaration of {@code name} as {@code kind} gets, before its own arguments are
     * applied: why it is refused, or null when the name may be declared now.
     */
    private String declareProblem(GrugWidgetKind kind, String name) {
        if (!GrugOwnedScreens.isInitializing(this)) {
            return "A widget is declared only while this screen's screen_init is running, and "
                    + "this call is not inside it.";
        }
        if (declaredThisInit.contains(name)) {
            return "\""
                    + name
                    + "\" was already declared as a "
                    + widgets.get(name).kind().description()
                    + " in this screen_init.";
        }
        GrugWidget existing = widgets.get(name);
        if (existing != null && !existing.is(kind)) {
            return "\""
                    + name
                    + "\" was declared as a "
                    + existing.kind().description()
                    + " on this screen already, so it cannot be declared as a "
                    + kind.description()
                    + ".";
        }
        return null;
    }

    Declared declareButton(
            String name, String text, double x, double y, double width, double height) {
        String problem = declareProblem(GrugWidgetKind.BUTTON, name);
        if (problem != null) return Declared.refused(problem);
        GrugWidget widget = reuse(GrugWidgetKind.BUTTON, name, x, y, width, height);
        widget.setText(text);
        return Declared.of(widget);
    }

    Declared declareCheckbox(
            String name,
            String text,
            boolean checked,
            double x,
            double y,
            double width,
            double height) {
        String problem = declareProblem(GrugWidgetKind.CHECKBOX, name);
        if (problem != null) return Declared.refused(problem);
        GrugWidget widget = reuse(GrugWidgetKind.CHECKBOX, name, x, y, width, height);
        widget.setText(text);
        widget.setChecked(checked);
        return Declared.of(widget);
    }

    Declared declareList(
            String name, double x, double y, double width, double height, double rowHeight) {
        String problem = declareProblem(GrugWidgetKind.LIST, name);
        if (problem != null) return Declared.refused(problem);
        GrugWidget widget = reuse(GrugWidgetKind.LIST, name, x, y, width, height);
        widget.rowHeight = rowHeight;
        widget.scroll = GrugListMath.clampScroll(widget.scroll, widget.rows, rowHeight, height);
        return Declared.of(widget);
    }

    Declared declareSlider(
            String name,
            String text,
            double min,
            double max,
            double value,
            double x,
            double y,
            double width,
            double height) {
        String range = GrugSliderMath.rangeProblem(min, max);
        if (range != null) return Declared.refused(range);
        String problem = declareProblem(GrugWidgetKind.SLIDER, name);
        if (problem != null) return Declared.refused(problem);
        GrugWidget widget = reuse(GrugWidgetKind.SLIDER, name, x, y, width, height);
        widget.setText(text);
        widget.min = min;
        widget.max = max;
        // The game's own slider clamps the value it is given, so a declaration starts at the value
        // the handle would be drawn at, and value() answers that.
        widget.value = GrugSliderMath.clamp(value, min, max);
        return Declared.of(widget);
    }

    Declared declareTextField(
            String name, String text, double x, double y, double width, double height) {
        String problem = declareProblem(GrugWidgetKind.TEXT_FIELD, name);
        if (problem != null) return Declared.refused(problem);
        GrugWidget widget = reuse(GrugWidgetKind.TEXT_FIELD, name, x, y, width, height);
        widget.setText(text);
        return Declared.of(widget);
    }
}
