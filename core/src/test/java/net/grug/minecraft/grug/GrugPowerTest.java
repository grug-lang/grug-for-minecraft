package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the energy a grug block entity receives. */
class GrugPowerTest {

    /** Stands in for a loader's grug block entity, counting how often it was marked changed. */
    private static final class Receiver implements GrugPower.Holder {
        int changes = 0;

        @Override
        public void markEnergyChanged() {
            changes++;
        }
    }

    private static Receiver receiverHolding(double maxStored) {
        Receiver receiver = new Receiver();
        GrugPower.declare(receiver, maxStored);
        return receiver;
    }

    @Test
    void onlyAWholeNumberInRangeIsEnergy() {
        assertNull(GrugPower.checkEnergy("The amount", 0));
        assertNull(GrugPower.checkEnergy("The amount", 64));
        assertNull(GrugPower.checkEnergy("The amount", Integer.MAX_VALUE));
        assertTrue(GrugPower.checkEnergy("The amount", -1).startsWith("The amount of energy"));
        assertTrue(GrugPower.checkEnergy("The amount", 2.5).contains("2.5"));
        assertTrue(GrugPower.checkEnergy("The amount", Integer.MAX_VALUE + 1.0).contains("was"));
        assertNotNull(GrugPower.checkEnergy("The amount", Double.NaN));
        assertNotNull(GrugPower.checkEnergy("The amount", Double.POSITIVE_INFINITY));
    }

    @Test
    void onlyAGrugBlockEntityCanBeAReceiver() {
        assertNull(GrugPower.checkCanDeclare(new Receiver()));
        assertNotNull(GrugPower.checkCanDeclare(new Object()));
    }

    @Test
    void aBlockEntityIsAReceiverOnlyOnceItDeclaredItself() {
        Receiver receiver = new Receiver();

        assertFalse(GrugPower.isReceiver(receiver));
        assertNotNull(GrugPower.checkIsReceiver(receiver));

        GrugPower.declare(receiver, 10);

        assertTrue(GrugPower.isReceiver(receiver));
        assertNull(GrugPower.checkIsReceiver(receiver));
        assertFalse(GrugPower.isReceiver(new Object()));
    }

    @Test
    void aStoreFillsToItsLimitAndLosesWhatDoesNotFit() {
        Receiver receiver = receiverHolding(10);

        assertEquals(0.0, GrugPower.stored(receiver));
        assertEquals(4.0, GrugPower.receive(receiver, 4));
        assertEquals(6.0, GrugPower.receive(receiver, 20));
        assertEquals(10.0, GrugPower.stored(receiver));
        assertEquals(0.0, GrugPower.receive(receiver, 3), "a full store takes nothing");
        assertEquals(10.0, GrugPower.stored(receiver));
    }

    @Test
    void extractingTakesEverythingUpToTheMost() {
        Receiver receiver = receiverHolding(10);
        GrugPower.receive(receiver, 10);

        assertEquals(3.0, GrugPower.extract(receiver, 3));
        assertEquals(7.0, GrugPower.stored(receiver));
        assertEquals(7.0, GrugPower.extract(receiver, 100));
        assertEquals(0.0, GrugPower.stored(receiver));
        assertEquals(0.0, GrugPower.extract(receiver, 5), "an empty store gives nothing");
    }

    @Test
    void movingNothingDoesNotMarkTheBlockEntityChanged() {
        Receiver receiver = receiverHolding(10);

        GrugPower.receive(receiver, 0);
        GrugPower.extract(receiver, 5);
        assertEquals(0, receiver.changes);

        GrugPower.receive(receiver, 10);
        GrugPower.receive(receiver, 10);
        assertEquals(1, receiver.changes, "a full store sent more is not a change");

        GrugPower.extract(receiver, 0);
        assertEquals(1, receiver.changes);
        GrugPower.extract(receiver, 4);
        assertEquals(2, receiver.changes);
    }

    @Test
    void declaringAgainKeepsTheEnergyAndReplacesTheLimit() {
        Receiver receiver = receiverHolding(10);
        GrugPower.receive(receiver, 8);

        GrugPower.declare(receiver, 10);
        assertEquals(8.0, GrugPower.stored(receiver), "declaring again does not empty the store");

        GrugPower.declare(receiver, 5);
        assertEquals(5.0, GrugPower.stored(receiver), "a lower limit cuts the store down to it");

        GrugPower.declare(receiver, 20);
        assertEquals(15.0, GrugPower.receive(receiver, 100), "the new limit is the one in force");
        assertEquals(20.0, GrugPower.stored(receiver));
    }

    @Test
    void loadingPutsBackTheSavedEnergyWithoutCountingAsAChange() {
        Receiver reloaded = new Receiver();

        GrugPower.load(reloaded, 7);
        assertEquals(7.0, GrugPower.stored(reloaded));
        assertEquals(0, reloaded.changes, "loading what was saved is not a change to save");

        GrugPower.declare(reloaded, 10);
        assertEquals(
                7.0, GrugPower.stored(reloaded), "declaring after a load keeps what was saved");
        assertEquals(0, reloaded.changes);
    }

    @Test
    void energyIsNotASavedNumber() {
        Receiver receiver = receiverHolding(10);
        GrugPower.receive(receiver, 7);

        assertTrue(GrugSavedNumbers.snapshot(receiver).isEmpty());
        assertEquals(0.0, GrugSavedNumbers.get(receiver, "energy"));
    }
}
