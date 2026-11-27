package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

/** Covers what a block's {@code use()} reaches through the context it is handed. */
class HostFunctionsBlockUseContextTest {

    /** The player's hand, and what a call asked the adapter to do with it. */
    private static final class Hand {
        int count;
        final Object player = new Object();
        final List<String> calls = new ArrayList<>();
        ModLoaderAdapter adapter;

        Hand(int count) {
            this.count = count;
            this.adapter =
                    (ModLoaderAdapter)
                            Proxy.newProxyInstance(
                                    HostFunctionsBlockUseContextTest.class.getClassLoader(),
                                    new Class<?>[] {ModLoaderAdapter.class},
                                    (proxy, method, args) -> {
                                        switch (method.getName()) {
                                            case "getHeldCount":
                                                return (double) Hand.this.count;
                                            case "setHeldItemCount":
                                                Hand.this.count = (int) args[1];
                                                calls.add("count " + Hand.this.count);
                                                return null;
                                            case "setHeldItem":
                                                calls.add("item " + args[1]);
                                                return null;
                                            case "logError":
                                                return null;
                                            default:
                                                throw new AssertionError(
                                                        "a held item change does not ask "
                                                                + method.getName());
                                        }
                                    });
        }

        long playerId() {
            return GrugEntities.addEntity(GrugEntityType.Player, player);
        }

        long context() {
            return GrugEntities.addEntity(
                    GrugEntityType.BlockUseContext, GrugBlockUse.openContext(player));
        }

        long item() {
            return GrugEntities.addEntity(GrugEntityType.Item, "grug:test_item");
        }
    }

    private ModLoaderAdapter savedAdapter;
    private boolean savedRunActive;

    @BeforeEach
    void saveState() throws Exception {
        GrugBlockUse.closeContext();
        GrugEntities.entityData.clear();
        drain();
        GrugErrors.takeHostFunctionException();
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        savedAdapter = (ModLoaderAdapter) field.get(null);
        savedRunActive = GrugTestState.testRunActive;
        // A report a unit test makes goes to the record the runner reads, not to the game.
        GrugTestState.testRunActive = true;
    }

    @AfterEach
    void restoreState() throws Exception {
        GrugBlockUse.closeContext();
        GrugEntities.entityData.clear();
        drain();
        GrugErrors.takeHostFunctionException();
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, savedAdapter);
        GrugTestState.testRunActive = savedRunActive;
    }

    private static List<String> drain() {
        synchronized (GrugErrors.runtimeErrorQueue) {
            List<String> reports = new ArrayList<>(GrugErrors.runtimeErrorQueue);
            GrugErrors.runtimeErrorQueue.clear();
            return reports;
        }
    }

    private static void install(Hand hand) throws Exception {
        Field field = GrugCore.class.getDeclaredField("adapter");
        field.setAccessible(true);
        field.set(null, hand.adapter);
    }

    @Test
    void theOpenContextDeclinesTheClick() {
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.BlockUseContext, GrugBlockUse.openContext(new Object()));

        HostFunctions.BlockUseContext_decline_use(context);

        assertTrue(drain().isEmpty(), "A legal call reports nothing.");
        assertNull(GrugErrors.takeHostFunctionException(), "A legal call reports nothing.");
    }

    @Test
    void aContextKeptPastItsCallIsRefused() {
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.BlockUseContext, GrugBlockUse.openContext(new Object()));
        GrugBlockUse.closeContext();

        HostFunctions.BlockUseContext_decline_use(context);

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("BlockUseContext.decline_use"), reports.get(0));
    }

    @Test
    void aStackOfSeveralLosesOneAndKeepsItsItem() throws Exception {
        Hand hand = new Hand(3);
        install(hand);
        long context = hand.context();

        HostFunctions.BlockUseContext_replace_held_item(context, hand.item());

        assertEquals(List.of("count 2"), hand.calls);
        assertEquals(2, hand.count);
        assertNull(GrugErrors.takeHostFunctionException(), "A legal call reports nothing.");
    }

    @Test
    void theLastItemIsReplacedByTheNamedItem() throws Exception {
        Hand hand = new Hand(1);
        install(hand);
        long context = hand.context();
        long item = hand.item();

        HostFunctions.BlockUseContext_replace_held_item(context, item);

        assertEquals(List.of("item grug:test_item"), hand.calls);
    }

    @Test
    void anEmptyHandIsGivenTheNamedItem() throws Exception {
        Hand hand = new Hand(0);
        install(hand);
        long context = hand.context();

        HostFunctions.BlockUseContext_replace_held_item(context, hand.item());

        assertEquals(List.of("item grug:test_item"), hand.calls);
    }

    @Test
    void anItemNameNoItemHasChangesNothing() throws Exception {
        Hand hand = new Hand(2);
        install(hand);
        long context = hand.context();
        long missingItem = GrugEntities.addEntity(GrugEntityType.Item, null);

        HostFunctions.BlockUseContext_replace_held_item(context, missingItem);

        String report = GrugErrors.takeHostFunctionException();
        assertTrue(
                report != null && report.contains("BlockUseContext.replace_held_item"),
                String.valueOf(report));
        assertTrue(hand.calls.isEmpty(), "the hand is unchanged");
    }

    @Test
    void aPlayerThatNoLongerResolvesChangesNothing() throws Exception {
        Hand hand = new Hand(2);
        install(hand);
        long context =
                GrugEntities.addEntity(
                        GrugEntityType.BlockUseContext, GrugBlockUse.openContext(null));

        HostFunctions.BlockUseContext_replace_held_item(context, hand.item());

        String report = GrugErrors.takeHostFunctionException();
        assertTrue(
                report != null && report.contains("BlockUseContext.replace_held_item"),
                String.valueOf(report));
        assertTrue(hand.calls.isEmpty(), "the hand is unchanged");
    }

    @Test
    void aReplaceOnAClosedContextChangesNothing() throws Exception {
        Hand hand = new Hand(2);
        install(hand);
        long context = hand.context();
        GrugBlockUse.closeContext();

        HostFunctions.BlockUseContext_replace_held_item(context, hand.item());

        List<String> reports = drain();
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).contains("BlockUseContext.replace_held_item"), reports.get(0));
        assertTrue(hand.calls.isEmpty(), "the hand is unchanged");
    }
}
