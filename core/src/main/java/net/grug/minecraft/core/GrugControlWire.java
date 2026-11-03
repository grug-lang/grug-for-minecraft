package net.grug.minecraft.core;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The bytes the test-control channel carries between a dedicated server and the client of the
 * player who started the run (#1040, option A). A loader moves these byte arrays in whatever custom
 * packet its game has; nothing here knows about a packet, so every loader can reuse the format and
 * a unit test can run both ends in one process.
 *
 * <p>Four messages exist. {@link #RUN_START} goes from the client to the server and says this
 * player started a run, which is the consent the client later relies on. {@link #CAPTURE_REQUEST}
 * goes the other way and names a screenshot rectangle. The client answers with either {@link
 * #ANSWER_ERROR} or one or more {@link #ANSWER_CHUNK}s that together hold a PNG: a serverbound
 * custom payload is limited to 32767 bytes on 1.20.6 and the other old games are no more generous,
 * so a capture is cut into {@link #MAX_CHUNK}-byte pieces.
 */
public final class GrugControlWire {
    public static final byte RUN_START = 1;
    public static final byte CAPTURE_REQUEST = 2;
    public static final byte ANSWER_ERROR = 3;
    public static final byte ANSWER_CHUNK = 4;

    /** Well under the 32767 bytes a serverbound payload may hold, leaving room for the header. */
    public static final int MAX_CHUNK = 20_000;

    private GrugControlWire() {}

    /** One decoded message; the fields a type does not use are zero or null. */
    public static final class Message {
        public final byte type;
        public final long id;
        public final int x1;
        public final int y1;
        public final int x2;
        public final int y2;
        public final int index;
        public final int total;
        public final byte[] data;
        public final String text;

        Message(
                byte type,
                long id,
                int[] rectangle,
                int index,
                int total,
                byte[] data,
                String text) {
            this.type = type;
            this.id = id;
            this.x1 = rectangle[0];
            this.y1 = rectangle[1];
            this.x2 = rectangle[2];
            this.y2 = rectangle[3];
            this.index = index;
            this.total = total;
            this.data = data;
            this.text = text;
        }
    }

    public static byte[] runStart() {
        return ByteBuffer.allocate(1).put(RUN_START).array();
    }

    public static byte[] captureRequest(long id, int x1, int y1, int x2, int y2) {
        return ByteBuffer.allocate(1 + 8 + 16)
                .put(CAPTURE_REQUEST)
                .putLong(id)
                .putInt(x1)
                .putInt(y1)
                .putInt(x2)
                .putInt(y2)
                .array();
    }

    public static byte[] answerError(long id, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + 8 + 4 + bytes.length)
                .put(ANSWER_ERROR)
                .putLong(id)
                .putInt(bytes.length)
                .put(bytes)
                .array();
    }

    /** The messages that carry {@code png} as the answer to request {@code id}, in order. */
    public static List<byte[]> answerChunks(long id, byte[] png) {
        int total = Math.max(1, (png.length + MAX_CHUNK - 1) / MAX_CHUNK);
        List<byte[]> messages = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            int from = index * MAX_CHUNK;
            int length = Math.min(MAX_CHUNK, png.length - from);
            messages.add(
                    ByteBuffer.allocate(1 + 8 + 4 + 4 + 4 + length)
                            .put(ANSWER_CHUNK)
                            .putLong(id)
                            .putInt(index)
                            .putInt(total)
                            .putInt(length)
                            .put(png, from, length)
                            .array());
        }
        return messages;
    }

    /** Reads a message, or throws {@link IllegalArgumentException} when the bytes are not one. */
    public static Message decode(byte[] bytes) {
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes);
            byte type = in.get();
            int[] none = new int[4];
            switch (type) {
                case RUN_START:
                    return new Message(type, 0, none, 0, 0, null, null);
                case CAPTURE_REQUEST:
                    long id = in.getLong();
                    int[] rectangle = {in.getInt(), in.getInt(), in.getInt(), in.getInt()};
                    return new Message(type, id, rectangle, 0, 0, null, null);
                case ANSWER_ERROR:
                    long errorId = in.getLong();
                    byte[] text = new byte[in.getInt()];
                    in.get(text);
                    return new Message(
                            type,
                            errorId,
                            none,
                            0,
                            0,
                            null,
                            new String(text, StandardCharsets.UTF_8));
                case ANSWER_CHUNK:
                    long chunkId = in.getLong();
                    int index = in.getInt();
                    int total = in.getInt();
                    byte[] data = new byte[in.getInt()];
                    in.get(data);
                    return new Message(type, chunkId, none, index, total, data, null);
                default:
                    throw new IllegalArgumentException("unknown control message type " + type);
            }
        } catch (BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("malformed control message: " + e, e);
        }
    }
}
