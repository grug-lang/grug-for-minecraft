package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

class GrugControlFrameTest {
    private static DataInputStream in(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static byte[] lengthOnly(int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DataOutputStream(out).writeInt(length);
        return out.toByteArray();
    }

    @Test
    void aFrameRoundTripsAndTheLargestChunkMessageFits() throws IOException {
        byte[] message = GrugControlWire.answerChunks(1, new byte[50_000]).get(0);
        assertEquals(true, message.length <= GrugControlWire.MAX_FRAME);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GrugControlWire.writeFrame(new DataOutputStream(out), message);
        assertArrayEquals(message, GrugControlWire.readFrame(in(out.toByteArray())));
        ByteArrayOutputStream empty = new ByteArrayOutputStream();
        GrugControlWire.writeFrame(new DataOutputStream(empty), new byte[0]);
        assertEquals(0, GrugControlWire.readFrame(in(empty.toByteArray())).length);
    }

    @Test
    void aNegativeOrOversizeLengthIsRefusedBeforeAnythingIsAllocated() throws IOException {
        assertThrows(IOException.class, () -> GrugControlWire.readFrame(in(lengthOnly(-1))));
        assertThrows(
                IOException.class,
                () -> GrugControlWire.readFrame(in(lengthOnly(GrugControlWire.MAX_FRAME + 1))));
        assertThrows(
                IOException.class,
                () -> GrugControlWire.readFrame(in(lengthOnly(Integer.MAX_VALUE))));
    }

    @Test
    void aFrameCutShortIsAnIoError() throws IOException {
        byte[] header = lengthOnly(10);
        assertThrows(IOException.class, () -> GrugControlWire.readFrame(in(header)));
    }

    private static class Base {
        private final String name = "base";
    }

    private static class Holder extends Base {
        private final Integer number = 5;
        private final String empty = null;
    }

    @Test
    void aFieldIsFoundByItsTypeInTheClassOrASuperclass() throws IllegalAccessException {
        Holder holder = new Holder();
        assertEquals(5, GrugFieldByType.find(holder, Integer.class));
        assertEquals("base", GrugFieldByType.find(holder, String.class));
        assertNull(GrugFieldByType.find(holder, Double.class));
        assertNull(GrugFieldByType.find(null, String.class));
        Object self = new Object();
        assertNull(GrugFieldByType.find(self, Object.class));
        assertSame(holder, GrugFieldByType.find(new Wrapper(holder), Holder.class));
    }

    private static class Wrapper {
        private final Holder inner;

        Wrapper(Holder inner) {
            this.inner = inner;
        }
    }
}
