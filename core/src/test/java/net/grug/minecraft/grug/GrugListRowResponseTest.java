package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.core.GrugCore;
import net.grug.minecraft.core.ModLoaderAdapter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * The four rules every response class carries, proved for {@link GrugListRowResponse} against the
 * host functions a mod calls, and the record the screen's frame pass keeps of a broken row.
 *
 * <p>The rules are also proved from grug in {@code mods/coverage/}, through a screen's row export;
 * this test reaches the parts a grug test cannot, such as a response whose entity is gone.
 */
class GrugListRowResponseTest {

    private Object savedAdapter;
    private final List<String> logged = new ArrayList<>();
    private boolean savedRunActive;
    private GrugWidget widget;
    private long responseId;
    private GrugListRowResponse response;

    @BeforeEach
    void makeAResponse() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = field.get(null);
        field.set(
                null,
                Proxy.newProxyInstance(
                        ModLoaderAdapter.class.getClassLoader(),
                        new Class<?>[] {ModLoaderAdapter.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("logError")) {
                                logged.add((String) args[0]);
                                return null;
                            }
                            throw new AssertionError("unexpected adapter call " + method.getName());
                        }));
        savedRunActive = GrugTestState.testRunActive;
        GrugTestState.testRunActive = true;
        GrugErrors.takeHostFunctionException();
        GrugListRowResponse.clearLastDefect();
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
        widget = new GrugWidget(null, GrugWidgetKind.LIST, "rows");
        widget.entityId = GrugEntities.addEntity(GrugEntityType.Widget, widget);
        response = new GrugListRowResponse(3, widget);
        responseId = GrugEntities.addEntity(GrugEntityType.ListRowResponse, response);
    }

    @AfterEach
    void clearState() throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        GrugTestState.testRunActive = savedRunActive;
        GrugErrors.takeHostFunctionException();
        GrugListRowResponse.clearLastDefect();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
        GrugEntities.entityData.clear();
        GrugEntities.retainedFnEntities.clear();
        GrugEntities.fnEntities = GrugEntities.retainedFnEntities;
    }

    /** The host function exception a call outside the response reported, or null. */
    private static String reported() {
        return GrugErrors.takeHostFunctionException();
    }

    /** The defect the row export reported, which is the record a grug test reads. */
    private static String rowError() {
        return GrugListRowResponse.lastDefect();
    }

    @Test
    void itAnswersItsIndexAndItsList() {
        assertEquals(3.0, HostFunctions.ListRowResponse_index(responseId));
        assertSame(
                widget,
                GrugEntities.entityData.get(HostFunctions.ListRowResponse_widget(responseId))
                        .object);
        assertNull(reported());
        assertEquals("", rowError());
    }

    @Test
    void aReadyResponseIsUnansweredUntilTextIsSet() {
        assertFalse(response.finish());
        assertTrue(rowError().contains("returned without answering"));
        assertNull(reported(), "the row has no call of a test's own to fail");
        assertTrue(logged.contains(rowError()));
    }

    @Test
    void oneTextAnswerFinishesTheRow() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        assertTrue(response.finish());
        assertEquals("apple", response.answeredText());
        assertEquals(-1, response.answeredRgb(), "no colour was asked for");
        assertNull(reported());
        assertEquals("", rowError());
    }

    @Test
    void aColourIsNotAnAnswerOnItsOwn() {
        long colour = GrugEntities.addEntity(GrugEntityType.Color, new Color(1, 2, 3));
        HostFunctions.ListRowResponse_set_text_colour(responseId, colour);
        assertEquals(0x010203, response.answeredRgb());
        assertFalse(response.finish());
        assertTrue(rowError().contains("returned without answering"));
        assertNull(reported());
    }

    @Test
    void aTextOfNoCharactersIsAnEmptyRow() {
        HostFunctions.ListRowResponse_set_text(responseId, null);
        assertTrue(response.finish());
        assertEquals("", response.answeredText());
        assertNull(reported());
        assertEquals("", rowError());
    }

    @Test
    void aSecondAnswerWithTheSameTextIsAnErr() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        assertTrue(rowError().contains("already been answered"));
        assertNull(reported());
        assertTrue(response.finish(), "the first answer stands");
        assertEquals("apple", response.answeredText());
    }

    @Test
    void aSecondAnswerWithDifferentTextIsAnErrAndTheFirstStands() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        HostFunctions.ListRowResponse_set_text(responseId, "pear");
        assertTrue(rowError().contains("already been answered"));
        assertNull(reported());
        assertTrue(response.finish());
        assertEquals("apple", response.answeredText());
    }

    @Test
    void aUsedAfterItsCallReturnedResponseIsAnErrEverywhere() {
        HostFunctions.ListRowResponse_set_text(responseId, "apple");
        response.finish();

        GrugListRowResponse.clearLastDefect();
        assertEquals(0.0, HostFunctions.ListRowResponse_index(responseId));
        assertTrue(rowError().contains("only valid during"));
        assertNull(reported());

        GrugListRowResponse.clearLastDefect();
        assertEquals(0, HostFunctions.ListRowResponse_widget(responseId));
        assertTrue(rowError().contains("only valid during"));
        assertNull(reported());

        GrugListRowResponse.clearLastDefect();
        HostFunctions.ListRowResponse_set_text(responseId, "pear");
        assertTrue(rowError().contains("only valid during"));
        assertNull(reported());

        GrugListRowResponse.clearLastDefect();
        long colour = GrugEntities.addEntity(GrugEntityType.Color, new Color(9, 9, 9));
        HostFunctions.ListRowResponse_set_text_colour(responseId, colour);
        assertTrue(rowError().contains("only valid during"));
        assertNull(reported());
    }

    @Test
    void theFirstDefectOfAFrameStands() {
        response.report("first");
        response.report("second");
        assertEquals("first", rowError());
    }

    @Test
    void clearingForgetsTheLastDefect() {
        response.report("first");
        GrugListRowResponse.clearLastDefect();
        assertEquals("", rowError());
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
