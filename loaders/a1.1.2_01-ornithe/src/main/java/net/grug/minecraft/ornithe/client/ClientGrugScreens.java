package net.grug.minecraft.ornithe.client;

import net.fabricmc.api.ClientModInitializer;
import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.gui.GrugGuiBuilder;
import net.grug.minecraft.ornithe.gui.GrugScreenContentPacket;
import net.grug.minecraft.ornithe.gui.GrugScreenOpenPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The client's half of the GUI packets a dedicated Alpha server sends (#1098).
 *
 * <p>It is a loader-only entrypoint because its handler names {@link GrugScreen}, which only this
 * loader has. Alpha hands a packet to the network side, so both packets are queued here and applied
 * from the client tick on the thread that draws, the way a saved-data packet is (#1093). The queue
 * keeps the server's order, so a menu's contents are applied after the packet that opened it
 * installed the screen.
 *
 * <p>Nothing here runs on a singleplayer client, which opens its screen itself, or on the server,
 * which only sends these packets.
 */
@GrugGenerated("client glue: the screen it drives is excluded from coverage")
public final class ClientGrugScreens implements ClientModInitializer {
    /** The order the server sent them in, so an open is applied before its contents. */
    private static final List<Pending> QUEUE = new ArrayList<>();

    /** The screen the last applied open installed, and the one a content packet now belongs to. */
    private static GrugScreen active = null;

    @Override
    public void onInitializeClient() {
        GrugScreenOpenPacket.clientHandler = ClientGrugScreens::queueOpen;
        GrugScreenContentPacket.clientHandler = ClientGrugScreens::queueContents;
    }

    private static void queueOpen(int x, int y, int z, int windowId, GrugGuiBuilder layout) {
        Pending pending = new Pending();
        pending.open = true;
        pending.x = x;
        pending.y = y;
        pending.z = z;
        pending.windowId = windowId;
        pending.layout = layout;
        synchronized (QUEUE) {
            QUEUE.add(pending);
        }
    }

    private static void queueContents(int windowId, ItemStack cursor, ItemStack[] slots) {
        Pending pending = new Pending();
        pending.windowId = windowId;
        pending.cursor = cursor;
        pending.slots = slots;
        synchronized (QUEUE) {
            QUEUE.add(pending);
        }
    }

    /**
     * Applies what the server sent since the last tick. Called at the head of the client tick, on
     * the thread that draws, so a screen is installed before the next frame rather than from the
     * network.
     */
    public static void applyPending(Minecraft minecraft) {
        List<Pending> ready;
        synchronized (QUEUE) {
            if (QUEUE.isEmpty()) return;
            ready = new ArrayList<>(QUEUE);
            QUEUE.clear();
        }
        for (Pending pending : ready) {
            if (pending.open) {
                active =
                        GrugScreen.openFromServer(
                                pending.x, pending.y, pending.z, pending.windowId, pending.layout);
            } else if (active != null) {
                active.applyContents(pending.windowId, pending.cursor, pending.slots);
            }
        }
    }

    /** Called by the screen it opened, so a content packet reaches the screen now on show. */
    public static void opened(GrugScreen screen) {
        active = screen;
    }

    /** Called by a closing screen, so a content packet cannot reach one that is gone. */
    public static void closed(GrugScreen screen) {
        if (active == screen) {
            active = null;
        }
    }

    /** One packet waiting for the client tick, as the server sent it. */
    @GrugGenerated("client glue: the screen it drives is excluded from coverage")
    private static final class Pending {
        boolean open;
        int x;
        int y;
        int z;
        int windowId;
        GrugGuiBuilder layout;
        ItemStack cursor;
        ItemStack[] slots;
    }
}
