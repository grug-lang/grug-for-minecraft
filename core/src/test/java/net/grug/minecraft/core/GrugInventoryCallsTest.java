package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.BlockPos;
import net.grug.minecraft.grug.GrugErrors;
import net.grug.minecraft.grug.GrugTestState;
import net.grug.minecraft.grug.Vec3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two inventory changes that write a block entity: the local write singleplayer does, and the
 * request the client of a server-driven run names to the server instead (#1759).
 */
class GrugInventoryCallsTest {
    /**
     * The parts of the adapter these calls use, and nothing else: a level holding block entities by
     * position, the item list a name is found in, the player's position, and the two writes. Any
     * other call fails the test, which is how a call that started asking the adapter something else
     * is noticed.
     */
    private static final class World {
        final Map<String, Object> blockEntities = new HashMap<>();
        final Map<Object, BlockPos> positions = new IdentityHashMap<>();
        final Map<Object, String> names = new IdentityHashMap<>();
        final List<Object> items = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final Object level = "the level";
        Object player = "the player";
        Vec3 playerPosition = new Vec3(10.5, 64, 10.5);

        Object addBlockEntity(int x, int y, int z) {
            Object blockEntity = new Object();
            blockEntities.put(x + "," + y + "," + z, blockEntity);
            positions.put(blockEntity, new BlockPos(x, y, z));
            return blockEntity;
        }

        Object addItem(String name) {
            Object item = new Object();
            items.add(item);
            names.put(item, name);
            return item;
        }

        ModLoaderAdapter adapter() {
            return (ModLoaderAdapter)
                    java.lang.reflect.Proxy.newProxyInstance(
                            ModLoaderAdapter.class.getClassLoader(),
                            new Class<?>[] {ModLoaderAdapter.class},
                            (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "getLevel":
                                        return level;
                                    case "getBlockEntity":
                                        return blockEntities.get(
                                                (int) Math.floor((Double) args[1])
                                                        + ","
                                                        + (int) Math.floor((Double) args[2])
                                                        + ","
                                                        + (int) Math.floor((Double) args[3]));
                                    case "getBlockPosOfBlockEntity":
                                        return positions.get(args[0]);
                                    case "dropInventory":
                                        calls.add(
                                                "drop " + args[0] + " " + args[1] + "," + args[2]
                                                        + "," + args[3]);
                                        return null;
                                    case "setItemInSlot":
                                        calls.add(
                                                "slot "
                                                        + names.get(args[2])
                                                        + "x"
                                                        + args[3]
                                                        + " in "
                                                        + args[1]);
                                        return null;
                                    case "itemName":
                                        return names.get(args[0]);
                                    case "itemCount":
                                        return items.size();
                                    case "itemByIndex":
                                        int index = (Integer) args[0];
                                        return index >= 0 && index < items.size()
                                                ? items.get(index)
                                                : null;
                                    case "playerPosition":
                                        return playerPosition;
                                    default:
                                        throw new AssertionError(
                                                "an inventory change does not ask "
                                                        + method.getName());
                                }
                            });
        }
    }

    private boolean savedRunActive;

    @BeforeEach
    void startWithNoRunActive() {
        savedRunActive = GrugTestState.testRunActive;
        GrugTestState.testRunActive = false;
        GrugErrors.takeHostFunctionException();
    }

    @AfterEach
    void restoreTheRunAndWhatWasReported() {
        GrugTestState.testRunActive = savedRunActive;
        GrugErrors.takeHostFunctionException();
        synchronized (GrugErrors.runtimeErrorQueue) {
            GrugErrors.runtimeErrorQueue.clear();
        }
    }

    @Test
    void aProcessWithNoRunChangesItsOwnBlockEntity() {
        World world = new World();
        Object blockEntity = world.addBlockEntity(11, 64, 10);
        Object item = world.addItem("minecraft:diamond");
        ModLoaderAdapter adapter = world.adapter();
        // A client that never started a run, and a server, are the singleplayer case: this process
        // holds the block entity, so both calls are its own.
        new GrugControlClient(
                noLink(), noCapture(), GrugControlClient.adapterCaller(world::adapter), -1, -1);

        GrugInventoryCalls.dropInventory(adapter, world.level, 11, 64, 10);
        GrugInventoryCalls.setItemInSlot(
                adapter, blockEntity, 0, item, 1, "BlockEntity.set_item_in_slot");

        assertEquals(
                List.of("drop the level 11.0,64.0,10.0", "slot minecraft:diamondx1.0 in 0.0"),
                world.calls);
    }

    @Test
    void aJoinedClientNamesTheBlockEntityToTheServerAndChangesNothingItself() {
        World world = new World();
        Object blockEntity = world.addBlockEntity(11, 64, 10);
        Object item = world.addItem("minecraft:diamond");
        List<byte[]> sent = new ArrayList<>();
        GrugControlClient client =
                new GrugControlClient(
                        sent::add,
                        noCapture(),
                        GrugControlClient.adapterCaller(world::adapter),
                        -1,
                        -1);
        client.beginRun();
        sent.clear();

        GrugInventoryCalls.dropInventory(world.adapter(), world.level, 11, 64, 10);
        GrugInventoryCalls.setItemInSlot(
                world.adapter(), blockEntity, 3, item, 7, "BlockEntity.set_item_in_slot");

        assertEquals(2, sent.size());
        GrugControlWire.Message drop = GrugControlWire.decode(sent.get(0));
        assertEquals(GrugControlWire.DROP_INVENTORY_REQUEST, drop.type);
        assertEquals(List.of(11.0, 64.0, 10.0), List.of(drop.volume, drop.pitch, drop.third));
        GrugControlWire.Message slot = GrugControlWire.decode(sent.get(1));
        assertEquals(GrugControlWire.SET_ITEM_IN_SLOT_REQUEST, slot.type);
        assertEquals(List.of(11.0, 64.0, 10.0), List.of(slot.volume, slot.pitch, slot.third));
        assertEquals(3.0, slot.fourth, 0.0);
        assertEquals(7.0, slot.fifth, 0.0);
        assertEquals("minecraft:diamond", slot.text);
        assertTrue(world.calls.isEmpty(), "the client's own copy is not written");
    }

    @Test
    void aSlotWriteOfAnItemTheClientCannotNameIsReported() {
        World world = new World();
        Object blockEntity = world.addBlockEntity(11, 64, 10);
        Object nameless = new Object();
        Object emptyNamed = world.addItem("");
        List<byte[]> sent = joinedClient(world);

        String noName = setItemAndReadReport(world, blockEntity, nameless);
        String emptyName = setItemAndReadReport(world, blockEntity, emptyNamed);

        // No name identifies the item, so no request is sent and nothing is written to a copy the
        // server would overwrite; the call is an err instead, which is what the printed report is.
        assertTrue(sent.isEmpty());
        assertTrue(world.calls.isEmpty());
        assertTrue(noName.contains("cannot name the item"), noName);
        assertTrue(emptyName.contains("cannot name the item"), emptyName);
    }

    @Test
    void theServerDropsTheBlockEntityItsOwnLevelHolds() {
        World world = new World();
        world.addBlockEntity(11, 64, 10);
        long before = GrugWorldRevision.current();

        String refusal =
                GrugInventoryCalls.dropInventoryOnServer(world.adapter(), world.player, 11, 64, 10);

        assertNull(refusal);
        assertEquals(List.of("drop the level 11.0,64.0,10.0"), world.calls);
        assertEquals(before + 1, GrugWorldRevision.current(), "the drop moves the world revision");

        // A position this server holds no block entity at changes nothing, and says so rather than
        // doing nothing quietly.
        String missing =
                GrugInventoryCalls.dropInventoryOnServer(world.adapter(), world.player, 12, 64, 10);
        assertTrue(missing.contains("no block entity"), missing);
        assertEquals(1, world.calls.size());
        assertEquals(before + 1, GrugWorldRevision.current());
    }

    @Test
    void theServerWritesTheItemItsOwnListNames() {
        World world = new World();
        Object blockEntity = world.addBlockEntity(11, 64, 10);
        Object diamond = world.addItem("minecraft:diamond");
        world.addItem("minecraft:dirt");
        long before = GrugWorldRevision.current();

        String refusal =
                GrugInventoryCalls.setItemInSlotOnServer(
                        world.adapter(), world.player, 11, 64, 10, 2, "minecraft:diamond", 3);

        assertNull(refusal);
        assertEquals(List.of("slot minecraft:diamondx3.0 in 2.0"), world.calls);
        assertEquals(before + 1, GrugWorldRevision.current(), "the write moves the revision");

        // A name this server has no item for, and a position it holds no block entity at, each
        // refuse the write instead of writing some other item or throwing at the game.
        String unknown =
                GrugInventoryCalls.setItemInSlotOnServer(
                        world.adapter(), world.player, 11, 64, 10, 2, "minecraft:gravel", 1);
        assertTrue(unknown.contains("minecraft:gravel"), unknown);
        String missing =
                GrugInventoryCalls.setItemInSlotOnServer(
                        world.adapter(), world.player, 12, 64, 10, 2, "minecraft:diamond", 1);
        assertTrue(missing.contains("no block entity"), missing);
        assertEquals(1, world.calls.size());
        assertEquals(before + 1, GrugWorldRevision.current());
    }

    @Test
    void aBlockEntityOutsideThePlayersReachIsRefused() {
        World world = new World();
        world.addBlockEntity(30, 64, 10);

        String refusal =
                GrugInventoryCalls.setItemInSlotOnServer(
                        world.adapter(), world.player, 30, 64, 10, 0, "minecraft:diamond", 1);

        // The player is at 10.5, 64, 10.5, so this block is 20 blocks away: the server refuses the
        // write rather than letting a client change a block it only imagines it placed.
        assertTrue(refusal.contains("within 8 blocks"), refusal);
        assertTrue(world.calls.isEmpty());

        // The reach is measured to the block's centre, and its edge is the block itself: the
        // block at 7 is 7.5 blocks along, and the one at 8 is 8.5, which is past the reach.
        assertTrue(GrugInventoryCalls.withinReach(new Vec3(0, 0, 0), 7, 0, 0));
        assertFalse(GrugInventoryCalls.withinReach(new Vec3(0, 0, 0), 8, 0, 0));
        assertTrue(GrugInventoryCalls.withinReach(new Vec3(0.5, 0.5, 0.5), 0, 0, 0));
    }

    @Test
    void aPlayerTheServerCannotPlaceIsRefused() {
        World world = new World();
        world.addBlockEntity(11, 64, 10);

        // The test player has left, or this loader cannot say where they are: neither is a write
        // this server may make, so each says so instead of writing at some guessed place.
        String noPlayer =
                GrugInventoryCalls.dropInventoryOnServer(world.adapter(), null, 11, 64, 10);
        assertTrue(noPlayer.contains("No player has joined yet"), noPlayer);

        world.playerPosition = null;
        String noPosition =
                GrugInventoryCalls.dropInventoryOnServer(world.adapter(), world.player, 11, 64, 10);
        assertTrue(noPosition.contains("cannot tell where you are"), noPosition);

        assertTrue(world.calls.isEmpty());
    }

    @Test
    void theDefaultLinkAppliesBothChanges() throws Exception {
        World world = new World();
        world.addBlockEntity(11, 64, 10);
        world.addItem("minecraft:diamond");
        GrugControlServer.Link link =
                new GrugControlServer.Link() {
                    @Override
                    public boolean sendToStarter(Object starter, byte[] message) {
                        throw new AssertionError("a change does not send");
                    }

                    @Override
                    public void flushWorld() {
                        throw new AssertionError("a change does not flush");
                    }

                    @Override
                    public boolean mayStartRun(Object sender) {
                        throw new AssertionError("a change does not start a run");
                    }

                    @Override
                    public void applyClientWork() {
                        throw new AssertionError("a change does not drain");
                    }
                };

        Field adapter = GrugCore.class.getDeclaredField("adapter");
        adapter.setAccessible(true);
        ModLoaderAdapter saved = (ModLoaderAdapter) adapter.get(null);
        adapter.set(null, world.adapter());
        try {
            assertNull(link.applyDropInventory(world.player, 11, 64, 10));
            assertNull(
                    link.applySetItemInSlot(world.player, 11, 64, 10, 0, "minecraft:diamond", 2));
        } finally {
            adapter.set(null, saved);
        }

        assertEquals(
                List.of("drop the level 11.0,64.0,10.0", "slot minecraft:diamondx2.0 in 0.0"),
                world.calls);
    }

    /**
     * Makes this test's process a client that started a run, so a slot write names its block entity
     * to the server, and answers the list its messages are recorded in.
     */
    private static List<byte[]> joinedClient(World world) {
        List<byte[]> sent = new ArrayList<>();
        GrugControlClient client =
                new GrugControlClient(
                        sent::add,
                        noCapture(),
                        GrugControlClient.adapterCaller(world::adapter),
                        -1,
                        -1);
        client.beginRun();
        sent.clear();
        return sent;
    }

    /**
     * Runs a slot write with {@code System.out} captured, and answers the report it printed. The
     * err a reported write queues is dropped afterwards, so it cannot fail the test after this one.
     */
    private static String setItemAndReadReport(World world, Object blockEntity, Object item) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            GrugInventoryCalls.setItemInSlot(
                    world.adapter(), blockEntity, 0, item, 1, "BlockEntity.set_item_in_slot");
        } finally {
            System.setOut(saved);
            synchronized (GrugErrors.runtimeErrorQueue) {
                GrugErrors.runtimeErrorQueue.clear();
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static GrugControlClient.Link noLink() {
        return message -> {
            throw new AssertionError("nothing was expected to be sent");
        };
    }

    private static GrugControlClient.Capturer noCapture() {
        return (eye, x1, y1, x2, y2, done, failed) -> {
            throw new AssertionError("no capture was expected");
        };
    }
}
