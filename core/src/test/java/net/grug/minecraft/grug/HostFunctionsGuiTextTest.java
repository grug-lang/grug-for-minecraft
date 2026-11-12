package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.gui.GrugGuiLayoutCodec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The length {@link GrugGuiLayoutCodec#MAX_TEXT} puts on one label, as {@code GUI.add_text} asks it
 * (#1223). A client's reader refuses a layout whose text is longer than that, and a client that
 * refuses a layout loses the connection the server sent it over, so the call that would add one is
 * the thing that fails, rather than the packet that carries the layout.
 *
 * <p>The codec refuses that text when it writes one too, so no caller can send it however it built
 * the layout; {@link net.grug.minecraft.gui.GrugGuiLayoutCodecTest} pins that half.
 */
class HostFunctionsGuiTextTest {

    /** Whether a run was active before this test, put back afterwards. */
    private boolean savedRunActive;

    @BeforeEach
    void aRunIsActiveSoTheReportIsTheRunsOwn() {
        savedRunActive = GrugTestState.testRunActive;
        GrugTestState.testRunActive = true;
        GrugErrors.takeHostFunctionException();
    }

    @AfterEach
    void restoreTheRunAndWhatWasReported() {
        GrugTestState.testRunActive = savedRunActive;
        GrugErrors.takeHostFunctionException();
    }

    @Test
    void theLongestTextTheReaderAcceptsIsAdded() {
        GrugGuiBuilder layout = new GrugGuiBuilder("textures/gui/test.png");
        String longest = "a".repeat(GrugGuiLayoutCodec.MAX_TEXT);

        HostFunctions.GUI_add_text(liveGui(layout), longest, 1, 2, liveColor());

        assertEquals(1, layout.texts.size());
        assertEquals(longest, layout.texts.get(0).text());
        assertNull(GrugErrors.takeHostFunctionException(), "a label that fits is not refused");
    }

    @Test
    void aTextLongerThanTheReaderAcceptsIsReportedAndNotAdded() {
        GrugGuiBuilder layout = new GrugGuiBuilder("textures/gui/test.png");
        String tooLong = "a".repeat(GrugGuiLayoutCodec.MAX_TEXT + 1);

        HostFunctions.GUI_add_text(liveGui(layout), tooLong, 1, 2, liveColor());

        assertTrue(layout.texts.isEmpty(), "a text no client would read must not be added");
        assertEquals(
                "GUI.add_text: A GUI's text is at most "
                        + GrugGuiLayoutCodec.MAX_TEXT
                        + " bytes long, so this one of "
                        + (GrugGuiLayoutCodec.MAX_TEXT + 1)
                        + " bytes is not added.",
                GrugErrors.takeHostFunctionException());
    }

    private static long liveGui(GrugGuiBuilder layout) {
        return GrugEntities.addEntity(GrugEntityType.GUI, layout);
    }

    private static long liveColor() {
        return GrugEntities.addEntity(GrugEntityType.Color, new Color(1, 2, 3));
    }
}
