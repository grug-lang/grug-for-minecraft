package net.grug.minecraft.stationapi.gui;

import net.grug.minecraft.gui.GrugGuiBuilder;
import net.modificationstation.stationapi.api.network.packet.MessagePacket;

public class StationGuiHelper {

    public static void writeBuilderToPacket(GrugGuiBuilder builder, MessagePacket messagePacket, int syncId, int x,
            int y, int z) {
        String guiIdStr = (messagePacket.strings != null && messagePacket.strings.length > 0)
                ? messagePacket.strings[0]
                : "";

        // Add texts to the strings array
        String[] strings = new String[2 + builder.texts.size()];
        strings[0] = guiIdStr;
        strings[1] = builder.texturePath;
        for (int i = 0; i < builder.texts.size(); i++) {
            strings[2 + i] = builder.texts.get(i).text();
        }
        messagePacket.strings = strings;

        // 13 base elements + dynamic slots + grids + results + texts
        int numInts = 13 + (builder.blockSlots.size() * 4) + (builder.craftingGrids.size() * 3)
                + (builder.craftingResults.size() * 3) + (builder.texts.size() * 3);
        int[] ints = new int[numInts];

        ints[0] = syncId;
        ints[1] = x;
        ints[2] = y;
        ints[3] = z;
        ints[4] = builder.hasPlayerInventory ? 1 : 0;
        ints[5] = builder.playerInvX;
        ints[6] = builder.playerInvY;
        ints[7] = builder.hotbarX;
        ints[8] = builder.hotbarY;

        int idx = 9;
        ints[idx++] = builder.blockSlots.size();
        for (GrugGuiBuilder.SlotDef def : builder.blockSlots) {
            ints[idx++] = def.index();
            ints[idx++] = def.x();
            ints[idx++] = def.y();
            ints[idx++] = def.isOutput() ? 1 : 0;
        }

        ints[idx++] = builder.craftingGrids.size();
        for (GrugGuiBuilder.CraftingGridDef grid : builder.craftingGrids) {
            ints[idx++] = grid.startSlot();
            ints[idx++] = grid.x();
            ints[idx++] = grid.y();
        }

        ints[idx++] = builder.craftingResults.size();
        for (GrugGuiBuilder.CraftingResultDef res : builder.craftingResults) {
            ints[idx++] = res.slot();
            ints[idx++] = res.x();
            ints[idx++] = res.y();
        }

        ints[idx++] = builder.texts.size();
        for (GrugGuiBuilder.TextDef text : builder.texts) {
            ints[idx++] = text.x();
            ints[idx++] = text.y();
            ints[idx++] = text.color();
        }

        messagePacket.ints = ints;
    }

    public static GrugGuiBuilder readBuilderFromPacket(MessagePacket message) {
        GrugGuiBuilder builder = new GrugGuiBuilder(message.strings[1]);

        builder.hasPlayerInventory = message.ints[4] == 1;
        builder.playerInvX = message.ints[5];
        builder.playerInvY = message.ints[6];
        builder.hotbarX = message.ints[7];
        builder.hotbarY = message.ints[8];

        int idx = 9;

        int blockSlotsCount = message.ints[idx++];
        for (int i = 0; i < blockSlotsCount; i++) {
            builder.blockSlots.add(new GrugGuiBuilder.SlotDef(
                    message.ints[idx++], message.ints[idx++], message.ints[idx++],
                    message.ints[idx++] == 1));
        }

        int gridCount = message.ints[idx++];
        for (int i = 0; i < gridCount; i++) {
            builder.craftingGrids.add(new GrugGuiBuilder.CraftingGridDef(
                    message.ints[idx++], message.ints[idx++], message.ints[idx++]));
        }

        int resultsCount = message.ints[idx++];
        for (int i = 0; i < resultsCount; i++) {
            builder.craftingResults.add(new GrugGuiBuilder.CraftingResultDef(
                    message.ints[idx++], message.ints[idx++], message.ints[idx++]));
        }

        int textCount = message.ints[idx++];
        for (int i = 0; i < textCount; i++) {
            builder.texts.add(new GrugGuiBuilder.TextDef(
                    message.strings[2 + i], message.ints[idx++], message.ints[idx++], message.ints[idx++]));
        }

        return builder;
    }
}
