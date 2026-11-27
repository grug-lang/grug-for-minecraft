package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The four rules every response class carries, proved for {@link GrugListRowResponse} against the
 * host functions a mod calls, since a grug test can only break them one player action at a time and
 * the rules are the same for every class.
 */
class GrugListRowResponseTest {

    private boolean savedRunActive;
    private GrugWidget widget;
    private long responseId;
    private GrugListRowResponse response;

    @BeforeEach
    void makeAResponse() {
        savedRunActive = GrugTestState.testRunActive;
        GrugTestState.testRunActive = true;
        GrugErrors.takeHostFunctionException();
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
        widget = new GrugWidget(null, GrugWidgetKind.LIST, "rows");
        widget.entityId = GrugEntities.addEntity(GrugEntityType.Widget, widget);
        response = new GrugListRowResponse(3, widget);
        responseId = GrugEntities.addEntity(GrugEntityType.ListRowResponse, response);
    }

    @AfterEach
    void clearState() {
        GrugTestState.testRunActive = savedRunActive;
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
    }

    private static String reported() {
        return GrugErrors.takeHostFunctionException();
    }

    @Test
    void itAnswersItsIndexAndItsList() {
        assertEquals(3.0, HostFunctions.ListRowResponse_index(responseId));
        assertSame(
                widget,
                GrugEntities.entityData.get(HostFunctions.ListRowResponse_widget(responseId))
                        .object);
        assertNull(reported());
    }

    @Test
    void aReadyResponseIsUnansweredUntilTextIsSet() {
        assertFalse(response.finish());
        assertNotNull(reported());
    }

    @Test
    void oneTextAnswerFinishesTheRow() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        assertTrue(response.finish());
        assertEquals("apple", response.answeredText());
        assertEquals(-1, response.answeredRgb(), "no colour was asked for");
        assertNull(reported());
    }

    @Test
    void aColourIsNotAnAnswerOnItsOwn() {
        long colour = GrugEntities.addEntity(GrugEntityType.Color, new Color(1, 2, 3));
        HostFunctions.ListRowResponse_set_text_colour(responseId, colour);
        assertEquals(0x010203, response.answeredRgb());
        assertFalse(response.finish());
        assertTrue(reported().contains("returned without answering"));
    }

    @Test
    void aTextOfNoCharactersIsAnEmptyRow() {
        HostFunctions.ListRowResponse_set_text(responseId, null);
        assertTrue(response.finish());
        assertEquals("", response.answeredText());
        assertNull(reported());
    }

    @Test
    void aSecondAnswerWithTheSameTextIsAnErr() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        String report = reported();
        assertNotNull(report);
        assertTrue(report.contains("already been answered"));
        assertTrue(response.finish(), "the first answer stands");
        assertEquals("apple", response.answeredText());
    }

    @Test
    void aSecondAnswerWithDifferentTextIsAnErrAndTheFirstStands() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        HostFunctions.ListRowResponse_set_text(responseId, "pear");
        assertTrue(reported().contains("already been answered"));
        assertTrue(response.finish());
        assertEquals("apple", response.answeredText());
    }

    @Test
    void aUsedAfterItsCallReturnedResponseIsAnErrEverywhere() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        response.finish();

        assertEquals(0.0, HostFunctions.ListRowResponse_index(responseId));
        assertTrue(reported().contains("only valid during"));

        assertEquals(0, HostFunctions.ListRowResponse_widget(responseId));
        assertTrue(reported().contains("only valid during"));

        HostFunctions.ListRowResponse_set_text(responseId, "pear");
        assertTrue(reported().contains("only valid during"));

        long colour = GrugEntities.addEntity(GrugEntityType.Color, new Color(9, 9, 9));
        HostFunctions.ListRowResponse_set_text_colour(responseId, colour);
        assertTrue(reported().contains("only valid during"));
    }

    @Test
    void aResponseWhoseEntityIsGoneIsReportedAsStale() {
        GrugEntities.removeEntity(responseId);
        assertEquals(0.0, HostFunctions.ListRowResponse_index(responseId));
        assertTrue(reported().contains("no longer resolves"));
    }

    @Test
    void aStaleColorLeavesTheRowItsText() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        HostFunctions.ListRowResponse_set_text_colour(responseId, 0);
        assertEquals(-1, response.answeredRgb());
        assertTrue(reported().contains("no longer resolves"));
        assertTrue(response.finish());
    }
}
