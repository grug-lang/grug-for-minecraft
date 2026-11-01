package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Covers the rule a shape follows when a script declares no selection box: the game targets what it
 * collides with, so the selection boxes are the collision boxes. Without that rule a block author
 * who declared only collision boxes, which is the common case, would get a block nothing can click.
 */
class GrugShapeTest {

    private static final GrugBox CENTRE = new GrugBox(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);

    @Test
    void aShapeWithoutASelectionBoxTargetsItsCollisionBoxes() {
        GrugShape shape = new GrugShape(List.of(CENTRE), List.of());

        assertEquals(List.of(CENTRE), shape.collision());
        assertEquals(List.of(CENTRE), shape.selection());
    }

    @Test
    void aShapeWithASelectionBoxKeepsTheSelectionItDeclared() {
        GrugBox arm = new GrugBox(0.0, 0.25, 0.25, 1.0, 0.75, 0.75);
        GrugShape shape = new GrugShape(List.of(CENTRE), List.of(arm));

        assertEquals(List.of(CENTRE), shape.collision());
        assertEquals(List.of(arm), shape.selection());
    }

    @Test
    void aShapeCanHaveASelectionBoxAndNoCollisionBox() {
        GrugShape shape = new GrugShape(List.of(), List.of(CENTRE));

        assertTrue(
                shape.collision().isEmpty(),
                "Nothing collides with a shape that declared no collision box.");
        assertEquals(List.of(CENTRE), shape.selection());
    }

    @Test
    void aShapesBoxesAreCopiesTheScriptCannotChangeLater() {
        List<GrugBox> collision = new ArrayList<>();
        collision.add(CENTRE);
        GrugShape shape = new GrugShape(collision, List.of());

        collision.add(new GrugBox(0, 0, 0, 1, 1, 1));

        assertEquals(1, shape.collision().size(), "A shape keeps the boxes it was built from.");
        assertThrows(UnsupportedOperationException.class, () -> shape.selection().add(CENTRE));
    }
}
