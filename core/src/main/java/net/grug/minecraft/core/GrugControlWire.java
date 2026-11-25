package net.grug.minecraft.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
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
 * <p>Twelve messages exist. {@link #RUN_START} goes from the client to the server and says this
 * player started a run, which is the consent the client later relies on. {@link #CAPTURE_REQUEST}
 * goes the other way and names a screenshot rectangle. The client answers with either {@link
 * #ANSWER_ERROR} or one or more {@link #ANSWER_CHUNK}s that together hold a PNG: a serverbound
 * custom payload is limited to 32767 bytes on 1.20.6 and the other old games are no more generous,
 * so a capture is cut into {@link #MAX_CHUNK}-byte pieces. {@link #CALL_REQUEST} carries one of the
 * client-only host functions a server may forward, with a name and the six numbers the call may
 * use, which the client answers with a {@link #ANSWER_VALUE}, or with an {@link #ANSWER_NUMBER}
 * when the call's answer is a measurement such as a screen's width. A call that measures a whole
 * screen answers with one {@link #ANSWER_NUMBERS}, so reading six values costs one round trip
 * rather than six (#1277). {@link #GIVE_REQUEST} goes from the client to the server and asks it to
 * give the player an item, which is the one host function whose effect belongs to the server rather
 * than to the client that ran it (#1099). It names the item, because two processes whose item lists
 * differ give different items under the same index. {@link #CLICK_WRITTEN} goes from the client to
 * the server after a press has written the game's own click packet, and is the ordering barrier
 * that says the server's connection holds that packet, so the runner may not tick again until it is
 * handled (#1393).
 */
public final class GrugControlWire {
    public static final byte RUN_START = 1;
    public static final byte CAPTURE_REQUEST = 2;
    public static final byte ANSWER_ERROR = 3;
    public static final byte ANSWER_CHUNK = 4;

    /** Server to client: the run is over, so the client stops obeying forwarded calls. */
    public static final byte RUN_END = 5;

    /**
     * Server to client: perform one client-only host function and answer with {@link #ANSWER_VALUE}
     * or {@link #ANSWER_ERROR}. The call is one of the {@code CALL_} codes, with a name and six
     * numbers the call may use.
     */
    public static final byte CALL_REQUEST = 6;

    /** Client to server: the call was performed; it carries the one-byte result. */
    public static final byte ANSWER_VALUE = 7;

    /** Client to server: the call was performed; it carries the number it measured. */
    public static final byte ANSWER_NUMBER = 8;

    /**
     * Client to server: give the player {@code count} of the item named in the message (#1099). The
     * client sends it while its run is active, from the give an overlay's click performs, and the
     * server gives that item to its own copy of the test player: a write to the client's copy would
     * be overwritten by the next inventory packet, and a test reads the server's copy. The server
     * finds the item by its name, never by a list index, because the two processes can list
     * different items. It has no answer, because the test asserts on the world the server changed.
     */
    public static final byte GIVE_REQUEST = 9;

    /**
     * Client to server: the call was performed; it carries the {@link #GEOMETRY_COUNT} numbers it
     * measured, in the order {@link GrugScreenCalls} lists them (#1277). It holds a whole screen's
     * geometry in one message, because a server-run test that reads several measurements of one
     * screen asks for a few dozen values in a single tick, and each round trip on a client that
     * reads its packets once per frame costs that client's frame time.
     */
    public static final byte ANSWER_NUMBERS = 10;

    /**
     * Client to server: clear the cursor of the server's own copy of the test player's menu
     * (#1389). The client sends it while its run is active, from the delete an overlay's click
     * performs, because the client's copy of the cursor is only a mirror: the server's menu is the
     * one a click applies and a test reads, and a clear of the mirror alone would leave the item on
     * the server's cursor. It carries nothing and has no answer.
     */
    public static final byte DELETE_CARRIED_REQUEST = 11;

    /**
     * Client to server: the press of a {@link #CALL_CLICK_SCREEN} has written the game's own click
     * packet on this client's connection, so that packet is in front of this message on the same
     * FIFO stream (#1393). It carries nothing and has no answer.
     *
     * <p>It is deliberately not one of {@link #handledOnRead}'s messages: a loader leaves it to the
     * game thread, where the connection's own queue handles it after the click packet. The server
     * takes that as the barrier, because the barrier being handled proves the click packet's
     * handler already ran, and holds the next runner tick until it is. A loader whose control
     * messages never reach the game thread, such as 1.20.6, gets the same ordering from its drain,
     * which empties the server's whole task queue rather than one bounded pass.
     */
    public static final byte CLICK_WRITTEN = 12;

    /** The call: does the game have the sound under the name. The answer is 1 or 0. */
    public static final byte CALL_HAS_SOUND = 1;

    /** The call: play the sound under the name at the volume and pitch. The answer is 1. */
    public static final byte CALL_PLAY_UI_SOUND = 2;

    /**
     * The call: whether the client has drawn the world the server has, for a server-run settle wait
     * (#1086). The answer is 1 or 0. Its name is empty and its volume slot carries the world
     * revision the ask is about, so a revision the client has already answered is asked of the
     * client again rather than of the server's own empty client.
     */
    public static final byte CALL_WORLD_SETTLED = 3;

    /**
     * The call: the identity of the screen the client has open. The answer is an {@link
     * #ANSWER_NUMBER} that is 0 when no screen is open and otherwise the number that names the
     * screen, which is the same number only while that screen is the one up (#1094, #1289): a
     * screen replaced by another one while the panel stayed open answers a different number, so a
     * server that remembered the earlier screen's measurements drops them.
     *
     * <p>A screen that has just appeared makes the client forget its counted frames, as the local
     * call does, so a following settle wait cannot answer from frames drawn before the screen
     * opened.
     */
    public static final byte CALL_SCREEN_OPEN = 4;

    /**
     * The call: one of the client's screen measurements, named by the call's name: {@code
     * screen_width}, {@code gui_left}, {@code gui_top}, {@code gui_width} or {@code gui_height}.
     * The answer is an {@link #ANSWER_NUMBER}, 0 when no screen is open.
     */
    public static final byte CALL_SCREEN_READ = 5;

    /**
     * The call: press a key on the open screen. The numbers are the key, then whether control was
     * held, then whether the key is released again, each of the two flags as 0 or 1. The answer is
     * 1 when an overlay took the key and 0 when it was left to the screen.
     */
    public static final byte CALL_PRESS_SCREEN_KEY = 6;

    /**
     * The call: turn the mouse wheel at a screen position. The numbers are the position's x and y,
     * in the scaled pixels a screen's own handlers use, then the delta in wheel notches. The answer
     * is 1 when an overlay took the movement and 0 when it was left to the screen.
     */
    public static final byte CALL_SCROLL_SCREEN = 7;

    /** The call: close whatever screen is open. The answer is 1. */
    public static final byte CALL_CLOSE_SCREEN = 8;

    /**
     * The call: one component of the joined client's own render camera, which is the eye of its
     * test player (#1161). The volume slot carries the axis, 0 for x, 1 for y and 2 for z, and the
     * answer is an {@link #ANSWER_NUMBER}. {@code TestTickContext.camera} makes the three calls, so
     * a test can assert that the client draws from the place the room was built around.
     */
    public static final byte CALL_CAMERA = 9;

    /**
     * The call: press a mouse button on the open screen. The numbers are the position's x and y in
     * the scaled pixels a screen's own handlers use, then the button, which is 0 for the left, 1
     * for the right and 2 for the middle. The answer is 1 when an overlay took the press and 0 when
     * it was left to the screen (#1094).
     *
     * <p>The press is the client's half of a container click: the client's own press path sends the
     * click packet the game has always sent, which the server applies on its next tick (see {@link
     * GrugControlServer#applyClientWork}).
     */
    public static final byte CALL_CLICK_SCREEN = 10;

    /** The call: open the player's own inventory screen on the client. The answer is 1 (#1094). */
    public static final byte CALL_OPEN_INVENTORY = 11;

    /**
     * The call: the number saved under the call's name by the joined client's own copy of the block
     * entity at the position the three numbers carry, which is what {@code
     * TestTickContext.client_saved_number} asks (#1162). The answer is an {@link #ANSWER_NUMBER}, 0
     * when the client holds no such block entity or the number was never saved there.
     */
    public static final byte CALL_SAVED_NUMBER = 12;

    /**
     * The call: the block light of the section a position falls in, in the client's own copy of the
     * world, which is the copy its mesher bakes a room from (#1207). The answer is the section's
     * 53-bit {@link GrugLightSync#lightFingerprint} as a number, because 2048 bytes do not fit a
     * message; the three numbers are the position's x, y and z. A server-run test asks it to wait
     * until the room's light has reached the client, rather than capturing a room meshed from the
     * light it had before the change.
     */
    public static final byte CALL_CLIENT_LIGHT = 13;

    /**
     * The call: whether the joined client draws overlays on the inventory and container screens at
     * all, which is {@code Test.overlay_supported} (#1099). The answer is 1 or 0 and is a property
     * of that client, so a server that never asks it would answer false for a loader that draws
     * them.
     */
    public static final byte CALL_OVERLAY_SUPPORTED = 14;

    /**
     * The call: whether a frame has drawn the running test's own overlay on the screen that client
     * has open now, which is {@code TestTickContext.overlay_drawn} (#1099). The call's name is the
     * mod that owns the test, because the client is not running the test itself and so has to be
     * told whose overlay it waits for: the answer is about a frame of that mod's overlay, and the
     * client drops the mark it held when the mod changes. The answer is 1 or 0.
     */
    public static final byte CALL_OVERLAY_DRAWN = 15;

    /**
     * The call: the {@link #GEOMETRY_COUNT} measurements of the client's screen in one answer
     * (#1277), which are {@code gui_left}, {@code gui_top}, {@code gui_width}, {@code gui_height}
     * and {@code screen_width} in that order. The answer is an {@link #ANSWER_NUMBERS}, all 0 on a
     * client with no screen open.
     *
     * <p>The call carries no name, because it asks for all of them, and a test still enters through
     * the one read it wants: {@link GrugControlServer#screenRead} refuses the same names and the
     * same no-screen answers it did before. A client performs it with the same adapter reads a
     * single read makes, so one value cannot disagree with another.
     */
    public static final byte CALL_SCREEN_GEOMETRY = 16;

    /**
     * The call: whether the game draws the mining crack over the block's own geometry at the
     * position in the three numbers, in the client's own copy of the world, which is {@code
     * TestTickContext.mining_crack} (#1040). The crack is a client's frame pass, so a dedicated
     * server has no overlay to ask. The answer is 1 or 0.
     */
    public static final byte CALL_MINING_CRACK = 17;

    /**
     * The call: puts a mod file of the client's own mods directory into a named state, which is
     * {@code TestTickContext.set_mod_file_state} for a client resource (#1040). The name is the
     * file's path and the first number is the state's index in {@code GrugModFileStates}. A texture
     * is read by the client alone, so a dedicated server disturbing its own copy would reload
     * nothing. The answer is the ack 1.
     */
    public static final byte CALL_MOD_FILE_STATE = 18;

    /**
     * The call: how many changes to the client resource named by the call's name the client's
     * engine has reported (first number 0, {@code TestTickContext.hot_reload_count}) or its loader
     * has finished reloading for (first number 1, {@code TestTickContext.reload_count}) (#1040).
     * The answer is an {@link #ANSWER_NUMBER}.
     */
    public static final byte CALL_RELOAD_COUNT = 19;

    /**
     * The call: one of the four questions the runner asks about the GL capabilities that normalize
     * normals, which is a client's own state (#1040). The first number picks it: {@link
     * #NORMALIZATION_CAN_FORCE}, {@link #NORMALIZATION_FORCE}, {@link #NORMALIZATION_RESTORE} or
     * {@link #NORMALIZATION_ENABLED}, which are the four methods of {@code ModLoaderAdapter} a
     * client answers. A dedicated server has no GL context, so a test whose file name ends with
     * {@code _forced_normalization} had to stay client-only. The answer is 1 or 0.
     */
    public static final byte CALL_NORMALIZATION = 20;

    /**
     * The call: whether the joined client's drawn mark was already clear when the first frame of
     * the screen it has open now began, which is {@code
     * TestTickContext.overlay_first_frame_cleared} (#1391). The call's name is the mod that owns
     * the running test, because the mark is set only by that mod's overlay. A server-run test
     * cannot read the mark itself right after it opens a screen: the client draws frames between
     * the server's open and the server's read, so the mark is set by then whether or not the
     * client's screen-open hook ran before the first frame. The answer is 1 when the mark was clear
     * at that moment, and 0 when it was set or no frame of the screen has begun yet.
     */
    public static final byte CALL_OVERLAY_FIRST_FRAME = 21;

    /** The {@link #CALL_NORMALIZATION} question whether this loader's item draw inherits it. */
    public static final int NORMALIZATION_CAN_FORCE = 0;

    /** The {@link #CALL_NORMALIZATION} order to turn the capabilities on. */
    public static final int NORMALIZATION_FORCE = 1;

    /** The {@link #CALL_NORMALIZATION} order to put back what the force found. */
    public static final int NORMALIZATION_RESTORE = 2;

    /** The {@link #CALL_NORMALIZATION} question whether the capabilities are on now. */
    public static final int NORMALIZATION_ENABLED = 3;

    /**
     * The call: whether the box the game targets and outlines for the block at the first three
     * numbers contains the block-local point in the last three, in the client's own copy of the
     * world, which is {@code TestTickContext.block_selection} (#1441). The box is the client's
     * outline: 1.2.5's dedicated server has no selection-box method at all, so that server asks the
     * joined client. The answer is 1 or 0.
     *
     * <p>The code is 22, above the 21 #1449 took for its overlay first-frame read, so a pair built
     * either side of this call refuses each other: an older build does not know call 22 at all, and
     * a newer build reads six numbers from a call request that carried three.
     */
    public static final byte CALL_BLOCK_SELECTION = 22;

    /**
     * The call: one of the three world overlay reads or presses of the joined client, which are a
     * client's own frame pass and key path (#1040): whether it asks the overlays' world key, HUD
     * and world exports at all ({@link #WORLD_OVERLAY_SUPPORTED}, {@code
     * Test.world_overlay_supported}), whether a frame has drawn the running test's overlay since
     * the last press ({@link #WORLD_OVERLAY_DRAWN}, {@code TestTickContext.world_overlay_drawn}),
     * and one press of a letter or digit key ({@link #WORLD_OVERLAY_PRESS_KEY}, {@code
     * TestTickContext.press_world_key}). The first number picks it, and the call's name is the mod
     * that owns the running test, which only the server knows and whose overlay the client's drawn
     * mark is about. A press carries its key in the second number and its control flag in the
     * third. The answer is 1 or 0, and a press answers 1.
     *
     * <p>The code is 23 and the loader's channel version is 11, above the 22 and 10 of #1441, so a
     * pair built either side of this call refuses each other.
     */
    public static final byte CALL_WORLD_OVERLAY = 23;

    /** The {@link #CALL_WORLD_OVERLAY} question whether the client asks the world exports. */
    public static final int WORLD_OVERLAY_SUPPORTED = 0;

    /** The {@link #CALL_WORLD_OVERLAY} question whether a frame has drawn the test's overlay. */
    public static final int WORLD_OVERLAY_DRAWN = 1;

    /** The {@link #CALL_WORLD_OVERLAY} order to press one letter or digit key in the world. */
    public static final int WORLD_OVERLAY_PRESS_KEY = 2;

    /** The number of measurements a {@link #CALL_SCREEN_GEOMETRY} answers, in its one message. */
    public static final int GEOMETRY_COUNT = 5;

    /** The longest name a call or a give may carry, in bytes. */
    public static final int MAX_NAME = 256;

    /**
     * The most items a {@link #GIVE_REQUEST} may give. A give is one item or the item's full stack,
     * which every version caps well below this, so a larger count is a message that is not one of
     * the two host functions.
     */
    public static final int MAX_GIVE_COUNT = 4096;

    /** Well under the 32767 bytes a serverbound payload may hold, leaving room for the header. */
    public static final int MAX_CHUNK = 20_000;

    /**
     * The most bytes of PNG an answer may hold in all; a bigger one is an error, never a buffer.
     */
    public static final int MAX_PNG_BYTES = 4 << 20;

    /** The most chunks an answer may be cut into, which {@link #MAX_PNG_BYTES} needs. */
    public static final int MAX_CHUNKS = (MAX_PNG_BYTES + MAX_CHUNK - 1) / MAX_CHUNK;

    /** The most bytes of text an error answer may hold. */
    public static final int MAX_TEXT = 8192;

    /** The longest message a packet may carry: the largest chunk message, with its header. */
    public static final int MAX_FRAME = MAX_CHUNK + 64;

    private GrugControlWire() {}

    /**
     * Reads one length-prefixed message from a packet, as every loader's control packet does. The
     * length comes off the network, so it is bounded before anything is allocated: a connection
     * that sends a huge or negative one is closed by the reader that sees the exception.
     */
    public static byte[] readFrame(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException(
                    "A control message of " + length + " bytes is outside 0 to " + MAX_FRAME);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    /** Writes one message as {@link #readFrame} reads it. */
    public static void writeFrame(DataOutput out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** One decoded message; the fields a type does not use are zero or null. */
    public static final class Message {
        public final byte type;
        public final long id;

        /**
         * The x of a capture's rectangle. A {@link #GIVE_REQUEST} puts its item count here, and the
         * item's name in {@link #text}.
         */
        public final int x1;

        public final int y1;
        public final int x2;
        public final int y2;
        public final int index;
        public final int total;
        public final byte[] data;

        /** The name of a call request, or of the item a {@link #GIVE_REQUEST} names. */
        public final String text;

        /** The {@code CALL_} code of a call request. */
        public final byte call;

        /**
         * The volume and pitch of a {@link #CALL_PLAY_UI_SOUND}, or the numbers a screen call
         * carries. The slot's name is the sound's because it held that first: {@link
         * #CALL_WORLD_SETTLED} puts its world revision in the volume slot, {@link
         * #CALL_PRESS_SCREEN_KEY} puts its key there, its control flag in the pitch slot and its
         * release flag in the third, and {@link #CALL_SCROLL_SCREEN} puts its x, y and delta there
         * in that order. {@link #CALL_BLOCK_SELECTION} puts the block's position there and the
         * block-local point in {@link #fourth}, {@link #fifth} and {@link #sixth}. A {@link
         * #CAPTURE_REQUEST} puts the eye it must be taken from there, x, y and z in that order
         * (#1161). The name is in {@link #text}.
         */
        public final double volume;

        public final double pitch;

        /** The third number of a call request; see {@link #volume}. */
        public final double third;

        /**
         * The fourth, fifth and sixth number of a call request, which {@link #CALL_BLOCK_SELECTION}
         * uses for the block-local point it asks about. Zero for every other call.
         */
        public final double fourth;

        public final double fifth;

        public final double sixth;

        /** The result byte of a value answer. */
        public final byte value;

        /** The number a {@link #ANSWER_NUMBER} measured. */
        public final double number;

        /**
         * The numbers an {@link #ANSWER_NUMBERS} measured, in the order {@link GrugScreenCalls}
         * lists the screen's measurements; null for every other message.
         */
        public final double[] numbers;

        Message(
                byte type,
                long id,
                int[] rectangle,
                int index,
                int total,
                byte[] data,
                String text) {
            this(
                    type, id, rectangle, index, total, data, text, (byte) 0, 0, 0, 0, 0, 0, 0,
                    (byte) 0, 0, null);
        }

        Message(
                byte type,
                long id,
                int[] rectangle,
                int index,
                int total,
                byte[] data,
                String text,
                byte call,
                double volume,
                double pitch,
                double third,
                double fourth,
                double fifth,
                double sixth,
                byte value,
                double number,
                double[] numbers) {
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
            this.call = call;
            this.volume = volume;
            this.pitch = pitch;
            this.third = third;
            this.fourth = fourth;
            this.fifth = fifth;
            this.sixth = sixth;
            this.value = value;
            this.number = number;
            this.numbers = numbers;
        }
    }

    /** Whether {@code type} is one of the answers a client sends, which a frozen server reads. */
    public static boolean isAnswer(byte type) {
        return type == ANSWER_ERROR
                || type == ANSWER_CHUNK
                || type == ANSWER_VALUE
                || type == ANSWER_NUMBER
                || type == ANSWER_NUMBERS;
    }

    /**
     * Whether a message from the client changes no world state when the server takes it on the
     * connection's reader thread: an answer completes a call the server thread is waiting on, and a
     * give only joins the queue the server thread drains (#1280). A start is not one of them,
     * because the server learns the player of a connection on its game thread and refuses a start
     * from a connection whose player it has not learnt yet. Nor is {@link #CLICK_WRITTEN}: it is
     * the barrier that proves the game's click packet in front of it was handled, so only the game
     * thread, reading its own FIFO packet queue, can read it after that packet (#1393).
     *
     * <p>A give read on the game thread waits for the connection's own drain, which a frozen server
     * only reaches between two calls: on b1.7.3 that drain reads at most 101 packets, so a give
     * behind the client's own packets arrived many ticks late, and behind enough of them not at all
     * (#1280). Read here, it waits for the next runner tick instead, as a give on 1.20.6 does,
     * whose channel hands every message to the server on its network thread.
     *
     * <p>This is the wire's rule only: a loader passes the connection it read the message from to
     * {@code GrugControlServer#onRead}, which adds that a give is taken there only once the server
     * has a starter and the loader's {@code Link} says it has learnt the player of that connection
     * (#1288, #1418).
     */
    public static boolean handledOnRead(byte type) {
        return isAnswer(type) || type == GIVE_REQUEST || type == DELETE_CARRIED_REQUEST;
    }

    /** Whether {@code call} is one of the calls this wire carries. */
    public static boolean isCall(byte call) {
        return call >= CALL_HAS_SOUND && call <= CALL_WORLD_OVERLAY;
    }

    /**
     * Whether {@code call} can make the client send the server a packet, which is what the server
     * has to drain its connection to apply (#1094).
     *
     * <p>A click sends the game's own click packet, a key press can close the screen it is pressed
     * on, and closing or opening a screen sends the window packets the game sends for them. A read
     * sends nothing, so a tick that only measures a screen does not drain: the drain is the cost of
     * a call, paid only by the calls that can leave the server work to do.
     */
    public static boolean sendsPacket(byte call) {
        return call == CALL_CLICK_SCREEN
                || call == CALL_PRESS_SCREEN_KEY
                || call == CALL_CLOSE_SCREEN
                || call == CALL_OPEN_INVENTORY;
    }

    public static byte[] callRequest(
            long id, byte call, String name, double volume, double pitch, double third) {
        return callRequest(id, call, name, volume, pitch, third, 0, 0, 0);
    }

    /**
     * The ask for {@code call} with six numbers, which is what a call needs when its question is
     * two positions: {@link #CALL_BLOCK_SELECTION} names the block in the first three and the
     * block-local point in the last three (#1441).
     */
    public static byte[] callRequest(
            long id,
            byte call,
            String name,
            double volume,
            double pitch,
            double third,
            double fourth,
            double fifth,
            double sixth) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + 8 + 1 + 4 + bytes.length + 8 + 8 + 8 + 8 + 8 + 8)
                .put(CALL_REQUEST)
                .putLong(id)
                .put(call)
                .putInt(bytes.length)
                .put(bytes)
                .putDouble(volume)
                .putDouble(pitch)
                .putDouble(third)
                .putDouble(fourth)
                .putDouble(fifth)
                .putDouble(sixth)
                .array();
    }

    public static byte[] answerValue(long id, byte value) {
        return ByteBuffer.allocate(1 + 8 + 1).put(ANSWER_VALUE).putLong(id).put(value).array();
    }

    /**
     * The give {@code count} of the item named {@code item} (#1099). The name is what the loader
     * uses to identify it, such as {@code minecraft:stone}, and the server gives the item its own
     * list names that way rather than whatever sits at an index: the two processes' lists can
     * differ, and an index would then quietly give the wrong item.
     */
    public static byte[] giveRequest(String item, int count) {
        byte[] bytes = item.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + 4 + bytes.length + 4)
                .put(GIVE_REQUEST)
                .putInt(bytes.length)
                .put(bytes)
                .putInt(count)
                .array();
    }

    /** The ask to clear the cursor of the server's copy of the test player's menu (#1389). */
    public static byte[] deleteCarriedRequest() {
        return new byte[] {DELETE_CARRIED_REQUEST};
    }

    /** The barrier that follows a press's click packet on the client's connection (#1393). */
    public static byte[] clickWritten() {
        return new byte[] {CLICK_WRITTEN};
    }

    /** The answer to a call whose result is a measurement, such as a screen's width. */
    public static byte[] answerNumber(long id, double number) {
        return ByteBuffer.allocate(1 + 8 + 8)
                .put(ANSWER_NUMBER)
                .putLong(id)
                .putDouble(number)
                .array();
    }

    /**
     * The answer to a {@link #CALL_SCREEN_GEOMETRY}: {@code numbers}, which is exactly {@link
     * #GEOMETRY_COUNT} measurements in the order {@link GrugScreenCalls} lists them.
     */
    public static byte[] answerNumbers(long id, double[] numbers) {
        if (numbers.length != GEOMETRY_COUNT) {
            throw new IllegalArgumentException(
                    "a numbers answer carries "
                            + GEOMETRY_COUNT
                            + " numbers, and was given "
                            + numbers.length);
        }
        ByteBuffer out = ByteBuffer.allocate(1 + 8 + 8 * GEOMETRY_COUNT).put(ANSWER_NUMBERS);
        out.putLong(id);
        for (double number : numbers) {
            out.putDouble(number);
        }
        return out.array();
    }

    /**
     * The ask for {@link #CALL_WORLD_SETTLED} at {@code revision}, the counter of the test's own
     * edits {@link GrugWorldRevision} keeps. A revision is far below the 2^53 up to which a double
     * holds a whole number exactly, so the call's volume slot carries it without loss.
     */
    public static byte[] worldSettledRequest(long id, long revision) {
        return worldSettledRequest(id, revision, "");
    }

    /**
     * As {@link #worldSettledRequest(long, long)}, with the text of {@link GrugBlockRegion#encode}
     * in the name slot: the box of blocks the test placed and the server's checksum of it, which
     * the client's copy has to match before it answers settled (#1664). Empty for no placements.
     */
    public static byte[] worldSettledRequest(long id, long revision, String region) {
        return callRequest(id, CALL_WORLD_SETTLED, region, (double) revision, 0, 0);
    }

    public static byte[] runStart() {
        return ByteBuffer.allocate(1).put(RUN_START).array();
    }

    public static byte[] runEnd() {
        return ByteBuffer.allocate(1).put(RUN_END).array();
    }

    /**
     * The ask for a capture of the rectangle, taken from {@code eye}: the render camera's position
     * the test's room was built above (#1161). The client waits for its own camera to reach it and
     * for a frame to be drawn from there before it captures, so the pixels are the ones a run this
     * client drove would have drawn rather than whatever the joined player's view happened to be.
     */
    public static byte[] captureRequest(
            long id, int x1, int y1, int x2, int y2, double eyeX, double eyeY, double eyeZ) {
        return ByteBuffer.allocate(1 + 8 + 16 + 24)
                .put(CAPTURE_REQUEST)
                .putLong(id)
                .putInt(x1)
                .putInt(y1)
                .putInt(x2)
                .putInt(y2)
                .putDouble(eyeX)
                .putDouble(eyeY)
                .putDouble(eyeZ)
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

    /**
     * Reads a message, or throws {@link IllegalArgumentException} when the bytes are not one. Every
     * length, count and index is checked against the bytes that remain and against a fixed cap
     * before anything is allocated from it, so a hostile peer cannot make this allocate more than
     * the message it sent.
     */
    public static Message decode(byte[] bytes) {
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes);
            byte type = in.get();
            int[] none = new int[4];
            Message message;
            switch (type) {
                case RUN_START:
                case RUN_END:
                case DELETE_CARRIED_REQUEST:
                case CLICK_WRITTEN:
                    message = new Message(type, 0, none, 0, 0, null, null);
                    break;
                case GIVE_REQUEST:
                    byte[] given = new byte[checkedLength(in, MAX_NAME, "give name")];
                    if (given.length == 0) {
                        throw new IllegalArgumentException("a give names no item");
                    }
                    in.get(given);
                    int giveCount = in.getInt();
                    if (giveCount < 1 || giveCount > MAX_GIVE_COUNT) {
                        throw new IllegalArgumentException(
                                "a give of "
                                        + giveCount
                                        + " items is outside 1 to "
                                        + MAX_GIVE_COUNT);
                    }
                    message =
                            new Message(
                                    type,
                                    0,
                                    new int[] {giveCount, 0, 0, 0},
                                    0,
                                    0,
                                    null,
                                    new String(given, StandardCharsets.UTF_8));
                    break;
                case CAPTURE_REQUEST:
                    long id = in.getLong();
                    int[] rectangle = {in.getInt(), in.getInt(), in.getInt(), in.getInt()};
                    double[] eye = {in.getDouble(), in.getDouble(), in.getDouble()};
                    for (double value : eye) {
                        if (Double.isNaN(value) || Double.isInfinite(value)) {
                            throw new IllegalArgumentException(
                                    "a capture's eye is not a finite position");
                        }
                    }
                    message =
                            new Message(
                                    type, id, rectangle, 0, 0, null, null, (byte) 0, eye[0], eye[1],
                                    eye[2], 0, 0, 0, (byte) 0, 0, null);
                    break;
                case ANSWER_ERROR:
                    long errorId = in.getLong();
                    byte[] text = new byte[checkedLength(in, MAX_TEXT, "text")];
                    in.get(text);
                    message =
                            new Message(
                                    type,
                                    errorId,
                                    none,
                                    0,
                                    0,
                                    null,
                                    new String(text, StandardCharsets.UTF_8));
                    break;
                case ANSWER_CHUNK:
                    long chunkId = in.getLong();
                    int index = in.getInt();
                    int total = in.getInt();
                    if (total < 1 || total > MAX_CHUNKS) {
                        throw new IllegalArgumentException(
                                "a chunk count of " + total + " is outside 1 to " + MAX_CHUNKS);
                    }
                    if (index < 0 || index >= total) {
                        throw new IllegalArgumentException(
                                "chunk " + index + " is outside 0 to " + (total - 1));
                    }
                    byte[] data = new byte[checkedLength(in, MAX_CHUNK, "chunk")];
                    in.get(data);
                    message = new Message(type, chunkId, none, index, total, data, null);
                    break;
                case CALL_REQUEST:
                    long callId = in.getLong();
                    byte call = in.get();
                    if (!isCall(call)) {
                        throw new IllegalArgumentException("unknown call " + call);
                    }
                    byte[] name = new byte[checkedLength(in, MAX_NAME, "name")];
                    in.get(name);
                    double volume = in.getDouble();
                    double pitch = in.getDouble();
                    double third = in.getDouble();
                    double fourth = in.getDouble();
                    double fifth = in.getDouble();
                    double sixth = in.getDouble();
                    if (Double.isNaN(volume)
                            || Double.isNaN(pitch)
                            || Double.isNaN(third)
                            || Double.isNaN(fourth)
                            || Double.isNaN(fifth)
                            || Double.isNaN(sixth)) {
                        throw new IllegalArgumentException("a call number is not a number");
                    }
                    message =
                            new Message(
                                    type,
                                    callId,
                                    none,
                                    0,
                                    0,
                                    null,
                                    new String(name, StandardCharsets.UTF_8),
                                    call,
                                    volume,
                                    pitch,
                                    third,
                                    fourth,
                                    fifth,
                                    sixth,
                                    (byte) 0,
                                    0,
                                    null);
                    break;
                case ANSWER_VALUE:
                    long valueId = in.getLong();
                    byte value = in.get();
                    message =
                            new Message(
                                    type, valueId, none, 0, 0, null, null, (byte) 0, 0, 0, 0, 0, 0,
                                    0, value, 0, null);
                    break;
                case ANSWER_NUMBER:
                    long numberId = in.getLong();
                    double number = in.getDouble();
                    if (Double.isNaN(number)) {
                        throw new IllegalArgumentException("an answer number is not a number");
                    }
                    message =
                            new Message(
                                    type, numberId, none, 0, 0, null, null, (byte) 0, 0, 0, 0, 0, 0,
                                    0, (byte) 0, number, null);
                    break;
                case ANSWER_NUMBERS:
                    long numbersId = in.getLong();
                    double[] numbers = new double[GEOMETRY_COUNT];
                    for (int at = 0; at < numbers.length; at++) {
                        numbers[at] = in.getDouble();
                        if (Double.isNaN(numbers[at])) {
                            throw new IllegalArgumentException("an answer number is not a number");
                        }
                    }
                    message =
                            new Message(
                                    type, numbersId, none, 0, 0, null, null, (byte) 0, 0, 0, 0, 0,
                                    0, 0, (byte) 0, 0, numbers);
                    break;
                default:
                    throw new IllegalArgumentException("unknown control message type " + type);
            }
            if (in.hasRemaining()) {
                throw new IllegalArgumentException(
                        "malformed control message: " + in.remaining() + " trailing bytes");
            }
            return message;
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("malformed control message: " + e, e);
        }
    }

    /**
     * A length read from the message: not negative, within {@code max}, and present in the bytes.
     */
    private static int checkedLength(ByteBuffer in, int max, String what) {
        int length = in.getInt();
        if (length < 0 || length > max) {
            throw new IllegalArgumentException(
                    "a " + what + " length of " + length + " is outside 0 to " + max);
        }
        if (length > in.remaining()) {
            throw new IllegalArgumentException(
                    "a "
                            + what
                            + " length of "
                            + length
                            + " but only "
                            + in.remaining()
                            + " bytes");
        }
        return length;
    }
}
